# AGENTS.md

## Mission

Use the installed OpenCode workflows to onboard repositories and deliver minimal, reviewed, and documented changes. Staged delivery is the default conservative path; quick delivery handles clear, small, low-risk tasks via `/quick` or proactive planner suggestion.

## AI Memory Index

This file is the practical entrypoint for AI agents working in this repository. For durable memory (architecture, decisions, approved plans, and logs), see `docs/ai/README.md`.

Treat `docs/ai/README.md` as a tiny router. Do not load all memory files by default; use `/memory` (planner) or `memory-maintenance` for low-token lookup/freshness/update routing.

## Repository Guidance

The sections below contain repository-specific guidance. `/onboard-repo` and `research` fill these values during onboarding.

### Project Overview

bank2sheets is a personal application that uses Open Banking to fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. It runs periodically via GitHub Actions, never stores bank credentials, and is designed to stay simple and extensible (future: automatic categorization, statistics, exports). Planned stack: Java, no frontend, no database. See `README.md` and `docs/ai/architecture.md`.

### Build/Test Commands

Maven commands must run with the JDK 21 home set per command; the default `JAVA_HOME` points to JDK 8, which fails `mvn compile` with `release=21`:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.6'; mvn test
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.6'; mvn package
```

`mvn package` produces the runnable fat JAR at `target\bank2sheets-0.1.0-SNAPSHOT.jar`. Run it with `java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar <command>` (commands: `connect`, `fetch`, `sync`, `help`). Runtime config comes from environment variables (`SALTEDGE_APP_ID`, `SALTEDGE_SECRET`, `SALTEDGE_CUSTOMER_ID`, `SALTEDGE_CONNECTION_ID`, `BANK2SHEETS_ACCOUNTS`, and for `sync` `GOOGLE_SERVICE_ACCOUNT_B64`, `GOOGLE_SPREADSHEET_ID`); `connect` persists only the connection id to `bank2sheets-state.properties` (gitignored). See `README.md` for details.

### Key Conventions & Constraints

- Never store or commit bank credentials or long-lived tokens; all sensitive values must live in GitHub Actions secrets at runtime.
- `bank2sheets-state.properties` (runtime state: connection id only) is gitignored; never commit it.
- Keep the architecture minimal: no frontend and no database unless a concrete requirement forces one; avoid speculative abstraction (ponytail reuse-first discipline).
- Use staged delivery for implementation; `/quick` only for small, low-risk changes.
- Keep `docs/ai/` memory routed and current; `planner` is the memory owner.

## Workflow & Engineering Rules

The `planner` (planning) and `delivery` (implementation pipeline) agents are the two primary entry gates. Planner owns discovery, phased plan creation, memory, and shared tasks. Delivery owns phase handoff, code/quickcode invocation, git-based review, and close workflow. See the installed planner and delivery agents for the canonical rulesets.

When pasting an error or log (stack trace, crash dump, exception), prefix with `/triage` so the triage skill loads before any investigation.

Planner enforces a ponytail reuse-first discipline. Use `/ponytail-help` in an installed repo to see available commands and the 7-rung ladder. To disable: `/ponytail off`.
