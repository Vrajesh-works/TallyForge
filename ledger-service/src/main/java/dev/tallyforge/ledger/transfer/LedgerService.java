package dev.tallyforge.ledger.transfer;

import dev.tallyforge.ledger.account.Account;
import dev.tallyforge.ledger.account.AccountRepository;
import dev.tallyforge.ledger.account.EntryPhase;
import dev.tallyforge.ledger.account.EntrySide;
import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.fraud.FraudRules;
import dev.tallyforge.ledger.outbox.OutboxService;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code path that changes balances. Every method must run inside a transaction opened by
 * the caller (see {@link dev.tallyforge.ledger.common.RetryingTransactions}), so balance updates,
 * journal entries and outbox events commit or roll back together.
 *
 * <p>Invariant: each call writes journal entries in debit/credit pairs of equal amount, so the
 * ledger-wide sum of debits always equals the sum of credits, for both pending and posted phases.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerService {

  private final AccountRepository accounts;
  private final TransferRepository transfers;
  private final JournalEntryRepository entries;
  private final OutboxService outbox;
  private final FraudRules fraud;

  public LedgerService(
      AccountRepository accounts,
      TransferRepository transfers,
      JournalEntryRepository entries,
      OutboxService outbox,
      FraudRules fraud) {
    this.accounts = accounts;
    this.transfers = transfers;
    this.entries = entries;
    this.outbox = outbox;
    this.fraud = fraud;
  }

  /**
   * A request to move money from the debit account to the credit account. {@code screen} runs the
   * fraud rules; a flagged transfer is created PENDING with a hold reason for manual review.
   */
  public record TransferCommand(
      UUID debitAccountId,
      UUID creditAccountId,
      long amount,
      boolean pending,
      String reference,
      boolean screen) {}

  public TransferResponse create(TransferCommand cmd) {
    if (cmd.amount() <= 0) {
      throw ApiException.badRequest("invalid_amount", "amount must be positive");
    }
    if (cmd.debitAccountId().equals(cmd.creditAccountId())) {
      throw ApiException.unprocessable(
          "same_account", "debit and credit accounts must be different");
    }
    Account debit = load(cmd.debitAccountId());
    Account credit = load(cmd.creditAccountId());
    if (!debit.getCurrency().equals(credit.getCurrency())) {
      throw ApiException.unprocessable(
          "currency_mismatch", "accounts have different currencies, FX is not supported");
    }
    String holdReason = null;
    if (cmd.screen()) {
      List<String> reasons = fraud.screenTransfer(debit, cmd.amount());
      holdReason = reasons.isEmpty() ? null : String.join(",", reasons);
    }
    boolean pending = cmd.pending() || holdReason != null;
    Transfer transfer =
        new Transfer(
            UUID.randomUUID(),
            debit.getId(),
            credit.getId(),
            cmd.amount(),
            debit.getCurrency(),
            TransferKind.TRANSFER,
            pending ? TransferStatus.PENDING : TransferStatus.POSTED,
            null,
            holdReason,
            cmd.reference());
    transfers.save(transfer);
    move(transfer, debit, credit, pending ? EntryPhase.PENDING : EntryPhase.POSTED, cmd.amount());
    checkConstraints(debit, credit);
    return publish(transfer, pending ? "transfer.pending" : "transfer.posted");
  }

  /** Post a pending transfer. {@code amount} null means the full held amount. */
  public TransferResponse post(UUID transferId, Long amount) {
    Transfer transfer = loadTransfer(transferId);
    requirePending(transfer);
    long toPost = amount == null ? transfer.getAmount() : amount;
    if (toPost <= 0 || toPost > transfer.getAmount()) {
      throw ApiException.unprocessable(
          "invalid_post_amount", "post amount must be between 1 and the held amount");
    }
    Account debit = load(transfer.getDebitAccountId());
    Account credit = load(transfer.getCreditAccountId());
    move(transfer, debit, credit, EntryPhase.PENDING, -transfer.getAmount());
    move(transfer, debit, credit, EntryPhase.POSTED, toPost);
    transfer.markPosted(toPost);
    checkConstraints(debit, credit);
    return publish(transfer, "transfer.posted");
  }

  /** Release a pending transfer without moving any money. */
  public TransferResponse voidTransfer(UUID transferId) {
    Transfer transfer = loadTransfer(transferId);
    requirePending(transfer);
    Account debit = load(transfer.getDebitAccountId());
    Account credit = load(transfer.getCreditAccountId());
    move(transfer, debit, credit, EntryPhase.PENDING, -transfer.getAmount());
    transfer.markVoided();
    return publish(transfer, "transfer.voided");
  }

  /**
   * Reverse part or all of a posted transfer with a new REFUND transfer in the opposite direction.
   * Total refunds can never exceed the posted amount.
   */
  public TransferResponse refund(UUID transferId, long amount, String reference) {
    Transfer original = loadTransfer(transferId);
    if (original.getKind() != TransferKind.TRANSFER) {
      throw ApiException.unprocessable("not_refundable", "a refund cannot itself be refunded");
    }
    if (original.getStatus() != TransferStatus.POSTED) {
      throw ApiException.conflict(
          "not_posted", "only posted transfers can be refunded; void a pending one instead");
    }
    long refundable = original.getPostedAmount() - original.getRefundedAmount();
    if (amount <= 0 || amount > refundable) {
      throw ApiException.unprocessable(
          "refund_exceeds_posted",
          "refund amount must be between 1 and the refundable amount " + refundable);
    }
    Account debit = load(original.getCreditAccountId());
    Account credit = load(original.getDebitAccountId());
    Transfer refund =
        new Transfer(
            UUID.randomUUID(),
            debit.getId(),
            credit.getId(),
            amount,
            original.getCurrency(),
            TransferKind.REFUND,
            TransferStatus.POSTED,
            original.getId(),
            null,
            reference);
    transfers.save(refund);
    original.addRefunded(amount);
    move(refund, debit, credit, EntryPhase.POSTED, amount);
    checkConstraints(debit, credit);
    return publish(refund, "transfer.refunded");
  }

  /** Record the event in the outbox; it commits or rolls back with the ledger rows. */
  private TransferResponse publish(Transfer transfer, String eventType) {
    TransferResponse response = TransferResponse.from(transfer);
    outbox.append("transfer", transfer.getId(), eventType, response);
    return response;
  }

  private void move(Transfer t, Account debit, Account credit, EntryPhase phase, long delta) {
    debit.apply(EntrySide.DEBIT, phase, delta);
    credit.apply(EntrySide.CREDIT, phase, delta);
    entries.save(
        new JournalEntry(t.getId(), debit.getId(), EntrySide.DEBIT, phase, delta, t.getCurrency()));
    entries.save(
        new JournalEntry(
            t.getId(), credit.getId(), EntrySide.CREDIT, phase, delta, t.getCurrency()));
  }

  private static void checkConstraints(Account debit, Account credit) {
    debit.checkConstraints();
    credit.checkConstraints();
  }

  private static void requirePending(Transfer transfer) {
    if (transfer.getStatus() != TransferStatus.PENDING) {
      throw ApiException.conflict(
          "not_pending", "transfer " + transfer.getId() + " is " + transfer.getStatus());
    }
  }

  private Account load(UUID id) {
    return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account", id));
  }

  private Transfer loadTransfer(UUID id) {
    return transfers.findById(id).orElseThrow(() -> ApiException.notFound("Transfer", id));
  }
}
