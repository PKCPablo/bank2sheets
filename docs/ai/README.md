# AI Memory Index

This directory is a **router**, not a summary corpus. Read this file first, then load only the smallest relevant memory file.

Do **not** load all `docs/ai/**` by default.

## Repository Status

v1 MVP is implemented and validated: Enable Banking restricted production is the only provider, Sheets sync is idempotent per month tab (rows go to Spanish month tabs like `Enero 2026`, created on demand, appended in ascending date order; each tab has four columns `Fecha, Importe, Descripción, Cuenta` with `DD/MM/YYYY` text dates, and dedupe runs on exact `TransactionId` via a hidden `_sync` ledger tab; a one-time owner reset of legacy tabs is required), multi-account rows carry `Cuenta`, and monthly GitHub Actions sync is verified. Test suite green; JaCoCo 80% line gate met. Latest change: on each `sync`, closed month tabs (month strictly before the run month) are protected as whole sheets with warning-only protection (idempotent, service-account appends unaffected), recorded in `decisions/ADR-0009-warning-only-protection-for-closed-month-tabs.md` via `plans/2026-11-17_211424-protect-closed-month-tabs.md`. Earlier: the ASPSP rate limit (~4 accesses/24 h) and its `429`/`[HUB046]` recovery rule are documented, and the scheduled sync gained an automatic next-day retry cron (`0 6 2 * *`) via `plans/2026-11-16_094002-aspsp-rate-limit-recovery.md`; durable decision recorded as a dated amendment in `decisions/ADR-0005-bbva-ais-provider.md`.

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
