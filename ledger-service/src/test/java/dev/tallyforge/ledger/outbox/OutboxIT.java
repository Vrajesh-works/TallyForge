package dev.tallyforge.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.support.KafkaProbe;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Autowired KafkaConnectionDetails kafka;

  LedgerApi api;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
  }

  @Test
  void holdAndPostEventsReachKafkaInOrder() throws Exception {
    var alice = api.fundedWallet("alice", 5_000);
    var shop = api.fundedWallet("shop", 0);
    var hold = api.transfer(alice.id(), shop.id(), 2_000, true, null).getBody();
    api.post("/api/v1/transfers/" + hold.id() + "/post", null, TransferResponse.class);

    List<JsonNode> events =
        KafkaProbe.await(
            String.join(",", kafka.getBootstrapServers()),
            "tallyforge.ledger.events",
            e -> e.path("aggregateId").asText().equals(hold.id().toString()),
            2,
            Duration.ofSeconds(20));

    assertThat(events)
        .extracting(e -> e.get("type").asText())
        .containsExactly("transfer.pending", "transfer.posted");
    assertThat(events.get(1).at("/data/postedAmount").asLong()).isEqualTo(2_000);
    assertThat(events).extracting(e -> e.get("id").asText()).doesNotHaveDuplicates();
    Integer unpublished =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NULL",
            Integer.class,
            hold.id().toString());
    assertThat(unpublished).isZero();
  }

  @Test
  void rejectedTransferWritesNoEvent() {
    var alice = api.fundedWallet("alice", 100);
    var shop = api.fundedWallet("shop", 0);
    Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);

    var res = api.transfer(alice.id(), shop.id(), 101, false, null);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
    assertThat(after).isEqualTo(before);
  }
}
