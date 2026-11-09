import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { PAYMENT_STATUSES, type Account, type Payment, type PaymentStatus } from '../api/types';
import { Empty, ErrorBanner } from '../components/Feedback';
import { StatusBadge } from '../components/StatusBadge';
import { formatTime, shortId } from '../lib/format';
import { formatMinor } from '../lib/money';
import { useAsync } from '../lib/useAsync';

type Action = 'approve' | 'capture' | 'void';

const ACTIONS: Partial<Record<PaymentStatus, Action[]>> = {
  REQUIRES_REVIEW: ['approve', 'void'],
  AUTHORIZED: ['capture', 'void'],
};

export function PaymentsPage({ accounts }: { accounts: Account[] }) {
  const [status, setStatus] = useState<PaymentStatus | ''>('');
  const [open, setOpen] = useState<string>();
  const [busy, setBusy] = useState<string>();
  const [actionError, setActionError] = useState<Error>();
  const { data, error, loading, reload } = useAsync(() => api.payments(status || undefined), [status]);
  const names = useMemo(() => new Map(accounts.map((a) => [a.id, a.code])), [accounts]);

  const act = async (p: Payment, action: Action) => {
    setBusy(p.id);
    setActionError(undefined);
    try {
      if (action === 'approve') await api.approvePayment(p.id);
      else if (action === 'capture') await api.capturePayment(p.id);
      else await api.voidPayment(p.id);
      reload();
    } catch (e) {
      setActionError(e as Error);
    } finally {
      setBusy(undefined);
    }
  };

  return (
    <section>
      <div className="filters">
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value as PaymentStatus | '')}>
            <option value="">Any</option>
            {PAYMENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s.replace(/_/g, ' ')}
              </option>
            ))}
          </select>
        </label>
        <div className="filter-actions">
          <button type="button" onClick={reload} disabled={loading}>
            Refresh
          </button>
        </div>
      </div>

      <ErrorBanner error={error ?? actionError} />

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Created</th>
              <th>ID</th>
              <th>Merchant</th>
              <th>Card</th>
              <th>Status</th>
              <th className="num">Amount</th>
              <th className="num">Captured</th>
              <th className="num">Refunded</th>
              <th>Reference</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data?.map((p) => (
              <PaymentRow
                key={p.id}
                payment={p}
                merchant={names.get(p.merchantAccountId) ?? shortId(p.merchantAccountId)}
                expanded={open === p.id}
                onToggle={() => setOpen(open === p.id ? undefined : p.id)}
                busy={busy === p.id}
                onAction={(a) => act(p, a)}
              />
            ))}
          </tbody>
        </table>
        {data && data.length === 0 && <Empty>No payments yet.</Empty>}
      </div>
    </section>
  );
}

function PaymentRow(props: {
  payment: Payment;
  merchant: string;
  expanded: boolean;
  onToggle: () => void;
  busy: boolean;
  onAction: (a: Action) => void;
}) {
  const { payment: p } = props;
  return (
    <>
      <tr className={p.status === 'REQUIRES_REVIEW' ? 'row-held' : undefined}>
        <td className="nowrap">{formatTime(p.createdAt)}</td>
        <td className="mono">
          <button type="button" className="link" onClick={props.onToggle} aria-expanded={props.expanded}>
            {shortId(p.id)}
          </button>
        </td>
        <td>{props.merchant}</td>
        <td className="mono">**** {p.cardLast4}</td>
        <td>
          <StatusBadge status={p.status} />
          {p.reviewReason && <span className="hold">{p.reviewReason}</span>}
          {p.failureReason && <span className="hold">{p.failureReason}</span>}
        </td>
        <td className="num">{formatMinor(p.amount, p.currency)}</td>
        <td className="num">{p.capturedAmount ? formatMinor(p.capturedAmount, p.currency) : ''}</td>
        <td className="num">{p.refundedAmount ? formatMinor(p.refundedAmount, p.currency) : ''}</td>
        <td className="truncate">{p.reference}</td>
        <td className="nowrap">
          {(ACTIONS[p.status] ?? []).map((a) => (
            <button
              key={a}
              type="button"
              className={`small${a === 'void' ? ' danger' : ''}`}
              disabled={props.busy}
              onClick={() => props.onAction(a)}
            >
              {a[0].toUpperCase() + a.slice(1)}
            </button>
          ))}
        </td>
      </tr>
      {props.expanded && (
        <tr className="detail">
          <td colSpan={10}>
            <ol className="timeline">
              {p.events.map((e, i) => (
                <li key={i}>
                  <span className="nowrap">{formatTime(e.at)}</span>
                  <span>
                    {e.from ?? 'START'} {'->'} <strong>{e.to}</strong>
                  </span>
                  {e.detail && <span className="muted">{e.detail}</span>}
                </li>
              ))}
            </ol>
          </td>
        </tr>
      )}
    </>
  );
}
