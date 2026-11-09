#!/usr/bin/env python3
"""Replay PaySim-shaped mobile-money traffic through the TallyForge API.

PaySim (Lopez-Rojas et al., EMSS 2016) is distributed on Kaggle behind a login, so this script can
either read the real CSV (--file PS_20174392719_1491204439457_log.csv) or generate a small synthetic
file with the same columns (--generate N). Amounts in PaySim are decimal currency units; they are
converted to integer cents here.

Mapping of PaySim transaction types onto ledger transfers:
  CASH_IN   cash-desk  -> customer
  CASH_OUT  customer   -> cash-desk
  PAYMENT   customer   -> merchant
  TRANSFER  customer   -> customer
  DEBIT     customer   -> bank

Customers are no-overdraft wallets that get an opening balance from the bank account equal to
PaySim's oldbalanceOrg, so a transfer that empties the account trips the DRAINS_ACCOUNT hold.

Only the Python standard library is used.
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

COLUMNS = [
    "step",
    "type",
    "amount",
    "nameOrig",
    "oldbalanceOrg",
    "newbalanceOrig",
    "nameDest",
    "oldbalanceDest",
    "newbalanceDest",
    "isFraud",
    "isFlaggedFraud",
]


def generate(path: Path, rows: int, seed: int) -> None:
    """Writes a PaySim-schema CSV with a realistic type mix and a few account-draining frauds."""
    rng = random.Random(seed)
    customers = [f"C{rng.randrange(10**9, 10**10)}" for _ in range(max(10, rows // 8))]
    merchants = [f"M{rng.randrange(10**9, 10**10)}" for _ in range(max(5, rows // 25))]
    balances = {c: round(rng.uniform(50, 5000), 2) for c in customers}
    mix = [("PAYMENT", 0.34), ("CASH_OUT", 0.35), ("CASH_IN", 0.22), ("TRANSFER", 0.08), ("DEBIT", 0.01)]

    out = []
    step = 1
    while len(out) < rows:
        if rng.random() < 0.15:
            step += 1
        if rng.random() < 0.02 and len(out) < rows - 1:
            # PaySim fraud pattern: empty the victim with a TRANSFER, then CASH_OUT from the mule.
            victim, mule = rng.sample(customers, 2)
            amount = balances[victim]
            if amount < 10:
                continue
            out.append(_row(step, "TRANSFER", amount, victim, balances, mule, fraud=True))
            out.append(_row(step, "CASH_OUT", amount, mule, balances, f"C{rng.randrange(10**9, 10**10)}", fraud=True))
            continue
        kind = rng.choices([k for k, _ in mix], weights=[w for _, w in mix])[0]
        orig = rng.choice(customers)
        if kind == "CASH_IN":
            amount = round(rng.uniform(10, 2000), 2)
        else:
            amount = round(min(balances[orig] * rng.uniform(0.05, 0.6), 9000), 2)
            if amount < 1:
                continue
        dest = rng.choice(merchants) if kind == "PAYMENT" else rng.choice(customers)
        out.append(_row(step, kind, amount, orig, balances, dest, fraud=False))

    with path.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(COLUMNS)
        writer.writerows(out)
    print(f"wrote {len(out)} PaySim-shaped rows to {path}")


def _row(step, kind, amount, orig, balances, dest, fraud):
    old_orig = balances.get(orig, 0.0)
    if kind == "CASH_IN":
        new_orig = old_orig + amount
    else:
        new_orig = max(0.0, old_orig - amount)
    balances[orig] = round(new_orig, 2)
    old_dest = balances.get(dest, 0.0) if dest.startswith("C") else 0.0
    new_dest = old_dest + amount if dest.startswith("C") and kind != "CASH_IN" else old_dest
    if dest.startswith("C"):
        balances[dest] = round(new_dest, 2)
    return [
        step,
        kind,
        f"{amount:.2f}",
        orig,
        f"{old_orig:.2f}",
        f"{new_orig:.2f}",
        dest,
        f"{old_dest:.2f}",
        f"{new_dest:.2f}",
        1 if fraud else 0,
        0,
    ]


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def call(self, method: str, path: str, body=None, key: str | None = None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if key:
            req.add_header("Idempotency-Key", key)
        try:
            with urllib.request.urlopen(req, timeout=15) as res:
                return res.status, json.loads(res.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"null")

    def account(self, code: str, name: str, flags: str) -> str:
        status, body = self.call(
            "POST", "/api/v1/accounts", {"code": code, "name": name, "currency": "USD", "flags": flags}
        )
        if status != 201:
            raise SystemExit(f"cannot create account {code}: {status} {body}")
        return body["id"]


def cents(text: str) -> int:
    units, _, frac = text.partition(".")
    return int(units) * 100 + int((frac + "00")[:2])


def replay(path: Path, api: Api, limit: int | None) -> None:
    run = uuid.uuid4().hex[:6]
    accounts: dict[str, str] = {}
    funded: set[str] = set()

    def acct(name: str, flags: str) -> str:
        if name not in accounts:
            accounts[name] = api.account(f"{name}-{run}", name, flags)
        return accounts[name]

    bank = acct("bank", "NONE")
    cash = acct("cash-desk", "NONE")
    stats = {"posted": 0, "held": 0, "rejected": 0, "fraud_rows": 0, "fraud_held": 0}
    latencies = []

    with path.open(newline="") as f:
        for i, row in enumerate(csv.DictReader(f)):
            if limit is not None and i >= limit:
                break
            kind, amount = row["type"], cents(row["amount"])
            orig, dest = row["nameOrig"], row["nameDest"]
            if amount <= 0:
                continue
            orig_id = acct(orig, "DEBITS_MUST_NOT_EXCEED_CREDITS")
            # The first time a customer sends money, give it PaySim's opening balance.
            first_send = orig not in funded
            funded.add(orig)
            if first_send and cents(row["oldbalanceOrg"]) > 0:
                api.call(
                    "POST",
                    "/api/v1/transfers",
                    {"debitAccountId": bank, "creditAccountId": orig_id, "amount": cents(row["oldbalanceOrg"]),
                     "reference": f"opening-balance {orig}"},
                    key=f"open-{run}-{orig}",
                )
            if kind == "CASH_IN":
                debit, credit = cash, orig_id
            elif kind == "CASH_OUT":
                debit, credit = orig_id, cash
            elif kind == "DEBIT":
                debit, credit = orig_id, bank
            elif kind == "PAYMENT":
                debit, credit = orig_id, acct(dest, "NONE")
            else:
                debit, credit = orig_id, acct(dest, "DEBITS_MUST_NOT_EXCEED_CREDITS")

            started = time.perf_counter()
            status, body = api.call(
                "POST",
                "/api/v1/transfers",
                {"debitAccountId": debit, "creditAccountId": credit, "amount": amount,
                 "reference": f"paysim step={row['step']} {kind} {orig}->{dest}"},
                key=f"paysim-{run}-{i}",
            )
            latencies.append((time.perf_counter() - started) * 1000)
            fraud = row["isFraud"] == "1"
            stats["fraud_rows"] += fraud
            if status == 201 and body["status"] == "PENDING":
                stats["held"] += 1
                stats["fraud_held"] += fraud
            elif status == 201:
                stats["posted"] += 1
            else:
                stats["rejected"] += 1
                stats["fraud_rejected"] = stats.get("fraud_rejected", 0) + fraud
                code = (body or {}).get("code", str(status))
                stats.setdefault("rejected_by_code", {})
                stats["rejected_by_code"][code] = stats["rejected_by_code"].get(code, 0) + 1

    latencies.sort()
    p95 = latencies[int(len(latencies) * 0.95) - 1] if latencies else 0
    print(json.dumps({**stats, "requests": len(latencies), "p95_ms": round(p95, 1)}, indent=2))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--file", type=Path, help="PaySim CSV to replay (real or generated)")
    parser.add_argument("--generate", type=int, metavar="N", help="generate N synthetic PaySim rows first")
    parser.add_argument("--out", type=Path, default=Path("paysim-sample.csv"), help="where --generate writes")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--limit", type=int, help="replay at most this many rows")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--no-replay", action="store_true", help="only generate the CSV")
    args = parser.parse_args(argv)

    path = args.file
    if args.generate:
        generate(args.out, args.generate, args.seed)
        path = args.out
    if path is None:
        parser.error("pass --file or --generate")
    if not args.no_replay:
        replay(path, Api(args.api), args.limit)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
