package dev.tallyforge.ledger.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.common.ApiError;
import dev.tallyforge.ledger.payment.PaymentDtos.CreatePaymentRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.PaymentResponse;
import dev.tallyforge.ledger.payment.PaymentStatus;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import dev.tallyforge.ledger.transfer.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Test profile thresholds: large amount 5,000,000 and drain minimum 50,000 (minor units). */
class FraudIT extends IntegrationTest {

  LedgerApi api;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
  }

  @Test
  void largeTransferIsHeldUntilAReviewerPostsIt() {
    var bank = api.account("bank", AccountFlags.NONE);
    var wallet = api.account("wallet", AccountFlags.NONE);

    var held = api.transfer(bank.id(), wallet.id(), 5_000_000, false, null).getBody();

    assertThat(held.status()).isEqualTo(TransferStatus.PENDING);
    assertThat(held.holdReason()).isEqualTo("LARGE_AMOUNT");
    assertThat(api.get(wallet.id()).creditsPosted()).isZero();

    var posted =
        api.post("/api/v1/transfers/" + held.id() + "/post", null, TransferResponse.class)
            .getBody();
    assertThat(posted.status()).isEqualTo(TransferStatus.POSTED);
    assertThat(api.get(wallet.id()).creditsPosted()).isEqualTo(5_000_000);
  }

  @Test
  void transferThatEmptiesAnAccountIsHeld() {
    var victim = api.fundedWallet("victim", 60_000);
    var mule = api.fundedWallet("mule", 0);

    var held = api.transfer(victim.id(), mule.id(), 60_000, false, null).getBody();

    assertThat(held.status()).isEqualTo(TransferStatus.PENDING);
    assertThat(held.holdReason()).isEqualTo("DRAINS_ACCOUNT");
    var voided =
        api.post("/api/v1/transfers/" + held.id() + "/void", null, TransferResponse.class)
            .getBody();
    assertThat(voided.status()).isEqualTo(TransferStatus.VOIDED);
    assertThat(api.get(victim.id()).available()).isEqualTo(60_000);
  }

  @Test
  void largePaymentNeedsApprovalBeforeCapture() {
    var merchant = api.account("merchant", AccountFlags.NONE);
    var req =
        new CreatePaymentRequest(
            merchant.id(), 6_000_000, "USD", "4242424242424242", true, "big-order");

    PaymentResponse p = http.postForObject("/api/v1/payments", req, PaymentResponse.class);
    assertThat(p.status()).isEqualTo(PaymentStatus.REQUIRES_REVIEW);
    assertThat(p.reviewReason()).isEqualTo("LARGE_AMOUNT");

    var blocked = api.post("/api/v1/payments/" + p.id() + "/capture", null, ApiError.class);
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    var approved =
        api.post("/api/v1/payments/" + p.id() + "/approve", null, PaymentResponse.class).getBody();
    assertThat(approved.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    var captured =
        api.post("/api/v1/payments/" + p.id() + "/capture", null, PaymentResponse.class).getBody();
    assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(api.get(merchant.id()).creditsPosted()).isEqualTo(6_000_000);
  }
}
