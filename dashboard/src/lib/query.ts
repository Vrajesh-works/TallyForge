import type { TransferKind, TransferStatus } from '../api/types';
import { parseMajor } from './money';

export interface TransferFilters {
  status: TransferStatus | '';
  kind: TransferKind | '';
  accountId: string;
  /** Inclusive start day, yyyy-mm-dd, interpreted in UTC. */
  fromDate: string;
  /** Inclusive end day, yyyy-mm-dd, interpreted in UTC. */
  toDate: string;
  minAmount: string;
  maxAmount: string;
  held: '' | 'true' | 'false';
  q: string;
}

export const EMPTY_FILTERS: TransferFilters = {
  status: '',
  kind: '',
  accountId: '',
  fromDate: '',
  toDate: '',
  minAmount: '',
  maxAmount: '',
  held: '',
  q: '',
};

function nextDay(day: string): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString();
}

/**
 * Turns form state into the query string the transfer list and export endpoints accept. Blank
 * fields are dropped; amounts typed in major units become minor units; the end date is made
 * exclusive by moving to the next midnight.
 */
export function transferQuery(f: TransferFilters, page?: number, size?: number): string {
  const p = new URLSearchParams();
  if (f.status) p.set('status', f.status);
  if (f.kind) p.set('kind', f.kind);
  if (f.accountId) p.set('accountId', f.accountId);
  if (f.fromDate) p.set('from', `${f.fromDate}T00:00:00.000Z`);
  if (f.toDate) p.set('to', nextDay(f.toDate));
  const min = parseMajor(f.minAmount);
  if (min !== null) p.set('minAmount', String(min));
  const max = parseMajor(f.maxAmount);
  if (max !== null) p.set('maxAmount', String(max));
  if (f.held) p.set('held', f.held);
  if (f.q.trim()) p.set('q', f.q.trim());
  if (page !== undefined) p.set('page', String(page));
  if (size !== undefined) p.set('size', String(size));
  const s = p.toString();
  return s ? `?${s}` : '';
}
