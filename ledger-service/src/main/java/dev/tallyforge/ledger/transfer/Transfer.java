package dev.tallyforge.ledger.transfer;

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
@Table(name = "transfers")
public class Transfer {

  @Id private UUID id;

  @Column(nullable = false)
  private UUID debitAccountId;

  @Column(nullable = false)
  private UUID creditAccountId;

  private long amount;
  private long postedAmount;
  private long refundedAmount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransferKind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransferStatus status;

  private UUID refundOf;
  private String holdReason;
  private String reference;

  @Version private Long version;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Transfer() {}

  Transfer(
      UUID id,
      UUID debitAccountId,
      UUID creditAccountId,
      long amount,
      String currency,
      TransferKind kind,
      TransferStatus status,
      UUID refundOf,
      String holdReason,
      String reference) {
    this.id = id;
    this.debitAccountId = debitAccountId;
    this.creditAccountId = creditAccountId;
    this.amount = amount;
    this.currency = currency;
    this.kind = kind;
    this.status = status;
    this.refundOf = refundOf;
    this.holdReason = holdReason;
    this.reference = reference;
    this.postedAmount = status == TransferStatus.POSTED ? amount : 0;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  void markPosted(long posted) {
    this.status = TransferStatus.POSTED;
    this.postedAmount = posted;
    this.updatedAt = Instant.now();
  }

  void markVoided() {
    this.status = TransferStatus.VOIDED;
    this.updatedAt = Instant.now();
  }

  void addRefunded(long refunded) {
    this.refundedAmount = Math.addExact(refundedAmount, refunded);
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getDebitAccountId() {
    return debitAccountId;
  }

  public UUID getCreditAccountId() {
    return creditAccountId;
  }

  public long getAmount() {
    return amount;
  }

  public long getPostedAmount() {
    return postedAmount;
  }

  public long getRefundedAmount() {
    return refundedAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public TransferKind getKind() {
    return kind;
  }

  public TransferStatus getStatus() {
    return status;
  }

  public UUID getRefundOf() {
    return refundOf;
  }

  public String getHoldReason() {
    return holdReason;
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
