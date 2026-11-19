# AI Memory Index

This directory is a **router**, not a summary corpus. Read this file first, then load only the smallest relevant memory file.

Do **not** load all `docs/ai/**` by default.

## Repository Status

v1 MVP is implemented and validated: Enable Banking restricted production is the only provider, Sheets sync is idempotent per month tab (rows go to Spanish month tabs like `Enero 2026`, created on demand, appended in ascending date order; each tab has four columns `Fecha, Movimientos, Descripción, Cuenta` with `Fecha` as a date-typed cell written via `USER_ENTERED` (ISO input, display per sheet locale; legacy text-date rows are left untouched, mixed tabs accepted), and dedupe runs on exact `TransactionId` via a hidden `_sync` ledger tab; a one-time owner reset of legacy tabs is required), multi-account rows carry `Cuenta`, and monthly GitHub Actions sync is verified. Test suite green; JaCoCo 80% line gate met. Latest change: same-date rows in month tabs are written chronologically (oldest movement first) — `Main.sync` reverses each account's fetched list before month grouping because the Enable Banking provider is newest-first (observed, not contractual; if the direction changes the reversal must be revisited), while `appendRows` keeps its stable `Fecha`-ascending sort and `fetch` keeps raw provider order (recorded via `plans/2026-11-19_105115-provider-intraday-order.md` / GitHub issue #22 and the ADR-0007 same-day-ordering amendment). Earlier: a missing month tab is created as a full duplicate of the spreadsheet's first sheet when that first sheet is a dedicated template (neither a month tab nor `_sync`), inserted at the end so the template stays first; when the first sheet is a month tab or `_sync`, creation falls back to a bare `addSheet` tab with the canonical header (recorded via `plans/2026-11-19_015501-duplicate-month-tabs-from-first-sheet.md` / GitHub issue #16 and `decisions/ADR-0010-first-sheet-as-month-tab-template.md`). Earlier: `Descripción` shows the transaction concept — when the provider text contains `//` segments, only the last non-blank trimmed segment is written; text without `//` is verbatim; an all-blank split falls back to the full text — while dedupe ids still hash the raw provider description, so existing `_sync` ids stay valid with no reset (recorded via `plans/2026-11-18_225513-description-concept-normalization.md` / GitHub issue #21). Earlier: the visible amount column is renamed `Importe` → `Movimientos` — the header guard expects `Movimientos` as the second cell, no legacy alias or migration exists, and pre-rename tabs with the old `Importe` header are rejected and require the owner reset (recorded via `plans/2026-11-18_210831-rename-importe-column-to-movimientos.md` / GitHub issue #20 and a dated amendment in `decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md`). Earlier: new `sync` rows carry a real date cell in `Fecha` while `Descripción`/`Cuenta` stay literal text and the amount column (now `Movimientos`) stays numeric (visible rows `USER_ENTERED`, header/`_sync` `RAW`), recorded via `plans/2026-11-18_182437-date-typed-fecha-cells.md` / GitHub issue #18 and a dated amendment in `decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md`. Earlier: on each `sync`, closed month tabs (month strictly before the run month) are protected as whole sheets with warning-only protection (idempotent, service-account appends unaffected), recorded in `decisions/ADR-0009-warning-only-protection-for-closed-month-tabs.md` via `plans/2026-11-17_211424-protect-closed-month-tabs.md`. Earlier still: the ASPSP rate limit (~4 accesses/24 h) and its `429`/`[HUB046]` recovery rule are documented, and the scheduled sync gained an automatic next-day retry cron (`0 6 2 * *`) via `plans/2026-11-16_094002-aspsp-rate-limit-recovery.md`; durable decision recorded as a dated amendment in `decisions/ADR-0005-bbva-ais-provider.md`.

Details live in `architecture.md`, `decisions/`, `plans/`, and `logs/`; this status changes only when the project status changes, not for every feature.

## Topic Routes

- Planning current work: `plans/<active-plan>.md`
- Repo boundaries/contracts: `architecture.md`
- Durable decisions/tradeoffs: `decisions/*.md`
- Shared task workflow context: `backlog/README.md`
- Historical continuity: `logs/YYYY-MM.md`

## Low-token Load Rules

1. Read this index first.
2. Load one route file at a time.
3. Stop when you have enough context to proceed safely.

## What To Persist

- Approved plans and phase status
- Architecture boundaries/invariants
- Durable decisions (ADRs)
- Short monthly logs

## What Not To Persist

- Full transcripts
- Raw diffs
- Long terminal output
- Ephemeral notes or temporary assumptions

## Ownership

- `planner` is the final memory owner.
- Other agents may propose updates, but `planner` decides what gets persisted.
