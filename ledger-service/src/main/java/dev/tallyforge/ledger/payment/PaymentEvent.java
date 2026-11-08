package dev.tallyforge.ledger.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Audit trail: one row per state transition. */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private UUID paymentId;

  @Enumerated(EnumType.STRING)
  private PaymentStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentStatus toStatus;

  private String detail;

  @Column(nullable = false)
  private Instant createdAt;

  protected PaymentEvent() {}

  PaymentEvent(UUID paymentId, PaymentStatus from, PaymentStatus to, String detail) {
    this.paymentId = paymentId;
    this.fromStatus = from;
    this.toStatus = to;
    this.detail = detail;
    this.createdAt = Instant.now();
  }

  public PaymentStatus getFromStatus() {
    return fromStatus;
  }

  public PaymentStatus getToStatus() {
    return toStatus;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
