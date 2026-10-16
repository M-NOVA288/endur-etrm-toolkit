# Gas Scheduling Guide

The `scheduling` package covers the physical side of the gas business: turning traded deals into pipeline nominations, handling pipeline confirmations and cuts, and settling imbalances after flow. It sits downstream of trading — deals booked through the lifecycle service are the input.

## Daily flow

```
Traded book (PHYS_GAS buys/sells)
        │  + deal locations (receipt / delivery points)
        ▼
ScheduleBuilder ── match supply to market per pipeline, pick contracts, gross up fuel
        ▼
NominationService.submit ── NAESB cycle deadline check
        ▼
Pipeline constraint ── allocateConstraint (IT cut first, then firm pro rata)
        ▼
NominationService.confirm ── SCHEDULED / CUT
        ▼
marketShortfall ── re-nomination worklist for the next cycle
        ▼
After flow: ImbalanceCalculator ── tolerance band, tiered cash-out
```

## Concepts

| Term | Meaning in the toolkit |
|---|---|
| Gas day | 9:00 AM to 9:00 AM Central Clock Time, identified by its start date |
| Receipt point | Where supply enters the pipeline (a physical buy flows here) |
| Delivery point | Where gas leaves to the market (a physical sell flows here) |
| Transport contract | Firm (FT) or interruptible (IT) capacity with an MDQ, fuel rate and variable rate |
| MDQ | Maximum daily quantity, applied to receipt volume |
| Fuel | Share of received gas the pipeline keeps; delivered = received × (1 − fuel) |
| Nomination | Request to move a volume on a contract from a receipt to a delivery point for a gas day |
| Cut | Pipeline confirms less than nominated, usually due to a constraint |
| Imbalance | Actual receipts net of fuel minus actual deliveries |

## NAESB nomination cycles

| Cycle | Deadline (CCT) | Day |
|---|---|---|
| Timely | 1:00 PM | day before gas day |
| Evening | 6:00 PM | day before gas day |
| Intraday 1 | 10:00 AM | gas day |
| Intraday 2 | 2:30 PM | gas day |
| Intraday 3 | 7:00 PM | gas day |

Late submissions are rejected with the next cycle still open, so the scheduler knows where to resubmit.

## Pathing rules (`ScheduleBuilder`)

1. Only live `PHYS_GAS` deals flowing on the gas day are scheduled.
2. A buy must sit at a receipt point and a sell at a delivery point; anything else is a warning, not a silent skip.
3. Firm contracts are used before interruptible; within a class, cheapest rate first.
4. Receipt volume is grossed up for fuel so the market gets its full volume: receipt = ⌈delivery ÷ (1 − fuel)⌉.
5. Volumes are whole MMBtu.
6. Leftover supply and market are reported as unmatched; if both remain with no capacity, a capacity-constrained warning is raised.

## Constraint allocation (`allocateConstraint`)

When the pipeline limits a receipt point:
1. Nominations at other points are unaffected.
2. Interruptible service is cut first.
3. If firm alone exceeds capacity, firm is cut pro rata, in whole units, with any rounding remainder going to the largest nominations.

## Imbalances (`ImbalanceCalculator`)

- Imbalance = actual receipt × (1 − fuel) − actual delivery. Positive is **long** (gas left on the pipe), negative is **short**.
- Within the tolerance band (default 5% of scheduled delivery): no charge.
- Outside the band, only the excess is cashed out at index × factor:

| Tier | Size | Long (shipper paid) | Short (shipper pays) |
|---|---|---|---|
| Tier 1 | tolerance – 10% | 0.90 × index | 1.10 × index |
| Tier 2 | > 10% | 0.80 × index | 1.25 × index |

Real pipelines publish their own tariffs; tolerances and factors are constructor parameters so they can be set per pipeline.

## Sample run

The EOD batch (section 12) schedules gas day 2026-11-01 on a fictional pipeline: one 12,000/d supply into two markets, firm and interruptible contracts, a late-submission rejection, a receipt-point constraint that cuts interruptible gas, the resulting re-nomination, and two imbalance scenarios.
