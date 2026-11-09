package dev.tallyforge.ledger.payment;

import static dev.tallyforge.ledger.payment.PaymentStatus.AUTHORIZED;
import static dev.tallyforge.ledger.payment.PaymentStatus.CAPTURED;
import static dev.tallyforge.ledger.payment.PaymentStatus.CREATED;
import static dev.tallyforge.ledger.payment.PaymentStatus.FAILED;
import static dev.tallyforge.ledger.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static dev.tallyforge.ledger.payment.PaymentStatus.REFUNDED;
import static dev.tallyforge.ledger.payment.PaymentStatus.REQUIRES_REVIEW;
import static dev.tallyforge.ledger.payment.PaymentStatus.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tallyforge.ledger.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStatusTest {

  @ParameterizedTest
  @CsvSource({
    "CREATED, AUTHORIZED",
    "CREATED, REQUIRES_REVIEW",
    "CREATED, FAILED",
    "REQUIRES_REVIEW, AUTHORIZED",
    "REQUIRES_REVIEW, VOIDED",
    "AUTHORIZED, CAPTURED",
    "AUTHORIZED, VOIDED",
    "CAPTURED, PARTIALLY_REFUNDED",
    "CAPTURED, REFUNDED",
    "PARTIALLY_REFUNDED, PARTIALLY_REFUNDED",
    "PARTIALLY_REFUNDED, REFUNDED"
  })
  void allowedTransitions(PaymentStatus from, PaymentStatus to) {
    assertThat(from.canTransitionTo(to)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "CREATED, CAPTURED",
    "REQUIRES_REVIEW, CAPTURED",
    "AUTHORIZED, REFUNDED",
    "CAPTURED, VOIDED",
    "VOIDED, CAPTURED",
    "FAILED, AUTHORIZED",
    "REFUNDED, PARTIALLY_REFUNDED"
  })
  void forbiddenTransitions(PaymentStatus from, PaymentStatus to) {
    assertThat(from.canTransitionTo(to)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"REFUNDED", "VOIDED", "FAILED"})
  void terminalStatesHaveNoExit(PaymentStatus status) {
    assertThat(status.isTerminal()).isTrue();
    for (PaymentStatus next : PaymentStatus.values()) {
      assertThat(status.canTransitionTo(next)).isFalse();
    }
  }

  @Test
  void paymentRejectsIllegalTransitionWith409() {
    Payment p = new Payment(UUID.randomUUID(), UUID.randomUUID(), 100, "USD", "4242", "mock", null);
    assertThat(p.transitionTo(AUTHORIZED)).isEqualTo(CREATED);
    p.transitionTo(VOIDED);
    assertThatThrownBy(() -> p.transitionTo(CAPTURED))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("cannot go from VOIDED to CAPTURED");
    assertThat(REQUIRES_REVIEW.isTerminal()).isFalse();
    assertThat(FAILED.isTerminal()).isTrue();
    assertThat(PARTIALLY_REFUNDED.canTransitionTo(REFUNDED)).isTrue();
  }
}
