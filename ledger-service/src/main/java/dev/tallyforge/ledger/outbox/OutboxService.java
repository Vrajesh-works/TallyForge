package dev.tallyforge.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends domain events to the outbox table inside the caller's transaction. */
@Service
public class OutboxService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(String aggregateType, Object aggregateId, String eventType, Object data) {
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)"
              + " VALUES (?, ?, ?, ?, ?::jsonb)",
          id,
          aggregateType,
          aggregateId.toString(),
          eventType,
          mapper.writeValueAsString(data));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize event " + eventType, e);
    }
    return id;
  }
}
