package dev.tallyforge.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.support.KafkaProbe;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.webhook.WebhookEndpointController.CreateEndpointRequest;
import dev.tallyforge.ledger.webhook.WebhookEndpointController.EndpointResponse;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

class WebhookIT extends IntegrationTest {

  record Received(String eventType, String signature, String body, int attempt) {}

  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaConnectionDetails kafka;

  HttpServer server;
  final List<Received> received = new CopyOnWriteArrayList<>();

  /** Attempts numbered at or below this fail with HTTP 500, for every event. */
  final AtomicInteger failAttemptsUpTo = new AtomicInteger();

  LedgerApi api;

  @BeforeEach
  void start() throws Exception {
    api = new LedgerApi(http);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          int attempt =
              Integer.parseInt(
                  exchange.getRequestHeaders().getFirst("TallyForge-Delivery-Attempt"));
          received.add(
              new Received(
                  exchange.getRequestHeaders().getFirst("TallyForge-Event-Type"),
                  exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER),
                  body,
                  attempt));
          int code = attempt <= failAttemptsUpTo.get() ? 500 : 204;
          exchange.sendResponseHeaders(code, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    jdbc.update("UPDATE webhook_endpoints SET active = FALSE");
  }

  EndpointResponse register() {
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    return http.postForObject(
        "/api/v1/webhook-endpoints", new CreateEndpointRequest(url), EndpointResponse.class);
  }

  @Test
  void deliversSignedWebhookAfterTransientFailures() throws Exception {
    EndpointResponse endpoint = register();
    failAttemptsUpTo.set(1);
    var alice = api.fundedWallet("alice", 1_000);
    var shop = api.fundedWallet("shop", 0);
    var transfer = api.transfer(alice.id(), shop.id(), 250, false, null).getBody();

    List<Received> mine = awaitDeliveries(transfer.id().toString(), 2, Duration.ofSeconds(30));

    assertThat(mine).hasSizeGreaterThanOrEqualTo(2);
    assertThat(mine.get(0).attempt()).isEqualTo(1);
    Received ok = mine.get(mine.size() - 1);
    assertThat(ok.attempt()).isEqualTo(2);
    assertThat(ok.eventType()).isEqualTo("transfer.posted");
    assertThat(
            WebhookSigner.verify(
                endpoint.secret(), ok.signature(), ok.body(), Instant.now(), Duration.ofMinutes(5)))
        .isTrue();
    JsonNode event = new ObjectMapper().readTree(ok.body());
    assertThat(event.at("/data/amount").asLong()).isEqualTo(250);
    awaitStatus(event.get("id").asText(), "DELIVERED");
  }

  @Test
  void exhaustedDeliveryIsDeadLettered() throws Exception {
    register();
    failAttemptsUpTo.set(Integer.MAX_VALUE);
    var alice = api.fundedWallet("alice", 1_000);
    var shop = api.fundedWallet("shop", 0);
    var transfer = api.transfer(alice.id(), shop.id(), 99, false, null).getBody();

    List<JsonNode> dlq =
        KafkaProbe.await(
            String.join(",", kafka.getBootstrapServers()),
            "tallyforge.webhooks.dlq",
            n -> n.at("/event/aggregateId").asText().equals(transfer.id().toString()),
            1,
            Duration.ofSeconds(40));

    assertThat(dlq).hasSize(1);
    assertThat(dlq.get(0).get("attempts").asInt()).isEqualTo(3);
    assertThat(dlq.get(0).get("lastError").asText()).isEqualTo("HTTP 500");
    awaitStatus(dlq.get(0).get("eventId").asText(), "DEAD");
  }

  private List<Received> awaitDeliveries(String transferId, int count, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      List<Received> mine = received.stream().filter(r -> r.body().contains(transferId)).toList();
      if (mine.size() >= count) {
        return mine;
      }
      Thread.sleep(100);
    }
    return received.stream().filter(r -> r.body().contains(transferId)).toList();
  }

  private void awaitStatus(String eventId, String status) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    String actual = null;
    while (Instant.now().isBefore(deadline)) {
      List<String> rows =
          jdbc.queryForList(
              "SELECT status FROM webhook_deliveries WHERE event_id = ?::uuid",
              String.class,
              eventId);
      actual = rows.isEmpty() ? null : rows.get(0);
      if (status.equals(actual)) {
        return;
      }
      Thread.sleep(100);
    }
    assertThat(actual).isEqualTo(status);
  }
}
