import { useState } from 'react';
import { api } from '../api/client';
import { Empty, ErrorBanner } from '../components/Feedback';
import { StatusBadge } from '../components/StatusBadge';
import { formatTime } from '../lib/format';
import { useAsync } from '../lib/useAsync';

export function ReconciliationPage() {
  const { data, error, loading, reload } = useAsync(() => api.reconciliationRuns(), []);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<Error>();

  const runNow = async () => {
    setRunning(true);
    setRunError(undefined);
    try {
      await api.runReconciliation();
      reload();
    } catch (e) {
      setRunError(e as Error);
    } finally {
      setRunning(false);
    }
  };

  return (
    <section>
      <div className="table-meta">
        <span>
          The daily job recomputes every balance from the journal and compares it with the stored totals.
        </span>
        <button type="button" className="primary" onClick={runNow} disabled={running}>
          {running ? 'Running...' : 'Run now'}
        </button>
      </div>
      <ErrorBanner error={error ?? runError} />
      {data?.map((run) => (
        <article key={run.id} className="card">
          <header>
            <StatusBadge status={run.status} />
            <span>{formatTime(run.startedAt)}</span>
            <span className="muted">{run.trigger.toLowerCase()}</span>
          </header>
          <p>
            Checked {run.accountsChecked} accounts, {run.transfersChecked} transfers and {run.paymentsChecked} payments.{' '}
            {run.mismatchCount === 0 ? 'No mismatches.' : `${run.mismatchCount} mismatches.`}
          </p>
          {run.mismatches.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Check</th>
                  <th>Subject</th>
                  <th className="num">Expected</th>
                  <th className="num">Actual</th>
                </tr>
              </thead>
              <tbody>
                {run.mismatches.map((m, i) => (
                  <tr key={i}>
                    <td>{m.check}</td>
                    <td className="mono">{m.subject}</td>
                    <td className="num">{m.expected}</td>
                    <td className="num">{m.actual}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </article>
      ))}
      {data && data.length === 0 && !loading && <Empty>No runs yet. Press Run now to start one.</Empty>}
    </section>
  );
}
