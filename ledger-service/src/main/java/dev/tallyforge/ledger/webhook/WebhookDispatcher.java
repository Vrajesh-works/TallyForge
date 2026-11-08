package dev.tallyforge.ledger.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tallyforge.ledger.common.Backoff;
import dev.tallyforge.ledger.outbox.TopicsProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends due webhook deliveries. Failures are rescheduled with exponential backoff and jitter; after
 * {@code maxAttempts} the delivery is marked DEAD and copied to the dead-letter topic so an
 * operator can inspect and replay it.
 */
@Component
@ConditionalOnProperty(
    name = "tallyforge.webhooks.dispatcher-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WebhookDispatcher {

  private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;
  private final TopicsProperties topics;
  private final WebhookProperties props;
  private final Backoff backoff;
  private final HttpClient client;

  public WebhookDispatcher(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      ObjectMapper mapper,
      TopicsProperties topics,
      WebhookProperties props) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.mapper = mapper;
    this.topics = topics;
    this.props = props;
    this.backoff = new Backoff(props.baseDelay(), props.maxDelay());
    this.client = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
  }

  @Scheduled(fixedDelayString = "${tallyforge.webhooks.poll-interval-ms:500}")
  public void dispatchDue() {
    try {
      tx.executeWithoutResult(status -> dispatchBatch());
    } catch (RuntimeException e) {
      log.warn("Webhook dispatch batch failed, will retry: {}", e.getMessage());
    }
  }

  private void dispatchBatch() {
    List<Due> due =
        jdbc.query(
            "SELECT d.id, d.event_id, d.event_type, d.payload::text AS payload, d.attempts,"
                + " e.url, e.secret FROM webhook_deliveries d"
                + " JOIN webhook_endpoints e ON e.id = d.endpoint_id"
                + " WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()"
                + " ORDER BY d.next_attempt_at LIMIT ? FOR UPDATE OF d SKIP LOCKED",
            (rs, i) ->
                new Due(
                    rs.getObject("id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("attempts"),
                    rs.getString("url"),
                    rs.getString("secret")),
            props.batchSize());
    for (Due d : due) {
      attempt(d);
    }
  }

  private void attempt(Due d) {
    int attempt = d.attempts() + 1;
    Integer statusCode = null;
    String error;
    try {
      long ts = Instant.now().getEpochSecond();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(d.url()))
              .timeout(props.timeout())
              .header("Content-Type", "application/json")
              .header(WebhookSigner.HEADER, WebhookSigner.sign(d.secret(), ts, d.payload()))
              .header("TallyForge-Event-Id", d.eventId().toString())
              .header("TallyForge-Event-Type", d.eventType())
              .header("TallyForge-Delivery-Attempt", String.valueOf(attempt))
              .POST(HttpRequest.BodyPublishers.ofString(d.payload()))
              .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      statusCode = response.statusCode();
      if (statusCode >= 200 && statusCode < 300) {
        jdbc.update(
            "UPDATE webhook_deliveries SET status = 'DELIVERED', attempts = ?,"
                + " last_status_code = ?, last_error = NULL, delivered_at = now() WHERE id = ?",
            attempt,
            statusCode,
            d.id());
        return;
      }
      error = "HTTP " + statusCode;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "interrupted";
    } catch (Exception e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    error = error.length() > 500 ? error.substring(0, 500) : error;
    if (attempt >= props.maxAttempts()) {
      deadLetter(d, attempt, statusCode, error);
    } else {
      Instant next = Instant.now().plus(backoff.withJitter(attempt));
      jdbc.update(
          "UPDATE webhook_deliveries SET attempts = ?, last_status_code = ?, last_error = ?,"
              + " next_attempt_at = ? WHERE id = ?",
          attempt,
          statusCode,
          error,
          Timestamp.from(next),
          d.id());
    }
  }

  private void deadLetter(Due d, int attempt, Integer statusCode, String error) {
    try {
      Map<String, Object> dlq = new LinkedHashMap<>();
      dlq.put("deliveryId", d.id().toString());
      dlq.put("eventId", d.eventId().toString());
      dlq.put("eventType", d.eventType());
      dlq.put("url", d.url());
      dlq.put("attempts", attempt);
      dlq.put("lastStatusCode", statusCode);
      dlq.put("lastError", error);
      dlq.put("event", mapper.readTree(d.payload()));
      kafka
          .send(topics.webhooksDlq(), d.eventId().toString(), mapper.writeValueAsString(dlq))
          .get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      // Leave the delivery PENDING so the next tick retries the dead-lettering itself.
      throw new IllegalStateException("Could not publish to dead-letter topic", e);
    }
    jdbc.update(
        "UPDATE webhook_deliveries SET status = 'DEAD', attempts = ?, last_status_code = ?,"
            + " last_error = ? WHERE id = ?",
        attempt,
        statusCode,
        error,
        d.id());
    log.warn("Webhook delivery {} dead-lettered after {} attempts: {}", d.id(), attempt, error);
  }

  private record Due(
      UUID id,
      UUID eventId,
      String eventType,
      String payload,
      int attempts,
      String url,
      String secret) {}
}
