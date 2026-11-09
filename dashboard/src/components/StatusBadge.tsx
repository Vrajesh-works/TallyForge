const TONE: Record<string, string> = {
  POSTED: 'ok',
  CAPTURED: 'ok',
  OK: 'ok',
  AUTHORIZED: 'info',
  PENDING: 'warn',
  REQUIRES_REVIEW: 'warn',
  PARTIALLY_REFUNDED: 'info',
  REFUNDED: 'muted',
  VOIDED: 'muted',
  CREATED: 'muted',
  FAILED: 'bad',
  MISMATCH: 'bad',
};

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${TONE[status] ?? 'muted'}`}>{status.replace(/_/g, ' ')}</span>;
}
