package dev.tallyforge.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.account.AccountDtos.AccountResponse;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.common.ApiError;
import dev.tallyforge.ledger.payment.PaymentDtos.CaptureRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.CreatePaymentRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.PaymentRefundRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.PaymentResponse;
import dev.tallyforge.ledger.payment.gateway.MockCardProcessor;
import dev.tallyforge.ledger.support.LedgerApi;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentIT extends IntegrationTest {

  static final String VISA = "4242424242424242";

  LedgerApi api;
  AccountResponse merchant;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
    merchant = api.account("merchant", AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
  }

  ResponseEntity<PaymentResponse> pay(String card, long amount, boolean capture, String key) {
    HttpHeaders headers = new HttpHeaders();
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    var body = new CreatePaymentRequest(merchant.id(), amount, "USD", card, capture, "order-1");
    return http.exchange(
        "/api/v1/payments",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        PaymentResponse.class);
  }

  @Test
  void authorizeCapturePartialAndFullRefund() {
    var auth = pay(VISA, 10_000, false, null);
    assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    PaymentResponse p = auth.getBody();
    assertThat(p.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(p.cardLast4()).isEqualTo("4242");
    assertThat(api.get(merchant.id()).creditsPending()).isEqualTo(10_000);

    var captured =
        api.post(
                "/api/v1/payments/" + p.id() + "/capture",
                new CaptureRequest(8_000L),
                PaymentResponse.class)
            .getBody();
    assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
    var m = api.get(merchant.id());
    assertThat(m.creditsPending()).isZero();
    assertThat(m.creditsPosted()).isEqualTo(8_000);

    var partial =
        api.post(
                "/api/v1/payments/" + p.id() + "/refunds",
                new PaymentRefundRequest(3_000),
                PaymentResponse.class)
            .getBody();
    assertThat(partial.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

    var tooMuch =
        api.post(
            "/api/v1/payments/" + p.id() + "/refunds",
            new PaymentRefundRequest(5_001),
            ApiError.class);
    assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    var full =
        api.post(
                "/api/v1/payments/" + p.id() + "/refunds",
                new PaymentRefundRequest(5_000),
                PaymentResponse.class)
            .getBody();
    assertThat(full.status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(full.refundedAmount()).isEqualTo(8_000);
    assertThat(api.get(merchant.id()).available()).isZero();
    assertThat(full.events())
        .extracting(PaymentDtos.PaymentEventResponse::to)
        .containsExactly(
            PaymentStatus.CREATED,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.CAPTURED,
            PaymentStatus.PARTIALLY_REFUNDED,
            PaymentStatus.REFUNDED);
  }

  @Test
  void declinedCardFailsWithoutTouchingTheLedger() {
    var res = pay(MockCardProcessor.DECLINE, 5_000, true, null).getBody();
    assertThat(res.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(res.failureReason()).isEqualTo("card_declined");
    assertThat(res.holdTransferId()).isNull();
    var m = api.get(merchant.id());
    assertThat(m.creditsPending() + m.creditsPosted()).isZero();
  }

  @Test
  void transientGatewayErrorsAreRetriedThenGiveUp() {
    var flaky = pay(MockCardProcessor.FLAKY_ONCE, 1_000, true, null).getBody();
    assertThat(flaky.status()).isEqualTo(PaymentStatus.CAPTURED);

    var down = pay(MockCardProcessor.ALWAYS_UNAVAILABLE, 1_000, true, null).getBody();
    assertThat(down.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(down.failureReason()).isEqualTo("gateway_unavailable");
  }

  @Test
  void voidReleasesTheHoldAndBlocksCapture() {
    var p = pay(VISA, 2_000, false, null).getBody();
    var voided =
        api.post("/api/v1/payments/" + p.id() + "/void", null, PaymentResponse.class).getBody();
    assertThat(voided.status()).isEqualTo(PaymentStatus.VOIDED);
    assertThat(api.get(merchant.id()).creditsPending()).isZero();

    var capture = api.post("/api/v1/payments/" + p.id() + "/capture", null, ApiError.class);
    assertThat(capture.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(capture.getBody().code()).isEqualTo("invalid_transition");
  }

  @Test
  void retriedCreateWithSameKeyChargesOnce() {
    String key = UUID.randomUUID().toString();
    var first = pay(VISA, 4_000, true, key).getBody();
    var retry = pay(VISA, 4_000, true, key);
    assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(retry.getBody().id()).isEqualTo(first.id());
    assertThat(api.get(merchant.id()).creditsPosted()).isEqualTo(4_000);
  }
}
