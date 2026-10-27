# AI Memory Index

This directory is a **router**, not a summary corpus. Read this file first, then load only the smallest relevant memory file.

Do **not** load all `docs/ai/**` by default.

## Repository Status

Operational repo: v1 MVP pipeline implemented and completed (`plans/2026-08-04-mvp-pipeline-local.md`, phase 3/3). Confirmed direction: Open Banking → Google Sheets transaction sync, on-demand local execution in v1 (scheduled GitHub Actions cron deferred to the backlog), Java 21 (ADR-0002), no frontend/database, credentials never stored. Providers: Salt Edge (BBVA) now; Powens (ex Budget Insight) for Trade Republic later behind `TransactionSource`. Salt Edge uses the **V6 API** with **Service API keys** (App keys don't work in V6); migration `plans/2026-08-05-saltedge-v6-migration.md` is phase 1/2 done, phase 2 (real connect/sync validation) owner-gated. Architecture deliberately informal — no formal hexagonal (ADR-0003). Dev ergonomics: `.\scripts\mvn.ps1` (JDK 21+ wrapper that keeps an active JDK 21+ `JAVA_HOME` or auto-detects the highest installed `jdk-21*`), `.\scripts\run.ps1` (`.env` runner), `.editorconfig` LF, and JaCoCo 80% line-coverage gate on `mvn test` (ADR-0004). See `architecture.md` for boundaries, `logs/2026-08.md` for original context, and `logs/2026-10.md` for the 2026-10 onboarding refresh.

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
