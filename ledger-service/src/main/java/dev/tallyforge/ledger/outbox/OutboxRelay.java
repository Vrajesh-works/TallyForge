package dev.tallyforge.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls unpublished outbox rows and publishes them to Kafka, keyed by aggregate id so events for
 * one transfer stay ordered within a partition. Rows are locked with {@code FOR UPDATE SKIP
 * LOCKED}, so several service instances can relay in parallel without publishing a row twice
 * concurrently. A row is marked published only after the broker acknowledges it, which makes
 * delivery at-least-once; consumers deduplicate on the event id.
 */
@Component
@ConditionalOnProperty(
    name = "tallyforge.outbox.relay-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;
  private final TopicsProperties topics;
  private final int batchSize = 200;

  public OutboxRelay(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      ObjectMapper mapper,
      TopicsProperties topics) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.mapper = mapper;
    this.topics = topics;
  }

  @Scheduled(fixedDelayString = "${tallyforge.outbox.poll-interval-ms:200}")
  public void relay() {
    try {
      Integer published;
      do {
        published = tx.execute(status -> publishBatch());
      } while (published != null && published == batchSize);
    } catch (RuntimeException e) {
      // The batch rolled back and stays unpublished; the next tick retries it.
      log.warn("Outbox relay failed, will retry: {}", e.getMessage());
    }
  }

  private int publishBatch() {
    List<Row> rows =
        jdbc.query(
            "SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload,"
                + " created_at FROM outbox_events WHERE published_at IS NULL"
                + " ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs, i) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getTimestamp("created_at").toInstant()),
            batchSize);
    if (rows.isEmpty()) {
      return 0;
    }
    List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
    for (Row row : rows) {
      sends.add(kafka.send(topics.ledgerEvents(), row.aggregateId(), envelope(row)));
    }
    for (CompletableFuture<SendResult<String, String>> send : sends) {
      try {
        send.get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException("Kafka did not acknowledge outbox batch", e);
      }
    }
    jdbc.batchUpdate(
        "UPDATE outbox_events SET published_at = now() WHERE id = ?",
        rows.stream().map(r -> new Object[] {r.id()}).toList());
    return rows.size();
  }

  private String envelope(Row row) {
    try {
      ObjectNode node = mapper.createObjectNode();
      node.put("id", row.id().toString());
      node.put("type", row.eventType());
      node.put("aggregateType", row.aggregateType());
      node.put("aggregateId", row.aggregateId());
      node.put("occurredAt", row.createdAt().toString());
      JsonNode data = mapper.readTree(row.payload());
      node.set("data", data);
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt outbox payload " + row.id(), e);
    }
  }

  private record Row(
      UUID id,
      String aggregateType,
      String aggregateId,
      String eventType,
      String payload,
      Instant createdAt) {}
}
