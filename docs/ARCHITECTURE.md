# Architecture

## Goals

1. **Testable without the platform.** Endur environments are shared, slow to refresh and expensive. Business logic should be provable with unit tests on a laptop.
2. **Upgrade-resilient.** Vendor API changes between Endur versions should touch one adapter, not every rule and report.
3. **Fail safe.** In a trading system a defect must stop a bad trade, never wave it through.

## Layers

```
┌──────────────────────────────────────────────────────────────────────┐
│ app            EodBatchRunner (task / workflow entry point)          │
├──────────────────────────────────────────────────────────────────────┤
│ lifecycle      TradeLifecycleService  ── ops (rule chain)            │
│ valuation      ValuationEngine, PnlExplain                           │
│ risk           PositionReport, HistoricalVaR, CreditExposure         │
│ settlement     CashflowGenerator, InvoiceService                     │
│ interfaces     TradeFeedParser, ConfirmationWriter, Dispatcher,      │
│                GlPostingExtract, PositionReconciliation              │
├──────────────────────────────────────────────────────────────────────┤
│ domain / market   pure data + logic, no I/O                          │
├──────────────────────────────────────────────────────────────────────┤
│ adapter        EndurSession  ◄── InMemoryEndurSession (tests/local)  │
│                              ◄── production adapter (vendor API)     │
└──────────────────────────────────────────────────────────────────────┘
```

## The `EndurSession` boundary

Everything that would touch the platform — loading and saving transactions, party and security static data, user tables, audit — is behind `EndurSession`. A production implementation would:

| `EndurSession` call | Platform equivalent |
|---|---|
| `save`, `current`, `history`, `query` | Transaction retrieval/insert via the trading factory; queries via saved queries or SQL against `ab_tran` |
| `nextDealTrackingNum`, `nextTranNum` | Assigned by the platform on insert (in-memory impl simulates) |
| `party`, `parties` | Party / business unit static data, credit limits from the credit module or a user table |
| `authorizedPortfolios` | Personnel–portfolio security assignments |
| `insertUserTableRow`, `userTable`, `updateUserTableRows` | User tables (`USER_*`) |
| `audit` | Plugin logging / a custom audit user table |

## Mapping to Endur plugin types

| Toolkit component | Typical Endur deployment |
|---|---|
| `TradeOpsRule` + `OpsServiceEngine` | Trading pre-process Operation Service plugin; each rule is configured as a definition or composed in one plugin |
| `TradeLifecycleService.postProcess` | Trading post-process ops service writing to an event user table |
| `TradeEventDispatcher` | Scheduled task or connex/interface engine job reading the event table |
| `ValuationEngine` | Custom sim result / UDSR, or a validation harness for vendor sim results |
| `EodBatchRunner` | EOD workflow of chained tasks |
| `TradeFeedParser` | Inbound interface (file drop, MQ, or ETP API) |

## Design decisions

- **Deal versioning.** `dealTrackingNum` is stable; every amendment gets a new `tranNum` and version. History is never overwritten, which audit and confirmations rely on.
- **Transitions are data.** `TranStatus.allowedNext()` is the single source of truth; the lifecycle service refuses anything else before rules run.
- **Rules are tiny and ordered.** Cheap structural checks run first; valuation-heavy credit checks last. Each rule is a class with its own tests.
- **Exceptions in rules = BLOCK.** `OpsServiceEngine` converts any rule exception into a blocking result with the error text, so operations see why.
- **Batch isolation.** `ValuationEngine.valueAll` collects per-deal failures instead of aborting EOD.
- **Idempotent interfaces.** The dispatcher marks event rows processed only after the document is produced; re-running after a failure is safe.
- **No runtime dependencies.** Avoids classpath conflicts inside a vendor JVM.
