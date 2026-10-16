# Functional Guide

Business conventions implemented by the toolkit, written for traders, middle office, back office and developers.

## Products (toolsets)

| Toolset | Example | Quantity | Settlement | Discounted |
|---|---|---|---|---|
| `PHYS_GAS` | Henry Hub physical forward | MMBtu per day | Full value invoice, buyer pays volume × fixed price | Yes |
| `PHYS_POWER` | ERCOT North Hub peak block | MWh per day | Full value invoice | Yes |
| `COM_SWAP` | HH fixed-for-floating swap | units per day | Net cash: volume × (fixing − fixed) | Yes |
| `COM_FUT` | WTI crude future | total per contract month | Exchange variation margin (no bilateral invoice) | No |

A deal is split into calendar-month **delivery periods**, clipped to its start and end dates. Payment date is the 20th of the month after delivery.

## Deal lifecycle

```
PENDING ──► NEW ──► VALIDATED ──► MATURED
             │  ▲       │ │
             │  └─AMENDED◄┘ └──► CLOSEOUT
             └──────┴────────────► CANCELLED
```

- **Book**: numbers assigned, rules run, status `NEW`.
- **Validate**: middle office sign-off; `VALIDATED`.
- **Amend**: new version and tran number, status `AMENDED`, must be re-validated. The prior version stays in history.
- **Cancel**: terminal. A cancelled deal's external reference can be re-booked.
- **Mature**: EOD moves validated deals whose last delivery day has passed to `MATURED`.

Live statuses for position and risk: `NEW`, `VALIDATED`, `AMENDED`.

## Ops-service rules (pre-process)

| Rule | Blocks when | Notes |
|---|---|---|
| MandatoryFields | Index, portfolio, BUs, trader, unit, trade date missing; quantity ≤ 0; trade date after end date | Lists every problem at once |
| DuplicateTrade | External reference already on a non-cancelled deal | Protects against feed replays |
| Counterparty | Unknown external BU, suspended counterparty, internal BU not internal | Inter-desk trades warn |
| TraderAuthorization | Trader not assigned to the portfolio | Book segregation |
| PriceTolerance | Fixed price > 15% from volume-weighted forward price | Warns if curve missing or deal fully delivered |
| CreditLimit | Netted exposure incl. this deal would exceed the limit | Warns at ≥ 80% utilization; offsetting trades pass |

## Valuation

Per period: `PV = sign × volume × (price − fixed) × DF`

- Delivered periods use the realized **fixing**, are undiscounted, and report as **realized value**.
- Future periods use the **forward curve** and report as **unrealized MtM**.
- `DF = exp(−r × days/365)` to payment date; futures use `DF = 1`.
- **Delta** = signed volume × DF (sensitivity to a $1 move).

### P&L explain

Day-over-day change split into **new deals** (value of trades not in yesterday's book), **time** (old deals revalued on yesterday's curves at today's date) and **market** (today's curves vs yesterday's). Components sum exactly to the total change.

## Risk

- **Position**: unrealized signed volume and delta by portfolio × index × month.
- **VaR**: delta-based historical simulation. Each historical day's P&L = Σ (index delta × that day's price change); VaR is the loss at the confidence percentile, scaled by √holding days.
- **Credit**: one netting set per counterparty; exposure = max(0, net MtM + realized receivable) compared to the limit.

## Settlement and accounting

- Cashflows are generated for delivered periods only; futures are excluded.
- Invoices group by counterparty, currency and payment date. Counterparties with a netting agreement get one net document; others get separate AR and AP documents.
- GL extract posts realized amounts to AR/Revenue or Cost/AP and unrealized MtM to derivative asset/liability against unrealized P&L. Every batch is checked for debit = credit.

## Reconciliation

Futures positions by index/month are compared with the clearing broker statement; differences above tolerance are reported as breaks for operations to investigate.
