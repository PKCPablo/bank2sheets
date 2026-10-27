# Architecture

## Scope

`bank2sheets` is a personal application that uses Open Banking to automatically fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. In v1 it runs on demand locally (`connect` / `fetch` / `sync`); scheduled GitHub Actions cron is deferred to the backlog (`docs/ai/backlog/README.md`). It never stores bank credentials. The architecture is deliberately simple and extensible so future features (automatic expense categorization, statistics, export to other formats) can be added.

## Stack (ADR-0002)

- Language: Java 21 LTS (Temurin). Java 25 LTS is an acceptable future upgrade.
- Build: Maven single module + `maven-shade-plugin` → executable fat JAR (`java -jar bank2sheets.jar`).
- No application framework (no Spring Boot). Plain Java.
- HTTP: JDK `java.net.http.HttpClient`; JSON: Gson (smallest stable lib, no transitive deps; ponytail decision over Jackson).
- Google Sheets: no official client library — stdlib `java.net.http.HttpClient` + stdlib RS256 service-account JWT (RFC 7523) + Gson. Auth with a Google service account (the spreadsheet is shared with its email). No interactive OAuth.
- Testing: JUnit 5 (minimum).
- Execution: on-demand locally in v1 (`connect` / `fetch` / `sync` CLI); scheduled GitHub Actions cron is deferred to the backlog (see `docs/ai/backlog/README.md`). If CI lands, secrets would move to Actions secrets.
- Open Banking providers: Salt Edge for BBVA (v1, free production tier). Powens (ex Budget Insight) is the only aggregator verified for Trade Republic and is deferred until needed. GoCardless/Nordigen is not viable for new users (stopped onboarding, enterprise-only).

## Configuration / Environment (contract)

Sensitive and per-run values come exclusively from the environment; never commit them.

- `SALTEDGE_APP_ID` — Salt Edge **Service** API key id (`App-id` header) (secret)
- `SALTEDGE_SECRET` — Salt Edge **Service** API key secret (`Secret` header) (secret)
- `SALTEDGE_CUSTOMER_ID` — Salt Edge customer that owns the connection (required for `connect`)
- `SALTEDGE_CONNECTION_ID` — active BBVA connection id; optional once `connect` has run (persisted to the gitignored `bank2sheets-state.properties`)
- `BANK2SHEETS_ACCOUNTS` — comma-separated `provider:accountId` entries to fetch, e.g. `saltedge:ACCT_ID`
- `GOOGLE_SERVICE_ACCOUNT_B64` — Google service-account key JSON, base64-encoded (secret)
- `GOOGLE_SPREADSHEET_ID` — target spreadsheet id

Salt Edge contract: **API V6** (`https://www.saltedge.com/api/v6`). Keys must be **Service API keys** from the Salt Edge dashboard (API Keys page, type Service); App API keys don't work in V6. The `App-id`/`Secret` headers and env var names are unchanged. AIS-only read consent with scopes `accounts` (covers accounts + balance) and `transactions`; PIS is never requested.

Local build note (machine-specific, revalidated 2026-10-27): Maven 3.9.9 defaults to JDK 8 (`JAVA_HOME` = `C:\Program Files\Java\jdk-1.8`). `scripts/mvn.ps1` keeps an active JDK 21+ `JAVA_HOME` and otherwise discovers the highest `jdk-21*` installation under `C:\Program Files\Java` (here Temurin 21.0.12 at `C:\Program Files\Java\jdk-21`), failing with a clear message when none exists.

## Boundaries

- Never store or commit bank credentials or long-lived access tokens; sensitive values come only from the environment (Actions secrets if CI lands later).
- Execution is on-demand local in v1 (`connect` / `fetch` / `sync`); scheduled headless GitHub Actions cron is deferred to the backlog.
- Read source: Open Banking (PSD2) account transaction APIs. Write target: Google Sheets.
- Bank access is isolated behind a `TransactionSource` interface; the provider is selected per account via configuration. v1 implements only Salt Edge; adding Trade Republic later means adding a Powens implementation without touching the rest of the pipeline.
- Bank access is read-only by design: only PSD2 AIS consent scopes are requested (`accounts` — covers accounts and balances — and `transactions`); PIS (payment initiation / transfers) is never requested.
- Keep a small pipeline shape (fetch → transform → sync) with room to add categorization/statistics/export stages later.
- Isolation strategy is deliberately informal ports-and-adapters seams, not a formal hexagonal architecture (ADR-0003): bank access behind `TransactionSource` (adding Powens = new implementation, no pipeline changes), HTTP behind `HttpExchange` (tests inject fakes), Google Sheets behind `GoogleSheetsClient` (factory-created), and `Main` as the composition root. Revisit only on concrete pain — second provider → provider-selection factory keyed by config; pipeline growth → extract a use case out of `Main`.
- No web frontend and no database unless a concrete requirement forces one.

## Invariants

- Sync is idempotent: existing rows are deduped by `TransactionId`, so a repeated `sync` appends no duplicates.
- The first sheet has fixed columns `Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`; the header is written once when missing.
- Amounts are appended with `valueInputOption=RAW`, so they stay numeric cells rather than text or formulas.
- Secrets (Salt Edge keys, Google service-account JSON, spreadsheet id) come only from environment variables; nothing sensitive is committed.
- Consent is AIS-only (`accounts` + `transactions`); PIS/transfers are never requested.
- `bank2sheets-state.properties` holds only the Salt Edge connection id and is gitignored.
- `mvn test` enforces a JaCoCo 80% line-coverage gate (BUNDLE `LINE/COVEREDRATIO >= 0.80`).
- Java 21 is required (`maven.compiler.release=21`).
