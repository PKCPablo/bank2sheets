---
status: planned
created: 2026-08-04
phase: 1/3
required_gates:
  test_verdict: false
  architect_review: false
  memory_update: true
---

# Plan: MVP bank2sheets — pipeline local BBVA → Google Sheets

## Goal

Deliver a Java 21 job executable locally that, through a one-time `connect` mode (AIS read-only consent on BBVA via Salt Edge) and a `sync` mode, downloads transactions and syncs them idempotently to Google Sheets. Cron/CI is deferred to a later iteration.

## Questions

Resolved during Discovery (2026-08-04):

- Java 21 LTS confirmed as target.
- Consent restricted to AIS-only (read-only; no PIS/transfers).
- GitHub Actions cron deferred and tracked as pending.

## Assumptions

- Owner will create during implementation: Google Cloud project + service account (JSON as base64), target spreadsheet shared with the service account email, Salt Edge account with `app_id`/`secret`.
- No real credentials in the repository: development uses empty config + test doubles; real values come from environment/secrets.
- Multi-account is handled by configuration (list of accounts + provider per account); v1 implements only BBVA via Salt Edge.
- Repository root: `C:\DEV\bank2sheets`.
- Local build environment (verified 2026-08-04): Maven 3.9.9 runs on JDK 8 by default (`JAVA_HOME` = `C:\Program Files\Java\jdk-1.8`); JDK 21.0.6 is installed at `C:\Program Files\Java\jdk-21.0.6`; `java` on PATH is 22.0.2 (Oracle javapath). Builds must run with `JAVA_HOME=C:\Program Files\Java\jdk-21.0.6`, otherwise `mvn compile` with `release=21` fails.

## Scope Classification

- Size: Medium
- Phase budget: 3
- Phase budget rationale: each phase is an independent reviewable deliverable (build → BBVA read → Sheets sync)
- Quick delivery eligible: No
- Split required: No

## Phases

### Phase 1: Scaffold Maven y build

- Scope: `pom.xml` (Java 21, JUnit 5, `maven-shade-plugin`), structure `bank2sheets.{config,openbanking,sheets,model}`, `Main` with subcommands (`connect`, `sync`, `help`), `.gitignore` (`target/`), README/AGENTS.md with build and run commands.
- Acceptance criteria: `mvn test` passes (smoke test); `mvn package` produces the fat JAR; `java -jar` prints usage without errors; no credentials in the repository.
- Risks: low.

### Phase 2: Lectura BBVA vía Salt Edge (modo `connect` + `fetch`)

- Scope: `TransactionSource` interface + per-account provider config; `SaltEdgeClient` (app_id/secret token, create connection with AIS-only consent and authorization URL, confirm active connection, list accounts, paginated transaction fetch); `Account` and `Transaction` models (amounts as `BigDecimal`); `connect` mode (one time, stores `connection_id` in config/env); `fetch` mode (dumps transactions to JSON/console).
- Acceptance criteria: unit tests with simulated HTTP (no real credentials); `connect` generates the URL and confirms `connection_id`; `fetch` returns transactions for the configured account; owner manual validation: one real BBVA connection (SCA).
- Risks: Salt Edge/BBVA payload variations (normalize into `Transaction`); one-time manual SCA flow.

### Phase 3: Sincronización a Google Sheets + pipeline

- Scope: `GoogleSheetsClient` (service-account auth from env base64 JSON, append rows, read existing `transactionId`s); pipeline in `Main`: config → fetch → transform → sync with dedupe by `transactionId` (idempotency); columns: Fecha, Descripción, Importe, Moneda, TransactionId; exit codes and clear logging; README update with env vars and usage.
- Acceptance criteria: transformation and dedupe tests; owner real sync: BBVA transactions appear in the spreadsheet exactly once; second run does not duplicate; exit code 0 with clear log.
- Risks: Sheets rate limits (batch writes), cell value types (amounts as numbers), date formatting.

## Deferred (pending, tracked in `docs/ai/backlog/`)

- GitHub Actions cron workflow for scheduled sync (explicitly out of scope of this plan; to be implemented later).
- Powens / Trade Republic integration (already deferred in ADR-0002).

## Validation Strategy

- `mvn test` and `mvn package` after each phase.
- HTTP stubs/doubles in tests (no real calls in automated tests).
- Owner manual validation: real BBVA connect (Phase 2) and real sheet sync (Phase 3).
- JUnit 5 tests are part of each phase; no extra test-agent work beyond that.

## Decisions To Record

- Security boundary: AIS-only read consent (never PIS/transfers) → `docs/ai/architecture.md` + ADR-0002 note.
- Pending item registered: cron/CI → plan Deferred section + `docs/ai/backlog/README.md`.
- Java 21 confirmed (already in ADR-0002, no change).

## Implementation Handoff

- Repository root: `C:\DEV\bank2sheets`
- Each phase is handed to `code` with this plan file as the source of truth; phase acceptance criteria must be met before the next phase starts.
- External API references: Salt Edge Bank API v5 docs (https://docs.saltedge.com — AIS endpoints: create connection, list accounts, transactions); Google Sheets API Java quickstart (https://developers.google.com/workspace/sheets/api/quickstart/java).
