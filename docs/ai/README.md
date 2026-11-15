# AI Memory Index

This directory is a **router**, not a summary corpus. Read this file first, then load only the smallest relevant memory file.

Do **not** load all `docs/ai/**` by default.

## Repository Status

v1 MVP is implemented and validated: Enable Banking restricted production is the only provider, Sheets sync is idempotent, multi-account rows carry `Cuenta`, and monthly GitHub Actions sync is verified. Test suite green; JaCoCo 80% line gate met.

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
