import type { Account, Payment, PaymentStatus, ReconciliationRun, Transfer, TransferPage } from './types';

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  if (!res.ok) {
    let code = 'http_error';
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = (await res.json()) as { code?: string; message?: string };
      code = body.code ?? code;
      message = body.message ?? message;
    } catch {
      // Non-JSON error body; keep the status line.
    }
    throw new ApiRequestError(res.status, code, message);
  }
  return (await res.json()) as T;
}

/** Every money-moving POST carries a fresh key so a double click cannot act twice. */
function mutate<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const api = {
  transfers: (query: string) => request<TransferPage>(`/api/v1/transfers${query}`),
  exportUrl: (query: string) => `/api/v1/transfers/export${query}`,
  postTransfer: (id: string) => mutate<Transfer>(`/api/v1/transfers/${id}/post`),
  voidTransfer: (id: string) => mutate<Transfer>(`/api/v1/transfers/${id}/void`),
  accounts: () => request<Account[]>('/api/v1/accounts'),
  payments: (status?: PaymentStatus) =>
    request<Payment[]>(`/api/v1/payments?limit=200${status ? `&status=${status}` : ''}`),
  approvePayment: (id: string) => mutate<Payment>(`/api/v1/payments/${id}/approve`),
  capturePayment: (id: string) => mutate<Payment>(`/api/v1/payments/${id}/capture`),
  voidPayment: (id: string) => mutate<Payment>(`/api/v1/payments/${id}/void`),
  reconciliationRuns: () => request<ReconciliationRun[]>('/api/v1/reconciliation/runs'),
  runReconciliation: () => mutate<ReconciliationRun>('/api/v1/reconciliation/runs'),
};
