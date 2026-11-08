package dev.tallyforge.ledger.account;

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
@Table(name = "accounts")
public class Account {

  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountFlags flags;

  private long debitsPending;
  private long debitsPosted;
  private long creditsPending;
  private long creditsPosted;

  @Version private long version;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  protected Account() {}

  public Account(UUID id, String code, String name, String currency, AccountFlags flags) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.currency = currency;
    this.flags = flags;
    this.createdAt = Instant.now();
  }

  /**
   * Apply a signed delta to one balance column. Only the ledger service calls this, inside the same
   * transaction that writes the matching journal entry.
   */
  public void apply(EntrySide side, EntryPhase phase, long delta) {
    if (side == EntrySide.DEBIT && phase == EntryPhase.PENDING) {
      debitsPending = Math.addExact(debitsPending, delta);
    } else if (side == EntrySide.DEBIT) {
      debitsPosted = Math.addExact(debitsPosted, delta);
    } else if (phase == EntryPhase.PENDING) {
      creditsPending = Math.addExact(creditsPending, delta);
    } else {
      creditsPosted = Math.addExact(creditsPosted, delta);
    }
  }

  /** Throws if the current balances violate this account's flags. */
  public void checkConstraints() {
    if (flags == AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS
        && Math.addExact(debitsPending, debitsPosted) > creditsPosted) {
      throw ApiException.unprocessable(
          "exceeds_credits", "Account " + code + " has insufficient funds for this debit");
    }
    if (flags == AccountFlags.CREDITS_MUST_NOT_EXCEED_DEBITS
        && Math.addExact(creditsPending, creditsPosted) > debitsPosted) {
      throw ApiException.unprocessable(
          "exceeds_debits", "Account " + code + " would exceed its debit limit");
    }
  }

  /** Funds available to debit: posted credits minus everything already debited or held. */
  public long available() {
    return creditsPosted - debitsPosted - debitsPending;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getCurrency() {
    return currency;
  }

  public AccountFlags getFlags() {
    return flags;
  }

  public long getDebitsPending() {
    return debitsPending;
  }

  public long getDebitsPosted() {
    return debitsPosted;
  }

  public long getCreditsPending() {
    return creditsPending;
  }

  public long getCreditsPosted() {
    return creditsPosted;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
