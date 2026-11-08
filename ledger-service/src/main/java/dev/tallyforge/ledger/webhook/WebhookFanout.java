package dev.tallyforge.ledger.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes ledger events from Kafka and creates one delivery per active endpoint. The unique
 * (endpoint_id, event_id) constraint makes this idempotent, so Kafka redeliveries and duplicate
 * outbox publishes never produce duplicate webhooks.
 */
@Component
public class WebhookFanout {

  private static final Logger log = LoggerFactory.getLogger(WebhookFanout.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public WebhookFanout(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @KafkaListener(
      topics = "${tallyforge.topics.ledger-events}",
      groupId = "${tallyforge.webhooks.consumer-group:tallyforge-webhooks}")
  public void onEvent(String message) {
    JsonNode event;
    try {
      event = mapper.readTree(message);
    } catch (JsonProcessingException e) {
      log.error("Skipping unparseable ledger event: {}", e.getMessage());
      return;
    }
    UUID eventId = UUID.fromString(event.path("id").asText());
    String type = event.path("type").asText();
    int created =
        jdbc.update(
            "INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, payload, status)"
                + " SELECT gen_random_uuid(), e.id, ?, ?, ?::jsonb, 'PENDING'"
                + " FROM webhook_endpoints e WHERE e.active"
                + " ON CONFLICT (endpoint_id, event_id) DO NOTHING",
            eventId,
            type,
            message);
    log.debug("Event {} ({}) fanned out to {} endpoints", eventId, type, created);
  }
}
