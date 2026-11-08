package dev.tallyforge.ledger.payment;

import dev.tallyforge.ledger.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

  @Id private UUID id;

  @Column(nullable = false)
  private UUID merchantAccountId;

  private long amount;
  private long capturedAmount;
  private long refundedAmount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentStatus status;

  private String cardLast4;

  @Column(nullable = false)
  private String gateway;

  private String gatewayReference;
  private UUID holdTransferId;
  private String failureReason;
  private String reviewReason;
  private String reference;

  @Version private Long version;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Payment() {}

  Payment(
      UUID id,
      UUID merchantAccountId,
      long amount,
      String currency,
      String cardLast4,
      String gateway,
      String reference) {
    this.id = id;
    this.merchantAccountId = merchantAccountId;
    this.amount = amount;
    this.currency = currency;
    this.cardLast4 = cardLast4;
    this.gateway = gateway;
    this.reference = reference;
    this.status = PaymentStatus.CREATED;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  /**
   * Move to {@code next} or throw 409 if the transition table forbids it. Returns the old state.
   */
  PaymentStatus transitionTo(PaymentStatus next) {
    if (!status.canTransitionTo(next)) {
      throw ApiException.conflict(
          "invalid_transition", "payment " + id + " cannot go from " + status + " to " + next);
    }
    PaymentStatus previous = status;
    status = next;
    updatedAt = Instant.now();
    return previous;
  }

  void requireStatus(PaymentStatus... allowed) {
    for (PaymentStatus s : allowed) {
      if (status == s) {
        return;
      }
    }
    throw ApiException.conflict(
        "invalid_transition", "payment " + id + " is " + status + " for this operation");
  }

  void authorized(String gatewayReference, UUID holdTransferId) {
    this.gatewayReference = gatewayReference;
    this.holdTransferId = holdTransferId;
  }

  void failed(String reason) {
    this.failureReason = reason;
  }

  void flagForReview(String reason) {
    this.reviewReason = reason;
  }

  void captured(long captured) {
    this.capturedAmount = captured;
  }

  void refunded(long refund) {
    this.refundedAmount = Math.addExact(refundedAmount, refund);
  }

  public UUID getId() {
    return id;
  }

  public UUID getMerchantAccountId() {
    return merchantAccountId;
  }

  public long getAmount() {
    return amount;
  }

  public long getCapturedAmount() {
    return capturedAmount;
  }

  public long getRefundedAmount() {
    return refundedAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public String getCardLast4() {
    return cardLast4;
  }

  public String getGateway() {
    return gateway;
  }

  public String getGatewayReference() {
    return gatewayReference;
  }

  public UUID getHoldTransferId() {
    return holdTransferId;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public String getReviewReason() {
    return reviewReason;
  }

  public String getReference() {
    return reference;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
