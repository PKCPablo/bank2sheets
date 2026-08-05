# Architecture

## Scope

`bank2sheets` is a personal application that uses Open Banking to automatically fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. It runs periodically via GitHub Actions and never stores bank credentials. The architecture is deliberately simple and extensible so future features (automatic expense categorization, statistics, export to other formats) can be added.

## Stack (ADR-0002)

- Language: Java 21 LTS (Temurin). Java 25 LTS is an acceptable future upgrade.
- Build: Maven single module + `maven-shade-plugin` → executable fat JAR (`java -jar bank2sheets.jar`).
- No application framework (no Spring Boot). Plain Java.
- HTTP: JDK `java.net.http.HttpClient`; JSON: Gson (smallest stable lib, no transitive deps; ponytail decision over Jackson).
- Google Sheets: no official client library — stdlib `java.net.http.HttpClient` + stdlib RS256 service-account JWT (RFC 7523) + Gson. Auth with a Google service account (the spreadsheet is shared with its email). No interactive OAuth.
- Testing: JUnit 5 (minimum).
- Execution: on-demand locally in v1 (`connect` / `fetch` / `sync` CLI). GitHub Actions cron is deferred to the backlog (see `docs/ai/backlog/README.md`). All secrets in Actions secrets when CI lands.
- Open Banking providers: Salt Edge for BBVA (v1, free production tier). Powens (ex Budget Insight) is the only aggregator verified for Trade Republic and is deferred until needed. GoCardless/Nordigen is not viable for new users (stopped onboarding, enterprise-only).

## Configuration / Environment (contract)

Sensitive and per-run values come exclusively from the environment; never commit them.

- `SALTEDGE_APP_ID` — Salt Edge application id (secret)
- `SALTEDGE_SECRET` — Salt Edge application secret (secret)
- `SALTEDGE_CUSTOMER_ID` — Salt Edge customer that owns the connection (required for `connect`)
- `SALTEDGE_CONNECTION_ID` — active BBVA connection id; optional once `connect` has run (persisted to the gitignored `bank2sheets-state.properties`)
- `BANK2SHEETS_ACCOUNTS` — comma-separated `provider:accountId` entries to fetch, e.g. `saltedge:ACCT_ID`
- `GOOGLE_SERVICE_ACCOUNT_B64` — Google service-account key JSON, base64-encoded (secret)
- `GOOGLE_SPREADSHEET_ID` — target spreadsheet id

Local build note: Maven 3.9.9 defaults to JDK 8 (`JAVA_HOME` = `C:\Program Files\Java\jdk-1.8`); use `JAVA_HOME=C:\Program Files\Java\jdk-21.0.6` (installed) for Java 21 builds.

## Boundaries

- Never store or commit bank credentials or long-lived access tokens; all sensitive values live in GitHub Actions secrets at runtime.
- Scheduled, headless execution via GitHub Actions is the primary run mode.
- Read source: Open Banking (PSD2) account transaction APIs. Write target: Google Sheets.
- Bank access is isolated behind a `TransactionSource` interface; the provider is selected per account via configuration. v1 implements only Salt Edge; adding Trade Republic later means adding a Powens implementation without touching the rest of the pipeline.
- Bank access is read-only by design: only PSD2 AIS consent types are requested (account details, balances, transactions); PIS (payment initiation / transfers) is never requested.
- Keep a small pipeline shape (fetch → transform → sync) with room to add categorization/statistics/export stages later.
- No web frontend and no database unless a concrete requirement forces one.
