# Production Support Runbook

Common incidents, how to find the root cause, and how to recover safely.

## Triage checklist

1. **Scope**: one deal, one counterparty, one index, or the whole run?
2. **Audit trail**: `session.auditTrail(dealTrackingNum)` — every booking, rule outcome and status change with timestamps and tran numbers.
3. **Versions**: `session.history(dealTrackingNum)` — what changed between versions.
4. **Events**: rows in `USER_TRADE_EVENTS` with `processed = false` show what downstream has not yet seen.
5. **Reproduce locally**: build the deal and market data in a unit test against `InMemoryEndurSession`; rules and valuation behave identically.

## Incidents

### Trade blocked at booking
- Read the `BLOCKED` audit line; it names the rule and the reason.
- *PriceTolerance*: confirm with the trader whether the price is correct (units: $/MMBtu vs ¢/therm, $/MWh). If the curve is stale, fix market data rather than widening tolerance.
- *Counterparty suspended*: credit/KYC decision — do not override in code.
- *TraderAuthorization*: security request for the portfolio assignment.
- *DuplicateTrade*: check whether the feed was replayed; the existing deal number is in the message.

### Valuation errors in EOD
- `BatchResult.errors()` lists each failed deal; the rest of EOD continues.
- `Missing fixing for INDEX MONTH`: the month has delivered but the fixing was not loaded. Load the fixing and re-run valuation only.
- `No forward curve loaded`: curve import failed or the index is new; check the curve file and index mapping.

### Trade feed rejects
- Each rejected row has a line number, external ref and reason; valid rows still load.
- Missing column header fails the whole file intentionally — the source changed its layout.
- After correcting the source, re-send only the rejected rows; `DuplicateTrade` protects against double booking if the whole file is replayed.

### Confirmations not sent
- Check `USER_TRADE_EVENTS` for `processed = false`.
- `TradeEventDispatcher.dispatchPending()` is safe to re-run; documents are keyed by tran number for downstream de-duplication.

### Reconciliation breaks
- Positive difference: we hold more than the broker — look for a booked trade the broker rejected or an amendment not reflected at the broker.
- Negative difference: missed fill or late booking; compare broker trade IDs to `externalRef`.

### Credit breach on report
- `CreditExposure.report` shows net value, exposure and utilization per counterparty. Breaches after booking usually come from market moves; escalate to credit, do not cancel trades.

### GL extract not balanced
- `GlPostingExtract.isBalanced` should always be true; if not, treat as a code defect and stop the GL interface until fixed.

## Safe re-run matrix

| Step | Re-runnable | Notes |
|---|---|---|
| Curve / fixing load | Yes | Replaces values |
| Trade feed | Yes | Duplicates blocked by external ref |
| Validation | Yes | Illegal transitions rejected, no side effects |
| Valuation / risk | Yes | Read-only |
| Confirmations | Yes | Only unprocessed events |
| Settlements / GL | Check | Downstream must de-duplicate on deal + period |
| Maturity | Yes | Only `VALIDATED` deals past end date |
