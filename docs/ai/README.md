# AI Memory Index

This directory is a **router**, not a summary corpus. Read this file first, then load only the smallest relevant memory file.

Do **not** load all `docs/ai/**` by default.

## Repository Status

Operational repo: v1 MVP pipeline implemented and completed (`plans/2026-08-04-mvp-pipeline-local.md`, phase 3/3). Confirmed direction: Open Banking → Google Sheets transaction sync, on-demand local execution in v1 (scheduled GitHub Actions cron deferred to the backlog), Java 21 (ADR-0002), no frontend/database, credentials never stored. Providers: **Enable Banking restricted production is the primary provider** (ADR-0005, 2026-11-13; adapter implemented in `plans/2026-11-13_171738-enable-banking-adapter.md` Tasks 1-2 — `EnableBankingClient`, `ProviderSources` factory keyed by `BANK2SHEETS_ACCOUNTS`, `ENABLE_BANKING_*` env contract, `enablebanking.session_id` state key, `eb:` transaction-id prefix); Salt Edge V6 remains a selectable fallback (sandbox-validated; real BBVA was blocked there). The Enable Banking adapter is **sandbox-validated** (2026-11-13: `connect`/`fetch`; `sync` twice → 17 then 0 rows); **production validation is pending**: create the production app, link the real BBVA account, and run the real `connect`/`fetch`/`sync`-twice flow against the disposable test spreadsheet (GitHub issue #3). Scheduled GitHub Actions sync remains deferred and is tracked in issue #4. Architecture deliberately informal — no formal hexagonal (ADR-0003). Dev ergonomics: `.\scripts\mvn.ps1` (JDK 21+ wrapper that keeps an active JDK 21+ `JAVA_HOME` or auto-detects the highest installed `jdk-21*`), `.\scripts\run.ps1` (`.env` runner), `.editorconfig` LF, and JaCoCo 80% line-coverage gate on `mvn test` (ADR-0004). See `architecture.md` for boundaries, `logs/2026-08.md` for original context, and `logs/2026-10.md` for the 2026-10 onboarding refresh.

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
