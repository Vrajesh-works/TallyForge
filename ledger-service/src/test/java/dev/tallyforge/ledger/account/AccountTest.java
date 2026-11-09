package dev.tallyforge.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tallyforge.ledger.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static Account account(AccountFlags flags) {
    return new Account(UUID.randomUUID(), "acc", "Account", "USD", flags);
  }

  @Test
  void pendingDebitsCountAgainstAvailableFunds() {
    Account wallet = account(AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
    wallet.apply(EntrySide.CREDIT, EntryPhase.POSTED, 1_000);
    wallet.apply(EntrySide.DEBIT, EntryPhase.PENDING, 600);
    wallet.checkConstraints();
    assertThat(wallet.available()).isEqualTo(400);

    wallet.apply(EntrySide.DEBIT, EntryPhase.POSTED, 401);
    assertThatThrownBy(wallet::checkConstraints)
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("insufficient funds");
  }

  @Test
  void creditLimitFlagIsEnforced() {
    Account limited = account(AccountFlags.CREDITS_MUST_NOT_EXCEED_DEBITS);
    limited.apply(EntrySide.DEBIT, EntryPhase.POSTED, 50);
    limited.apply(EntrySide.CREDIT, EntryPhase.PENDING, 50);
    limited.checkConstraints();
    limited.apply(EntrySide.CREDIT, EntryPhase.POSTED, 1);
    assertThatThrownBy(limited::checkConstraints).isInstanceOf(ApiException.class);
  }

  @Test
  void unconstrainedAccountMayGoNegative() {
    Account settlement = account(AccountFlags.NONE);
    settlement.apply(EntrySide.DEBIT, EntryPhase.POSTED, 1_000_000);
    settlement.checkConstraints();
    assertThat(settlement.available()).isEqualTo(-1_000_000);
  }

  @Test
  void overflowIsDetectedInsteadOfWrapping() {
    Account a = account(AccountFlags.NONE);
    a.apply(EntrySide.CREDIT, EntryPhase.POSTED, Long.MAX_VALUE);
    assertThatThrownBy(() -> a.apply(EntrySide.CREDIT, EntryPhase.POSTED, 1))
        .isInstanceOf(ArithmeticException.class);
  }
}
