package dev.tallyforge.ledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.common.RetryingTransactions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs a money-moving action at most once per (scope, Idempotency-Key).
 *
 * <p>The key is claimed with {@code INSERT ... ON CONFLICT DO NOTHING} inside the same transaction
 * as the action. A concurrent request with the same key blocks on the primary key until the first
 * transaction finishes: if it committed, the stored response is replayed; if it rolled back, the
 * second request claims the key and runs the action itself. Either way the action commits once.
 */
@Service
public class IdempotencyService {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final RetryingTransactions tx;
  private final Duration retention;

  public IdempotencyService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      RetryingTransactions tx,
      @Value("${tallyforge.idempotency.retention:PT24H}") Duration retention) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.tx = tx;
    this.retention = retention;
  }

  /**
   * Execute {@code action} in a retried transaction, guarded by {@code key} when present.
   *
   * @param scope endpoint name, so the same key can be used on different endpoints
   * @param key the client's Idempotency-Key header, or null for a non-idempotent call
   * @param fingerprint everything that identifies the request (path ids and body)
   */
  public ResponseEntity<Object> execute(
      String scope, String key, Object fingerprint, HttpStatus successStatus, Supplier<?> action) {
    if (key == null) {
      return ResponseEntity.status(successStatus).body(tx.execute(action));
    }
    if (key.isBlank() || key.length() > 255) {
      throw ApiException.badRequest(
          "invalid_idempotency_key", "Idempotency-Key must be 1 to 255 characters");
    }
    String requestHash = RequestHasher.hash(mapper, scope, fingerprint);
    return tx.execute(() -> claimAndRun(scope, key, requestHash, successStatus, action));
  }

  private ResponseEntity<Object> claimAndRun(
      String scope, String key, String requestHash, HttpStatus status, Supplier<?> action) {
    int claimed =
        jdbc.update(
            "INSERT INTO idempotency_keys (scope, idempotency_key, request_hash)"
                + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            scope,
            key,
            requestHash);
    if (claimed == 0) {
      return replay(scope, key, requestHash);
    }
    Object result = action.get();
    String body = toJson(result);
    jdbc.update(
        "UPDATE idempotency_keys SET response_status = ?, response_body = ?::jsonb"
            + " WHERE scope = ? AND idempotency_key = ?",
        status.value(),
        body,
        scope,
        key);
    return ResponseEntity.status(status).body(result);
  }

  private ResponseEntity<Object> replay(String scope, String key, String requestHash) {
    List<StoredResponse> rows =
        jdbc.query(
            "SELECT request_hash, response_status, response_body::text AS body"
                + " FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?",
            (rs, i) ->
                new StoredResponse(
                    rs.getString("request_hash"),
                    (Integer) rs.getObject("response_status"),
                    rs.getString("body")),
            scope,
            key);
    if (rows.isEmpty() || rows.getFirst().status() == null) {
      throw ApiException.conflict(
          "idempotency_in_progress", "A request with this Idempotency-Key is still in progress");
    }
    StoredResponse stored = rows.getFirst();
    if (!stored.requestHash().equals(requestHash)) {
      throw ApiException.unprocessable(
          "idempotency_key_reused",
          "This Idempotency-Key was already used with a different request");
    }
    return ResponseEntity.status(stored.status())
        .header(REPLAYED_HEADER, "true")
        .body(readJson(stored.body()));
  }

  /** Keys only need to outlive the client's retry window. */
  @Scheduled(fixedDelayString = "${tallyforge.idempotency.purge-interval:PT1H}")
  public void purgeExpired() {
    int deleted =
        jdbc.update(
            "DELETE FROM idempotency_keys WHERE created_at < ?",
            Timestamp.from(Instant.now().minus(retention)));
    if (deleted > 0) {
      log.info("Purged {} expired idempotency keys", deleted);
    }
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize response", e);
    }
  }

  private JsonNode readJson(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored response is not valid JSON", e);
    }
  }

  private record StoredResponse(String requestHash, Integer status, String body) {}
}
