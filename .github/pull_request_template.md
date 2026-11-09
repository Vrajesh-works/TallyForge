## Summary

<!-- What does this change and why? -->

## What was built

-

## How it was tested

- [ ] `make test` (unit + Testcontainers integration + dashboard)
- [ ] Manual check against `make up`

## Money-safety checklist

- [ ] Every money movement is a balanced pair of journal entries
- [ ] New POST endpoints accept an `Idempotency-Key`
- [ ] Events go through the outbox in the same transaction as the ledger write

## Deferred / follow-ups

-
