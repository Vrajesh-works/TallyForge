package dev.tallyforge.ledger.transfer;

import dev.tallyforge.ledger.account.EntryPhase;
import dev.tallyforge.ledger.account.EntrySide;
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

/** Immutable double-entry line. Every transfer writes them in balanced debit/credit pairs. */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID transferId;

  @Column(nullable = false, updatable = false)
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private EntrySide side;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private EntryPhase phase;

  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  protected JournalEntry() {}

  JournalEntry(
      UUID transferId,
      UUID accountId,
      EntrySide side,
      EntryPhase phase,
      long amount,
      String currency) {
    this.transferId = transferId;
    this.accountId = accountId;
    this.side = side;
    this.phase = phase;
    this.amount = amount;
    this.currency = currency;
    this.createdAt = Instant.now();
  }

  public UUID getTransferId() {
    return transferId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public EntrySide getSide() {
    return side;
  }

  public EntryPhase getPhase() {
    return phase;
  }

  public long getAmount() {
    return amount;
  }
}
