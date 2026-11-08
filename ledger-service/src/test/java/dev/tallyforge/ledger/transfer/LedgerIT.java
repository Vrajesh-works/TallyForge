package dev.tallyforge.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.common.ApiError;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.transfer.TransferDtos.PostTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.RefundRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class LedgerIT extends IntegrationTest {

  @Autowired JdbcTemplate jdbc;
  LedgerApi api;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
  }

  @Test
  void postedTransferMovesMoneyAndWritesBalancedEntries() {
    var alice = api.fundedWallet("alice", 10_000);
    var bob = api.fundedWallet("bob", 0);

    var res = api.transfer(alice.id(), bob.id(), 2_500, false, null);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getBody().status()).isEqualTo(TransferStatus.POSTED);
    assertThat(api.get(alice.id()).available()).isEqualTo(7_500);
    assertThat(api.get(bob.id()).available()).isEqualTo(2_500);
    Map<String, Object> sums =
        jdbc.queryForMap(
            "SELECT SUM(amount) FILTER (WHERE side = 'DEBIT') AS d,"
                + " SUM(amount) FILTER (WHERE side = 'CREDIT') AS c"
                + " FROM journal_entries WHERE transfer_id = ?",
            res.getBody().id());
    assertThat(sums.get("d")).isEqualTo(sums.get("c"));
  }

  @Test
  void overdraftIsRejectedAndNothingChanges() {
    var alice = api.fundedWallet("alice", 1_000);
    var bob = api.fundedWallet("bob", 0);

    var res =
        http.postForEntity(
            "/api/v1/transfers",
            new TransferDtos.CreateTransferRequest(alice.id(), bob.id(), 1_001, false, null),
            ApiError.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(res.getBody().code()).isEqualTo("exceeds_credits");
    assertThat(api.get(alice.id()).available()).isEqualTo(1_000);
    assertThat(api.get(bob.id()).creditsPosted()).isZero();
  }

  @Test
  void pendingHoldReservesFundsAndPartialPostReleasesTheRest() {
    var alice = api.fundedWallet("alice", 10_000);
    var merchant = api.account("merchant", AccountFlags.NONE);

    var hold = api.transfer(alice.id(), merchant.id(), 6_000, true, null).getBody();
    assertThat(hold.status()).isEqualTo(TransferStatus.PENDING);
    assertThat(api.get(alice.id()).available()).isEqualTo(4_000);

    // A second hold larger than what is left must fail: the first hold really reserved funds.
    var tooMuch = api.transfer(alice.id(), merchant.id(), 4_001, true, null);
    assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    var posted =
        api.post(
            "/api/v1/transfers/" + hold.id() + "/post",
            new PostTransferRequest(5_000L),
            TransferResponse.class);
    assertThat(posted.getBody().status()).isEqualTo(TransferStatus.POSTED);
    assertThat(posted.getBody().postedAmount()).isEqualTo(5_000);

    var aliceAfter = api.get(alice.id());
    assertThat(aliceAfter.debitsPending()).isZero();
    assertThat(aliceAfter.available()).isEqualTo(5_000);
    assertThat(api.get(merchant.id()).creditsPosted()).isEqualTo(5_000);
  }

  @Test
  void voidReleasesHoldAndTerminalStatesCannotChange() {
    var alice = api.fundedWallet("alice", 3_000);
    var merchant = api.account("merchant", AccountFlags.NONE);
    var hold = api.transfer(alice.id(), merchant.id(), 3_000, true, null).getBody();

    var voided = api.post("/api/v1/transfers/" + hold.id() + "/void", null, TransferResponse.class);
    assertThat(voided.getBody().status()).isEqualTo(TransferStatus.VOIDED);
    assertThat(api.get(alice.id()).available()).isEqualTo(3_000);

    var postAfterVoid = api.post("/api/v1/transfers/" + hold.id() + "/post", null, ApiError.class);
    assertThat(postAfterVoid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(postAfterVoid.getBody().code()).isEqualTo("not_pending");
  }

  @Test
  void refundsAreCappedAtThePostedAmount() {
    var alice = api.fundedWallet("alice", 5_000);
    var merchant = api.fundedWallet("merchant", 0);
    var payment = api.transfer(alice.id(), merchant.id(), 4_000, false, null).getBody();

    var first =
        api.post(
            "/api/v1/transfers/" + payment.id() + "/refunds",
            new RefundRequest(3_000, "partial"),
            TransferResponse.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody().kind()).isEqualTo(TransferKind.REFUND);
    assertThat(first.getBody().refundOf()).isEqualTo(payment.id());

    var tooMuch =
        api.post(
            "/api/v1/transfers/" + payment.id() + "/refunds",
            new RefundRequest(1_001, null),
            ApiError.class);
    assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tooMuch.getBody().code()).isEqualTo("refund_exceeds_posted");

    assertThat(api.get(alice.id()).available()).isEqualTo(4_000);
    assertThat(api.get(merchant.id()).available()).isEqualTo(1_000);
  }

  @Test
  void currencyMismatchAndUnknownAccountsAreRejected() {
    var usd = api.fundedWallet("usd", 100);
    var eur =
        http.postForEntity(
                "/api/v1/accounts",
                new dev.tallyforge.ledger.account.AccountDtos.CreateAccountRequest(
                    LedgerApi.unique("eur"), "EUR wallet", "EUR", AccountFlags.NONE),
                dev.tallyforge.ledger.account.AccountDtos.AccountResponse.class)
            .getBody();

    var mismatch =
        http.postForEntity(
            "/api/v1/transfers",
            new TransferDtos.CreateTransferRequest(usd.id(), eur.id(), 10, false, null),
            ApiError.class);
    assertThat(mismatch.getBody().code()).isEqualTo("currency_mismatch");

    var unknown =
        http.postForEntity(
            "/api/v1/transfers",
            new TransferDtos.CreateTransferRequest(
                usd.id(), java.util.UUID.randomUUID(), 10, false, null),
            ApiError.class);
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
