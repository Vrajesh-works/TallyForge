package dev.tallyforge.ledger.fraud;

import dev.tallyforge.ledger.account.Account;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.transfer.TransferRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Simple, explainable rules that put a suspicious transfer on hold instead of settling it. The
 * account-draining rule mirrors the fraud pattern labelled in the PaySim dataset, where a
 * compromised account is emptied by a TRANSFER followed by a CASH_OUT.
 */
@Component
public class FraudRules {

  public static final String LARGE_AMOUNT = "LARGE_AMOUNT";
  public static final String DRAINS_ACCOUNT = "DRAINS_ACCOUNT";
  public static final String VELOCITY = "VELOCITY";

  private final FraudProperties props;
  private final TransferRepository transfers;

  public FraudRules(FraudProperties props, TransferRepository transfers) {
    this.props = props;
    this.transfers = transfers;
  }

  /** Reasons to hold a transfer from {@code debit}; empty means it may settle immediately. */
  public List<String> screenTransfer(Account debit, long amount) {
    List<String> reasons = new ArrayList<>();
    if (!props.enabled()) {
      return reasons;
    }
    if (amount >= props.largeAmount()) {
      reasons.add(LARGE_AMOUNT);
    }
    if (debit.getFlags() == AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS
        && amount >= props.drainMinAmount()
        && amount == debit.available()) {
      reasons.add(DRAINS_ACCOUNT);
    }
    long recent =
        transfers.countByDebitAccountIdAndCreatedAtAfter(
            debit.getId(), Instant.now().minus(props.velocityWindow()));
    if (recent >= props.velocityMax()) {
      reasons.add(VELOCITY);
    }
    return reasons;
  }

  /** Card payments are screened on amount only; the clearing account is shared by design. */
  public List<String> screenPayment(long amount) {
    List<String> reasons = new ArrayList<>();
    if (props.enabled() && amount >= props.largeAmount()) {
      reasons.add(LARGE_AMOUNT);
    }
    return reasons;
  }
}
