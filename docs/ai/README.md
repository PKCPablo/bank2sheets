# AI Memory Index

This directory is a **router**, not a summary corpus. Read this file first, then load only the smallest relevant memory file.

Do **not** load all `docs/ai/**` by default.

## Repository Status

Greenfield repo (`bank2sheets`): no application code yet — OpenCode workflow tooling is bootstrapped. Confirmed direction: Open Banking → Google Sheets transaction sync, scheduled via GitHub Actions, Java 21 (ADR-0002), no frontend/database, credentials never stored. Providers: Salt Edge (BBVA) now; Powens (ex Budget Insight) for Trade Republic later. See `architecture.md` for boundaries and `logs/2026-08.md` for context.

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
