import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { Account, Transfer } from '../api/types';
import { Empty, ErrorBanner } from '../components/Feedback';
import { StatusBadge } from '../components/StatusBadge';
import { formatTime, shortId } from '../lib/format';
import { formatMinor } from '../lib/money';
import { EMPTY_FILTERS, transferQuery, type TransferFilters } from '../lib/query';
import { useAsync } from '../lib/useAsync';

const PAGE_SIZE = 50;

export function TransactionsPage({ accounts }: { accounts: Account[] }) {
  const [draft, setDraft] = useState<TransferFilters>(EMPTY_FILTERS);
  const [filters, setFilters] = useState<TransferFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [actionError, setActionError] = useState<Error>();
  const [busy, setBusy] = useState<string>();

  const { data, error, loading, reload } = useAsync(
    () => api.transfers(transferQuery(filters, page, PAGE_SIZE)),
    [filters, page],
  );
  const names = useMemo(() => new Map(accounts.map((a) => [a.id, a.code])), [accounts]);
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  const set = <K extends keyof TransferFilters>(key: K, value: TransferFilters[K]) =>
    setDraft((d) => ({ ...d, [key]: value }));

  const apply = (e: React.FormEvent) => {
    e.preventDefault();
    setFilters(draft);
    setPage(0);
  };

  const reset = () => {
    setDraft(EMPTY_FILTERS);
    setFilters(EMPTY_FILTERS);
    setPage(0);
  };

  const act = async (t: Transfer, action: 'post' | 'void') => {
    setBusy(t.id);
    setActionError(undefined);
    try {
      await (action === 'post' ? api.postTransfer(t.id) : api.voidTransfer(t.id));
      reload();
    } catch (e) {
      setActionError(e as Error);
    } finally {
      setBusy(undefined);
    }
  };

  return (
    <section>
      <form className="filters" onSubmit={apply} aria-label="Transaction filters">
        <label>
          Status
          <select value={draft.status} onChange={(e) => set('status', e.target.value as TransferFilters['status'])}>
            <option value="">Any</option>
            <option>PENDING</option>
            <option>POSTED</option>
            <option>VOIDED</option>
          </select>
        </label>
        <label>
          Kind
          <select value={draft.kind} onChange={(e) => set('kind', e.target.value as TransferFilters['kind'])}>
            <option value="">Any</option>
            <option>TRANSFER</option>
            <option>REFUND</option>
          </select>
        </label>
        <label>
          Account
          <select value={draft.accountId} onChange={(e) => set('accountId', e.target.value)}>
            <option value="">Any</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.code}
              </option>
            ))}
          </select>
        </label>
        <label>
          Fraud hold
          <select value={draft.held} onChange={(e) => set('held', e.target.value as TransferFilters['held'])}>
            <option value="">Any</option>
            <option value="true">Held</option>
            <option value="false">Not held</option>
          </select>
        </label>
        <label>
          From
          <input type="date" value={draft.fromDate} onChange={(e) => set('fromDate', e.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={draft.toDate} onChange={(e) => set('toDate', e.target.value)} />
        </label>
        <label>
          Min amount
          <input inputMode="decimal" placeholder="0.00" value={draft.minAmount} onChange={(e) => set('minAmount', e.target.value)} />
        </label>
        <label>
          Max amount
          <input inputMode="decimal" placeholder="0.00" value={draft.maxAmount} onChange={(e) => set('maxAmount', e.target.value)} />
        </label>
        <label className="grow">
          Reference
          <input type="search" placeholder="contains..." value={draft.q} onChange={(e) => set('q', e.target.value)} />
        </label>
        <div className="filter-actions">
          <button type="submit" className="primary">
            Apply
          </button>
          <button type="button" onClick={reset}>
            Reset
          </button>
          <a className="button" href={api.exportUrl(transferQuery(filters))} download>
            Export CSV
          </a>
        </div>
      </form>

      <ErrorBanner error={error ?? actionError} />

      <div className="table-meta">
        <span>{data ? `${data.total.toLocaleString()} transfers` : loading ? 'Loading...' : ''}</span>
        <button type="button" onClick={reload} disabled={loading}>
          Refresh
        </button>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Created</th>
              <th>ID</th>
              <th>Kind</th>
              <th>Status</th>
              <th>From</th>
              <th>To</th>
              <th className="num">Amount</th>
              <th className="num">Refunded</th>
              <th>Reference</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data?.items.map((t) => (
              <tr key={t.id} className={t.holdReason && t.status === 'PENDING' ? 'row-held' : undefined}>
                <td className="nowrap">{formatTime(t.createdAt)}</td>
                <td className="mono" title={t.id}>
                  {shortId(t.id)}
                </td>
                <td>{t.kind}</td>
                <td>
                  <StatusBadge status={t.status} />
                  {t.holdReason && <span className="hold" title="Fraud hold reason">{t.holdReason.replace(/,/g, ', ')}</span>}
                </td>
                <td title={t.debitAccountId}>{names.get(t.debitAccountId) ?? shortId(t.debitAccountId)}</td>
                <td title={t.creditAccountId}>{names.get(t.creditAccountId) ?? shortId(t.creditAccountId)}</td>
                <td className="num">{formatMinor(t.amount, t.currency)}</td>
                <td className="num">{t.refundedAmount ? formatMinor(t.refundedAmount, t.currency) : ''}</td>
                <td className="truncate" title={t.reference ?? ''}>
                  {t.reference}
                </td>
                <td className="nowrap">
                  {t.status === 'PENDING' && (
                    <>
                      <button type="button" className="small" disabled={busy === t.id} onClick={() => act(t, 'post')}>
                        Post
                      </button>
                      <button type="button" className="small danger" disabled={busy === t.id} onClick={() => act(t, 'void')}>
                        Void
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {data && data.items.length === 0 && <Empty>No transfers match these filters.</Empty>}
      </div>

      <nav className="pager" aria-label="Pagination">
        <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <span>
          Page {page + 1} of {pages}
        </span>
        <button type="button" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </nav>
    </section>
  );
}
