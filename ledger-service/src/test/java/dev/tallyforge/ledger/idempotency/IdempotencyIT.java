package dev.tallyforge.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.common.ApiError;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;
  LedgerApi api;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
  }

  @Test
  void retryWithSameKeyChargesOnceAndReplaysTheResponse() {
    var alice = api.fundedWallet("alice", 10_000);
    var shop = api.fundedWallet("shop", 0);
    String key = UUID.randomUUID().toString();

    var first = api.transfer(alice.id(), shop.id(), 1_500, false, key);
    var retry = api.transfer(alice.id(), shop.id(), 1_500, false, key);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(retry.getBody().id()).isEqualTo(first.getBody().id());
    assertThat(api.get(alice.id()).available()).isEqualTo(8_500);
    assertThat(transfersFrom(alice.id())).isEqualTo(1);
  }

  @Test
  void sameKeyWithDifferentBodyIsRejected() {
    var alice = api.fundedWallet("alice", 10_000);
    var shop = api.fundedWallet("shop", 0);
    String key = UUID.randomUUID().toString();
    api.transfer(alice.id(), shop.id(), 1_500, false, key);

    HttpHeaders headers = new HttpHeaders();
    headers.set(IdempotencyService.HEADER, key);
    var res =
        http.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            new HttpEntity<>(
                new CreateTransferRequest(alice.id(), shop.id(), 9_999, false, null), headers),
            ApiError.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(res.getBody().code()).isEqualTo("idempotency_key_reused");
    assertThat(api.get(alice.id()).available()).isEqualTo(8_500);
  }

  @Test
  void concurrentRetriesWithSameKeyMoveMoneyExactlyOnce() throws Exception {
    var alice = api.fundedWallet("alice", 10_000);
    var shop = api.fundedWallet("shop", 0);
    String key = UUID.randomUUID().toString();
    int clients = 16;
    ExecutorService pool = Executors.newFixedThreadPool(clients);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ResponseEntity<TransferResponse>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < clients; i++) {
        Callable<ResponseEntity<TransferResponse>> call =
            () -> {
              start.await();
              return api.transfer(alice.id(), shop.id(), 700, false, key);
            };
        futures.add(pool.submit(call));
      }
      start.countDown();
      List<UUID> ids = new ArrayList<>();
      for (var f : futures) {
        var res = f.get();
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ids.add(res.getBody().id());
      }
      assertThat(ids).containsOnly(ids.getFirst());
    } finally {
      pool.shutdownNow();
    }
    assertThat(transfersFrom(alice.id())).isEqualTo(1);
    assertThat(api.get(alice.id()).available()).isEqualTo(9_300);
    assertThat(api.get(shop.id()).available()).isEqualTo(700);
  }

  private int transfersFrom(UUID accountId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM transfers WHERE debit_account_id = ?", Integer.class, accountId);
  }
}
