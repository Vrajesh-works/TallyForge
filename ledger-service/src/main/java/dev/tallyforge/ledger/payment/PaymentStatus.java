package dev.tallyforge.ledger.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle. Allowed transitions are an explicit table (in the spirit of Kill Bill's
 * payment state machine), so an illegal move such as capturing a voided payment is rejected before
 * any gateway or ledger call happens.
 */
public enum PaymentStatus {
  CREATED,
  REQUIRES_REVIEW,
  AUTHORIZED,
  CAPTURED,
  PARTIALLY_REFUNDED,
  REFUNDED,
  VOIDED,
  FAILED;

  private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS =
      Map.of(
          CREATED, EnumSet.of(AUTHORIZED, REQUIRES_REVIEW, FAILED),
          REQUIRES_REVIEW, EnumSet.of(AUTHORIZED, VOIDED),
          AUTHORIZED, EnumSet.of(CAPTURED, VOIDED),
          CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
          PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
          REFUNDED, EnumSet.noneOf(PaymentStatus.class),
          VOIDED, EnumSet.noneOf(PaymentStatus.class),
          FAILED, EnumSet.noneOf(PaymentStatus.class));

  public boolean canTransitionTo(PaymentStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }
}
