package dev.tallyforge.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.reconciliation.ReconciliationService.Mismatch;
import dev.tallyforge.ledger.reconciliation.ReconciliationService.RunResponse;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ReconciliationIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void cleanLedgerReconcilesAndInjectedDriftIsFlagged() {
    LedgerApi api = new LedgerApi(http);
    var alice = api.fundedWallet("alice", 9_000);
    var shop = api.fundedWallet("shop", 0);
    var hold = api.transfer(alice.id(), shop.id(), 4_000, true, null).getBody();
    api.post("/api/v1/transfers/" + hold.id() + "/post", null, TransferResponse.class);

    var clean = http.postForEntity("/api/v1/reconciliation/runs", null, RunResponse.class);
    assertThat(clean.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(clean.getBody().status()).isEqualTo("OK");
    assertThat(clean.getBody().accountsChecked()).isGreaterThanOrEqualTo(3);

    // Simulate a bug or manual edit that changes a balance without a journal entry.
    jdbc.update("UPDATE accounts SET credits_posted = credits_posted + 1 WHERE id = ?", shop.id());
    try {
      RunResponse drift =
          http.postForObject("/api/v1/reconciliation/runs", null, RunResponse.class);
      assertThat(drift.status()).isEqualTo("MISMATCH");
      assertThat(drift.mismatches())
          .contains(new Mismatch("account_balance", shop.code() + ":credits_posted", 4_000, 4_001));

      RunResponse stored =
          http.getForObject("/api/v1/reconciliation/runs/" + drift.id(), RunResponse.class);
      assertThat(stored.mismatches()).isEqualTo(drift.mismatches());
      assertThat(http.getForObject("/api/v1/reconciliation/runs", RunResponse[].class))
          .extracting(RunResponse::id)
          .contains(drift.id(), clean.getBody().id());
    } finally {
      jdbc.update(
          "UPDATE accounts SET credits_posted = credits_posted - 1 WHERE id = ?", shop.id());
    }
  }
}
