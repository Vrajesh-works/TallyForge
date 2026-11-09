# TallyForge - Product Document

## Problem

Every company that moves money (marketplaces, wallets, lenders, payroll, card acquirers)
eventually writes its own ledger, and most first versions get the hard parts wrong:

- A client retries a timed-out `POST /charge` and the customer is charged twice.
- Two concurrent transfers read the same balance and both succeed, overdrawing an account.
- A database commit succeeds but the event that tells downstream systems about it is lost
  (or the event is sent and the commit rolls back).
- An authorization is captured twice, or a refund exceeds what was captured.
- Nobody notices that balances drifted until month-end close.

TallyForge is a double-entry ledger and payments API whose central promise is
**money is only ever moved, never created, lost or charged twice**.

## Target users

| User | Need |
| --- | --- |
| Backend engineers at a fintech or marketplace | A ledger API with strong guarantees they can call from checkout, payout and refund flows. |
| Finance / operations analysts | A dashboard to search, filter and export transactions, and a daily reconciliation report. |
| Risk analysts | Suspicious transfers held for review instead of settling immediately. |
| Merchant integrators | Signed webhooks that tell their systems when a payment changes state. |

## Core features (MVP, built today)

1. **Accounts** with a currency and optional balance constraints
   (debits must not exceed credits, or the reverse), so a customer wallet can never go negative.
2. **Double-entry transfers**: every transfer debits one account and credits another for the same
   amount, recorded as journal entries. The sum of all debits always equals the sum of all credits.
3. **Two-phase holds** (inspired by TigerBeetle): a transfer can be created `PENDING`, which
   reserves funds, then later `POSTED` (fully or partially) or `VOIDED`.
4. **Refunds** that reverse a posted transfer, capped so total refunds never exceed the posted amount.
5. **Idempotency keys** on every money-moving `POST`: a retry with the same key returns the original
   response; a reused key with a different body is rejected.
6. **Concurrency-safe balances** via optimistic locking with bounded retries, proven by a test that
   fires parallel transfers and checks conservation of money and the overdraft constraint.
7. **Transactional outbox**: ledger rows and their domain events commit in one database
   transaction; a relay publishes events to Kafka at least once, with stable event ids for dedupe.
8. **Payments** with a Kill Bill style state machine
   (`CREATED -> AUTHORIZED -> CAPTURED -> REFUNDED`, plus `FAILED`, `VOIDED`, `REQUIRES_REVIEW`)
   behind a pluggable `PaymentGateway` adapter interface, shipped with a mock card processor
   (test card numbers for approve, decline and transient failure).
9. **Signed outbound webhooks** (HMAC-SHA256 over timestamp and body), retried with exponential
   backoff and jitter; deliveries that exhaust retries go to a Kafka dead-letter topic.
10. **Daily reconciliation job** that recomputes balances from journal entries, checks global
    debit/credit equality and payment-vs-ledger totals, and records any mismatch.
11. **Rule-based fraud hold**: suspicious transfers (large amounts, account-draining transfers,
    high velocity) are forced into `PENDING` with a hold reason for manual review.
12. **React dashboard**: transactions table with filters (status, account, kind, amount, date),
    pagination, CSV export, account balances and reconciliation results.
13. **PaySim-shaped replay**: a synthetic generator matching the PaySim schema and a k6 script
    that replays it through the API to measure latency.

## Non-goals (for this MVP)

- Cloud deployment (AWS, SQS, Terraform). The design keeps these as later adapters.
- Multi-currency conversion / FX. Each transfer is single-currency.
- Real card network integration, PCI scope, tokenization or 3-D Secure.
- User authentication and multi-tenant authorization for the API and dashboard.
- Interest, fees, statements, or general ledger chart-of-accounts reporting.
- Horizontal sharding of the ledger.

## Success metrics

| Metric | Target |
| --- | --- |
| Double charges under client retry | 0 (idempotency integration test) |
| Money created or lost under parallel load | 0 (concurrency test: sum of balances unchanged, no constraint violation) |
| Lost events between DB and Kafka | 0 (outbox integration test against a real broker) |
| Reconciliation false negatives on injected drift | 0 (test corrupts a balance and expects a mismatch) |
| Webhook delivery | Signed, retried with backoff, dead-lettered after max attempts (integration test) |
| API latency | p95 reported in the README from the k6 replay |
| Local setup | One command: `docker compose up --build` |
| CI | Unit + integration tests green on every pull request |
