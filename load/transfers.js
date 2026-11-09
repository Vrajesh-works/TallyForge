// k6 load test: concurrent idempotent transfers between funded wallets.
// Run against a local stack:
//   docker run --rm --network host -i grafana/k6:0.54.0 run - < load/transfers.js
// Override the target with -e API=http://host:8080
import http from 'k6/http';
import { check } from 'k6';

const API = __ENV.API || 'http://localhost:8080';
const WALLETS = 50;
const json = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:transfer}': ['p(95)<250'],
  },
};

function account(code, flags) {
  const res = http.post(
    `${API}/api/v1/accounts`,
    JSON.stringify({ code, name: code, currency: 'USD', flags }),
    json,
  );
  return res.json('id');
}

export function setup() {
  const run = Math.random().toString(36).slice(2, 8);
  const bank = account(`k6-bank-${run}`, 'NONE');
  const wallets = [];
  for (let i = 0; i < WALLETS; i++) {
    const id = account(`k6-wallet-${run}-${i}`, 'DEBITS_MUST_NOT_EXCEED_CREDITS');
    http.post(
      `${API}/api/v1/transfers`,
      JSON.stringify({ debitAccountId: bank, creditAccountId: id, amount: 500000 }),
      json,
    );
    wallets.push(id);
  }
  return { wallets };
}

export default function (data) {
  const w = data.wallets;
  const from = w[Math.floor(Math.random() * w.length)];
  let to = w[Math.floor(Math.random() * w.length)];
  if (to === from) to = w[(w.indexOf(from) + 1) % w.length];
  const res = http.post(
    `${API}/api/v1/transfers`,
    JSON.stringify({
      debitAccountId: from,
      creditAccountId: to,
      amount: 1 + Math.floor(Math.random() * 500),
      reference: 'k6',
    }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` },
      tags: { name: 'transfer' },
    },
  );
  check(res, { created: (r) => r.status === 201 });
}
