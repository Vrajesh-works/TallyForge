// Shapes returned by the ledger-service REST API. Amounts are integer minor units (cents).

export type TransferStatus = 'PENDING' | 'POSTED' | 'VOIDED';
export type TransferKind = 'TRANSFER' | 'REFUND';

export interface Transfer {
  id: string;
  debitAccountId: string;
  creditAccountId: string;
  amount: number;
  postedAmount: number;
  refundedAmount: number;
  currency: string;
  kind: TransferKind;
  status: TransferStatus;
  refundOf: string | null;
  holdReason: string | null;
  reference: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TransferPage {
  items: Transfer[];
  page: number;
  size: number;
  total: number;
}

export type AccountFlags = 'NONE' | 'DEBITS_MUST_NOT_EXCEED_CREDITS' | 'CREDITS_MUST_NOT_EXCEED_DEBITS';

export interface Account {
  id: string;
  code: string;
  name: string;
  currency: string;
  flags: AccountFlags;
  debitsPending: number;
  debitsPosted: number;
  creditsPending: number;
  creditsPosted: number;
  available: number;
  createdAt: string;
}

export const PAYMENT_STATUSES = [
  'CREATED',
  'REQUIRES_REVIEW',
  'AUTHORIZED',
  'CAPTURED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'VOIDED',
  'FAILED',
] as const;
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

export interface PaymentEvent {
  from: PaymentStatus | null;
  to: PaymentStatus;
  detail: string | null;
  at: string;
}

export interface Payment {
  id: string;
  merchantAccountId: string;
  amount: number;
  capturedAmount: number;
  refundedAmount: number;
  currency: string;
  status: PaymentStatus;
  cardLast4: string;
  gateway: string;
  gatewayReference: string | null;
  holdTransferId: string | null;
  failureReason: string | null;
  reviewReason: string | null;
  reference: string | null;
  createdAt: string;
  updatedAt: string;
  events: PaymentEvent[];
}

export interface Mismatch {
  check: string;
  subject: string;
  expected: number;
  actual: number;
}

export interface ReconciliationRun {
  id: string;
  trigger: string;
  status: string;
  accountsChecked: number;
  transfersChecked: number;
  paymentsChecked: number;
  mismatchCount: number;
  startedAt: string;
  finishedAt: string | null;
  mismatches: Mismatch[];
}

export interface ApiError {
  code: string;
  message: string;
}
