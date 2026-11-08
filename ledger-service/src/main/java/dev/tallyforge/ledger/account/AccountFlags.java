package dev.tallyforge.ledger.account;

/**
 * Balance constraints, modelled on TigerBeetle account flags. Pending amounts count against the
 * constraint so that a hold really reserves funds.
 */
public enum AccountFlags {
  /** No constraint: the account may carry any net balance (for example a settlement account). */
  NONE,
  /**
   * debits_pending + debits_posted must never exceed credits_posted (a wallet cannot go negative).
   */
  DEBITS_MUST_NOT_EXCEED_CREDITS,
  /** credits_pending + credits_posted must never exceed debits_posted. */
  CREDITS_MUST_NOT_EXCEED_DEBITS
}
