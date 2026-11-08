package dev.tallyforge.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.account.AccountDtos.AccountResponse;
import dev.tallyforge.ledger.support.LedgerApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fires transfers from many threads at once and checks the invariants that matter for money:
 * nothing is created or destroyed, no constrained account is overdrawn, and the journal balances.
 */
class ConcurrentTransfersIT extends IntegrationTest {

  private static final int THREADS = 16;

  @Autowired JdbcTemplate jdbc;
  LedgerApi api;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
  }

  @Test
  void parallelTransfersOnOneHotAccountNeverOverdraw() throws Exception {
    var payer = api.fundedWallet("hot", 10_000);
    var payee = api.fundedWallet("payee", 0);
    int attempts = 200; // 200 x 100 = 20_000 requested against a 10_000 balance

    Map<Integer, AtomicInteger> statuses =
        fire(attempts, i -> api.transfer(payer.id(), payee.id(), 100, false, null).getStatusCode());

    assertThat(statuses.keySet()).containsOnly(201, 422);
    assertThat(statuses.get(201)).hasValue(100);
    assertThat(statuses.get(422)).hasValue(100);
    AccountResponse after = api.get(payer.id());
    assertThat(after.available()).isZero();
    assertThat(after.debitsPosted()).isEqualTo(after.creditsPosted());
    assertThat(api.get(payee.id()).creditsPosted()).isEqualTo(10_000);
  }

  @Test
  void randomParallelTransfersConserveMoney() throws Exception {
    int walletCount = 8;
    long initial = 10_000;
    List<UUID> wallets = new ArrayList<>();
    for (int i = 0; i < walletCount; i++) {
      wallets.add(api.fundedWallet("w" + i, initial).id());
    }
    int attempts = 600;

    Map<Integer, AtomicInteger> statuses =
        fire(
            attempts,
            i -> {
              var rnd = ThreadLocalRandom.current();
              int from = rnd.nextInt(walletCount);
              int to = (from + 1 + rnd.nextInt(walletCount - 1)) % walletCount;
              long amount = 1 + rnd.nextInt(3_000);
              return api.transfer(
                      wallets.get(from), wallets.get(to), amount, rnd.nextInt(5) == 0, null)
                  .getStatusCode();
            });

    assertThat(statuses.keySet()).isSubsetOf(201, 422);
    assertThat(statuses.get(201).get()).isGreaterThan(attempts / 3);

    long totalNet = 0;
    for (UUID id : wallets) {
      AccountResponse a = api.get(id);
      assertThat(a.debitsPending() + a.debitsPosted())
          .as("wallet %s must not be overdrawn", a.code())
          .isLessThanOrEqualTo(a.creditsPosted());
      totalNet += a.creditsPosted() - a.debitsPosted();
      // Balance columns must agree with the journal.
      Long journalDebits =
          jdbc.queryForObject(
              "SELECT COALESCE(SUM(amount), 0) FROM journal_entries"
                  + " WHERE account_id = ? AND side = 'DEBIT' AND phase = 'POSTED'",
              Long.class,
              id);
      assertThat(journalDebits).isEqualTo(a.debitsPosted());
    }
    // Pending holds have not moved money yet, so the posted total is unchanged.
    assertThat(totalNet).isEqualTo(walletCount * initial);

    Map<String, Object> global =
        jdbc.queryForMap(
            "SELECT SUM(amount) FILTER (WHERE side = 'DEBIT') AS debits,"
                + " SUM(amount) FILTER (WHERE side = 'CREDIT') AS credits FROM journal_entries");
    assertThat(global.get("debits")).isEqualTo(global.get("credits"));
  }

  interface Call {
    HttpStatusCode run(int i) throws Exception;
  }

  private Map<Integer, AtomicInteger> fire(int attempts, Call call) throws Exception {
    Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < attempts; i++) {
        int n = i;
        futures.add(
            pool.submit(() -> {
              start.await();
              int code = call.run(n).value();
              statuses.computeIfAbsent(code, c -> new AtomicInteger()).incrementAndGet();
              return null;
            }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    return statuses;
  }
}
