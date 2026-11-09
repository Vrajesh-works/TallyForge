import type { Account } from '../api/types';
import { Empty, ErrorBanner } from '../components/Feedback';
import { formatMinor } from '../lib/money';

const FLAG_LABEL: Record<Account['flags'], string> = {
  NONE: 'Unconstrained',
  DEBITS_MUST_NOT_EXCEED_CREDITS: 'No overdraft',
  CREDITS_MUST_NOT_EXCEED_DEBITS: 'Credit capped',
};

export function AccountsPage(props: { accounts: Account[] | undefined; error: Error | undefined; reload: () => void }) {
  const { accounts } = props;
  const net = (accounts ?? []).reduce((sum, a) => sum + a.creditsPosted - a.debitsPosted, 0);
  return (
    <section>
      <div className="table-meta">
        <span>
          {accounts?.length ?? 0} accounts. Net of all posted balances:{' '}
          <strong className={net === 0 ? 'ok-text' : 'bad-text'}>{formatMinor(net)}</strong>
          {net === 0 ? ' (money is conserved)' : ' (should be zero)'}
        </span>
        <button type="button" onClick={props.reload}>
          Refresh
        </button>
      </div>
      <ErrorBanner error={props.error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Rule</th>
              <th className="num">Debits posted</th>
              <th className="num">Credits posted</th>
              <th className="num">Debits pending</th>
              <th className="num">Credits pending</th>
              <th className="num">Available</th>
            </tr>
          </thead>
          <tbody>
            {accounts?.map((a) => (
              <tr key={a.id}>
                <td className="mono" title={a.id}>
                  {a.code}
                </td>
                <td>{a.name}</td>
                <td>{FLAG_LABEL[a.flags]}</td>
                <td className="num">{formatMinor(a.debitsPosted, a.currency)}</td>
                <td className="num">{formatMinor(a.creditsPosted, a.currency)}</td>
                <td className="num">{formatMinor(a.debitsPending, a.currency)}</td>
                <td className="num">{formatMinor(a.creditsPending, a.currency)}</td>
                <td className="num strong">{formatMinor(a.available, a.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {accounts && accounts.length === 0 && <Empty>No accounts yet. Run the seed script or create one through the API.</Empty>}
      </div>
    </section>
  );
}
