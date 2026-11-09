package dev.tallyforge.ledger.webhook;

import dev.tallyforge.ledger.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WebhookEndpointController {

  private final JdbcTemplate jdbc;

  public WebhookEndpointController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record CreateEndpointRequest(
      @NotBlank @Size(max = 500) @Pattern(regexp = "https?://.+") String url) {}

  /** The secret is only returned once, at creation, like most webhook providers. */
  public record EndpointResponse(
      UUID id, String url, String secret, boolean active, Instant createdAt) {}

  public record DeliveryResponse(
      UUID id,
      UUID endpointId,
      UUID eventId,
      String eventType,
      String status,
      int attempts,
      Instant nextAttemptAt,
      Integer lastStatusCode,
      String lastError,
      Instant deliveredAt) {}

  @PostMapping("/webhook-endpoints")
  @ResponseStatus(HttpStatus.CREATED)
  public EndpointResponse create(@Valid @RequestBody CreateEndpointRequest req) {
    UUID id = UUID.randomUUID();
    String secret = WebhookSigner.newSecret();
    jdbc.update(
        "INSERT INTO webhook_endpoints (id, url, secret) VALUES (?, ?, ?)", id, req.url(), secret);
    return new EndpointResponse(id, req.url(), secret, true, Instant.now());
  }

  @GetMapping("/webhook-endpoints")
  public List<EndpointResponse> list() {
    return jdbc.query(
        "SELECT id, url, active, created_at FROM webhook_endpoints ORDER BY created_at",
        (rs, i) ->
            new EndpointResponse(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                null,
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()));
  }

  @DeleteMapping("/webhook-endpoints/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivate(@PathVariable UUID id) {
    if (jdbc.update("UPDATE webhook_endpoints SET active = FALSE WHERE id = ?", id) == 0) {
      throw ApiException.notFound("Webhook endpoint", id);
    }
  }

  @GetMapping("/webhook-deliveries")
  public List<DeliveryResponse> deliveries(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "100") int limit) {
    int capped = Math.min(Math.max(limit, 1), 500);
    String where = status == null ? "" : " WHERE status = ?";
    Object[] args = status == null ? new Object[] {capped} : new Object[] {status, capped};
    return jdbc.query(
        "SELECT id, endpoint_id, event_id, event_type, status, attempts, next_attempt_at,"
            + " last_status_code, last_error, delivered_at FROM webhook_deliveries"
            + where
            + " ORDER BY created_at DESC LIMIT ?",
        (rs, i) ->
            new DeliveryResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"),
                rs.getTimestamp("delivered_at") == null
                    ? null
                    : rs.getTimestamp("delivered_at").toInstant()),
        args);
  }
}
