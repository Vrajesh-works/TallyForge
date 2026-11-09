package dev.tallyforge.ledger.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tallyforge.ledger.account.Account;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.account.EntryPhase;
import dev.tallyforge.ledger.account.EntrySide;
import dev.tallyforge.ledger.transfer.TransferRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FraudRulesTest {

  private final TransferRepository transfers = mock(TransferRepository.class);
  private final FraudProperties props =
      new FraudProperties(true, 1_000_000, 100_000, 5, Duration.ofMinutes(1));
  private final FraudRules rules = new FraudRules(props, transfers);
  private Account wallet;

  @BeforeEach
  void setUp() {
    wallet =
        new Account(
            UUID.randomUUID(), "w", "Wallet", "USD", AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
    wallet.apply(EntrySide.CREDIT, EntryPhase.POSTED, 250_000);
  }

  @Test
  void ordinaryTransferPasses() {
    assertThat(rules.screenTransfer(wallet, 10_000)).isEmpty();
  }

  @Test
  void largeAmountIsHeld() {
    Account bank = new Account(UUID.randomUUID(), "b", "Bank", "USD", AccountFlags.NONE);
    assertThat(rules.screenTransfer(bank, 1_000_000)).containsExactly(FraudRules.LARGE_AMOUNT);
    assertThat(rules.screenTransfer(bank, 999_999)).isEmpty();
  }

  @Test
  void emptyingTheWholeBalanceIsHeld() {
    assertThat(rules.screenTransfer(wallet, 250_000)).containsExactly(FraudRules.DRAINS_ACCOUNT);
    assertThat(rules.screenTransfer(wallet, 249_999)).isEmpty();
  }

  @Test
  void smallDrainsAreIgnored() {
    Account small =
        new Account(
            UUID.randomUUID(), "s", "Small", "USD", AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
    small.apply(EntrySide.CREDIT, EntryPhase.POSTED, 5_000);
    assertThat(rules.screenTransfer(small, 5_000)).isEmpty();
  }

  @Test
  void highVelocityIsHeld() {
    when(transfers.countByDebitAccountIdAndCreatedAtAfter(eq(wallet.getId()), any()))
        .thenReturn(5L);
    assertThat(rules.screenTransfer(wallet, 100)).containsExactly(FraudRules.VELOCITY);
  }

  @Test
  void disabledRulesNeverHold() {
    FraudRules off =
        new FraudRules(new FraudProperties(false, 1, 1, 1, Duration.ofMinutes(1)), transfers);
    assertThat(off.screenTransfer(wallet, 250_000)).isEmpty();
    assertThat(off.screenPayment(10_000_000)).isEmpty();
  }

  @Test
  void paymentsAreScreenedOnAmount() {
    assertThat(rules.screenPayment(2_000_000)).containsExactly(FraudRules.LARGE_AMOUNT);
    assertThat(rules.screenPayment(500)).isEmpty();
  }
}
