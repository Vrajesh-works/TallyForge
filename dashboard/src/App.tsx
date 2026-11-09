import { useEffect, useState } from 'react';
import { api } from './api/client';
import { AccountsPage } from './pages/AccountsPage';
import { PaymentsPage } from './pages/PaymentsPage';
import { ReconciliationPage } from './pages/ReconciliationPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { useAsync } from './lib/useAsync';

const TABS = [
  { id: 'transactions', label: 'Transactions' },
  { id: 'payments', label: 'Payments' },
  { id: 'accounts', label: 'Accounts' },
  { id: 'reconciliation', label: 'Reconciliation' },
] as const;
type Tab = (typeof TABS)[number]['id'];

function tabFromHash(): Tab {
  const id = window.location.hash.slice(1);
  return TABS.some((t) => t.id === id) ? (id as Tab) : 'transactions';
}

export function App() {
  const [tab, setTab] = useState<Tab>(tabFromHash);
  const accounts = useAsync(() => api.accounts(), [tab]);

  useEffect(() => {
    const onHash = () => setTab(tabFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  return (
    <div className="shell">
      <header className="top">
        <div className="brand">
          <span className="logo" aria-hidden>
            TF
          </span>
          <div>
            <h1>TallyForge</h1>
            <p>Double-entry ledger and payments</p>
          </div>
        </div>
        <nav className="tabs" aria-label="Sections">
          {TABS.map((t) => (
            <a key={t.id} href={`#${t.id}`} className={tab === t.id ? 'active' : undefined} aria-current={tab === t.id ? 'page' : undefined}>
              {t.label}
            </a>
          ))}
        </nav>
      </header>
      <main>
        {tab === 'transactions' && <TransactionsPage accounts={accounts.data ?? []} />}
        {tab === 'payments' && <PaymentsPage accounts={accounts.data ?? []} />}
        {tab === 'accounts' && <AccountsPage accounts={accounts.data} error={accounts.error} reload={accounts.reload} />}
        {tab === 'reconciliation' && <ReconciliationPage />}
      </main>
    </div>
  );
}
