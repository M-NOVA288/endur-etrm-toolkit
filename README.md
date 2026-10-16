# Endur ETRM Toolkit

A Java 17 customization toolkit modeled on **OpenLink Endur** workflows for energy and commodity trading — the kind of code an Endur developer writes around the platform: ops-service trade rules, valuation and risk extensions, settlement logic, inbound/outbound interfaces, reconciliation and EOD batch processing.

It covers the full front-to-back flow for physical natural gas, physical power, financial swaps and exchange futures, including physical gas scheduling:

```
Trade feed ─► Ops pre-process rules ─► Book / Validate / Amend / Cancel ─► Trade events ─► Confirmations
                                                    │
                       Market data ─► Valuation (MtM) ─► Positions ─► VaR ─► Credit exposure
                                                    │
                                Settlements ─► Invoices ─► GL extract ─► Broker reconciliation ─► Maturity ─► P&L explain
                                                    │
              Physical gas deals ─► Scheduling ─► Nominations ─► Pipeline confirmations/cuts ─► Imbalances
```

> Endur's Java plugin API (OpenComponents / JVS) is licensed vendor software and is not included. All platform access goes through the `EndurSession` interface, so business logic is fully testable without an Endur environment; a production adapter maps that interface onto the vendor API. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start

```bash
mvn test                                   # 48 unit + integration tests
mvn package
java -jar target/endur-etrm-toolkit-1.0.0.jar                 # run the sample EOD batch
java -jar target/endur-etrm-toolkit-1.0.0.jar my_trades.csv   # run against your own feed
```

No runtime dependencies — only JUnit for tests — so the jar can be dropped into a plugin classpath.

## What's inside

| Package | Functional area | Highlights |
|---|---|---|
| `domain` | Deal capture | `Deal` with deal tracking number vs tran number versioning, monthly delivery profile, toolsets (`PHYS_GAS`, `PHYS_POWER`, `COM_SWAP`, `COM_FUT`), enforced tran status lifecycle |
| `ops` / `ops.rules` | Operation Services | Pre-process rule chain that can veto a status change: mandatory fields, duplicate external ref, counterparty authorization, trader-portfolio security, fat-finger price tolerance, pre-deal credit limit. A rule that throws blocks the trade rather than letting it through |
| `lifecycle` | Trade processing | Book, validate, amend (new version + full history), cancel, auto-mature; audit trail; post-process event rows in `USER_TRADE_EVENTS` |
| `market` | Market data | Monthly forward curves with interpolation, realized fixings, discounting, curve CSV import, scenario shifts |
| `valuation` | Sim / MtM | Realized vs unrealized MtM, discounted delta, failure-isolated batch valuation, day-over-day P&L explain (new deals / time / market) |
| `risk` | Risk | Position by portfolio/index/month, delta-based historical VaR, netted counterparty credit exposure vs limits |
| `settlement` | Back office | Physical full-value invoicing vs swap net settlement (futures via margin), payment netting by counterparty agreement |
| `interfaces` | Integrations | Header-driven CSV trade feed with per-row rejects, XML confirmations, event dispatcher (idempotent, at-least-once), balanced GL journal extract, futures position reconciliation vs clearing broker |
| `scheduling` | Gas scheduling | Supply-to-market pathing per pipeline, firm before interruptible, fuel gross-up, MDQ limits; NAESB cycle deadlines; constraint allocation (IT cut first, firm pro rata); confirmations and cuts; re-nomination worklist; transport cost; imbalance tolerance and tiered cash-out |
| `adapter` | Platform boundary | `EndurSession` interface + in-memory implementation (deals, static data, user tables, audit) |
| `app` | EOD batch | `EodBatchRunner` wiring everything together on sample data |

## Sample EOD output (abridged)

```
-- 2. Booking (pre-process ops rules)
  BOOKED  ICE-778101   -> deal 100001
  BOOKED  BRK-22051    -> deal 100003  [WARN PriceTolerance: deal fully delivered (late booking) ...]
  BLOCKED ICE-778110   BLOCK PriceTolerance: price 34.5000 is 914.7% away from market 3.4000 (tolerance 15.0%)
  BLOCKED ICE-778111   BLOCK Counterparty: counterparty BAYOU_HOLDINGS is suspended / not authorized
  BLOCKED BRK-22060    BLOCK TraderAuthorization: trader mchen not authorized for portfolio GAS_PHYS_TX
  BLOCKED ICE-778101   BLOCK DuplicateTrade: external ref ICE-778101 already booked as deal 100001

-- 6. Risk
  1-day 95% historical VaR: 237,859
    LONESTAR_POWER     net 810,610  exposure 810,610  limit 4,000,000  util 20.3%

-- 7. Settlements / invoices
  2026-09-20 LONESTAR_POWER     USD PAY       2,047,240.00  (2 lines)

-- 8. GL extract: 18 journal lines, balanced=true
-- 9. BREAK WTI_NYMEX 2026-12 internal 50,000 broker 49,000 diff 1,000

-- 12. Gas scheduling  gas day 2026-11-01 (pipeline GULF_STAR)
  GULF_STAR-2026-11-01-1   GS-FT-001  GS-R-KATY  GS-D-HOUSTON-CG  8,122/8,000
  GULF_STAR-2026-11-01-3   GS-IT-001  GS-R-KATY  GS-D-BAYTOWN     2,677/2,636
  Submit at 13:05 for Timely: 3 rejected ... next open cycle is EVENING
    GULF_STAR-2026-11-01-3   CUT       scheduled    2,000 of    2,677  cut 677
  Re-nominate: market deal 100011 short 666 MMBtu -> source replacement supply in EVENING cycle
    actual rec 10,950 del 12,100 -> SHORT 1,314 (12.1%) TIER_2 cash-out -3,283.34
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — design, the platform adapter boundary, how this maps to Endur plugins
- [docs/FUNCTIONAL_GUIDE.md](docs/FUNCTIONAL_GUIDE.md) — ETRM business logic: deal lifecycle, valuation, risk, settlement conventions
- [docs/SCHEDULING_GUIDE.md](docs/SCHEDULING_GUIDE.md) — gas scheduling: nominations, NAESB cycles, cuts, imbalances
- [docs/PRODUCTION_SUPPORT.md](docs/PRODUCTION_SUPPORT.md) — runbook: common incidents, root-cause steps, safe re-runs
- [sql/user_tables.sql](sql/user_tables.sql) — DDL for the custom user tables the toolkit writes to

All counterparty names, prices and market data in this repository are fictional and for illustration only.
