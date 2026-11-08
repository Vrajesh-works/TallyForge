package dev.tallyforge.ledger.transfer;

/** Two-phase transfer lifecycle: PENDING reserves funds, then POSTED or VOIDED (terminal). */
public enum TransferStatus {
  PENDING,
  POSTED,
  VOIDED
}
