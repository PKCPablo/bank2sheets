# Architecture

## Scope

`bank2sheets` is a personal application that uses Open Banking to automatically fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. In v1 it runs on demand locally (`connect` / `fetch` / `sync`); scheduled sync also runs via `.github/workflows/sync.yml` (monthly on the 1st at 06:00 UTC, `0 6 1 * *` + `workflow_dispatch`, Actions secrets, never runs `connect` on the ephemeral runner). Verification is pending the owner adding the secrets (issue #4). It never stores bank credentials. The architecture is deliberately simple and extensible so future features (automatic expense categorization, statistics, export to other formats) can be added.

## Stack (ADR-0002)

- Language: Java 21 LTS (Temurin). Java 25 LTS is an acceptable future upgrade.
- Build: Maven single module + `maven-shade-plugin` → executable fat JAR (`java -jar bank2sheets.jar`).
- No application framework (no Spring Boot). Plain Java.
- HTTP: JDK `java.net.http.HttpClient`; JSON: Gson (smallest stable lib, no transitive deps; ponytail decision over Jackson).
- Google Sheets: no official client library — stdlib `java.net.http.HttpClient` + stdlib RS256 service-account JWT (RFC 7523) + Gson. Auth with a Google service account (the spreadsheet is shared with its email). No interactive OAuth.
- Testing: JUnit 5 (minimum).
- Execution: on-demand locally (`connect` / `fetch` / `sync` CLI) plus scheduled sync via `.github/workflows/sync.yml` (monthly on the 1st at 06:00 UTC, `0 6 1 * *` + `workflow_dispatch`; Actions secrets; never runs `connect` — the ephemeral runner uses `ENABLE_BANKING_SESSION_ID` from secrets). Verified 2026-11-14 via `workflow_dispatch` (issue #4 closed).
- Open Banking providers: the primary provider is **Enable Banking restricted production** (free, personal use, own linked accounts; ADR-0005, 2026-11-13), implemented behind the `TransactionSource` seam and selected by the `ProviderSources` factory. The Enable Banking sandbox was validated 2026-11-13 (`connect`/`fetch`; `sync` twice → 17 then 0 rows) and production real BBVA on 2026-11-14 (`fetch` 171 transactions; `sync` twice → 171 then 0 rows). Salt Edge V6 is a **discarded option** (ADR-0005); its fallback code was removed in GitHub issue #7 (it was sandbox-validated with a fake BBVA provider, but real BBVA was blocked there — `bbva_es` absent from the Service key catalog). Powens (ex Budget Insight) is the only aggregator verified for Trade Republic and is deferred until needed. GoCardless/Nordigen is not viable for new users (stopped onboarding, enterprise-only).

## Configuration / Environment (contract)

Sensitive and per-run values come exclusively from the environment; never commit them.

- `ENABLE_BANKING_APP_ID` — Enable Banking application id (JWT `kid`) (secret)
- `ENABLE_BANKING_PRIVATE_KEY_B64` — base64-encoded PKCS#8 PEM private key for the Enable Banking application (secret)
- `ENABLE_BANKING_REDIRECT_URL` — registered redirect URL: `http` loopback for sandbox (`127.0.0.1`/`localhost`, explicit port, non-empty path) or `https` for production (e.g. the GitHub Pages callback page; the CLI prompts for the pasted callback URL)
- `ENABLE_BANKING_SESSION_ID` — active Enable Banking session id; optional once `connect` has run (persisted to the gitignored `bank2sheets-state.properties`)
- `ENABLE_BANKING_ASPSP_NAME` — optional ASPSP name; default `BBVA` (trimmed; must match the ASPSP name shown in Enable Banking)
- `ENABLE_BANKING_ASPSP_COUNTRY` — optional ASPSP country; default `ES` (ISO 3166 alpha-2, uppercased)
- `ENABLE_BANKING_PSU_TYPE` — optional PSU type; default `personal` (lowercased)
- `BANK2SHEETS_ACCOUNTS` — comma-separated `provider:accountId` entries to fetch, e.g. `enablebanking:<uuid>`; unsupported or unknown providers fail fast; all entries must use one provider per invocation
- `GOOGLE_SERVICE_ACCOUNT_B64` — Google service-account key JSON, base64-encoded (secret)
- `GOOGLE_SPREADSHEET_ID` — target spreadsheet id

Enable Banking contract: Open Banking Gateway API (`https://api.enablebanking.com`), RS256 JWT auth built from `ENABLE_BANKING_APP_ID` + the private key (`kid` = app id, TTL ≤ 1 h). Free restricted-production mode: personal, non-commercial use only, and only the accounts the owner links in the Enable Banking control panel are accessible; ASPSP rate limits apply and sessions need re-authorisation (fresh redirect/SCA) when they expire. The `/aspsps` lookup and `/auth` flow are parameterized by the three optional ASPSP variables, with BBVA/ES/personal as defaults (BBVA Spain is the validated target; other ASPSPs are configurable but not owner-validated). `connect` uses an `http` loopback listener for sandbox redirects (bound before printing the auth URL) and a paste prompt for `https` production redirects (the `docs/callback.html` GitHub Pages page shows the `code`/`state`). Transaction ids are prefixed `eb:` (Enable Banking) so they stay stable and unique in the sheet.

Local build note (machine-specific, revalidated 2026-11-14): Maven 3.9.9 defaults to JDK 8 (`JAVA_HOME` = `C:\Program Files\Java\jdk-1.8`). `scripts/mvn.ps1` keeps an active JDK 21+ `JAVA_HOME` and otherwise discovers a `jdk-21*` installation under `C:\Program Files\Java` (here Temurin 21.0.12 at `C:\Program Files\Java\jdk-21`), failing with a clear message when none exists.

## Boundaries

- Never store or commit bank credentials or long-lived access tokens; sensitive values come only from the environment (Actions secrets for the scheduled workflow).
- Execution is on-demand local (`connect` / `fetch` / `sync`); scheduled headless sync runs via `.github/workflows/sync.yml` (verified 2026-11-14 via `workflow_dispatch`, issue #4 closed).
- Read source: Open Banking (PSD2) account transaction APIs. Write target: Google Sheets.
- Bank access is isolated behind a `TransactionSource` interface; the provider is selected per invocation by the `ProviderSources.create(AppConfig)` factory, keyed by the provider in `BANK2SHEETS_ACCOUNTS` (one provider per run). When `BANK2SHEETS_ACCOUNTS` is empty, `connect` infers the provider from credentials (Enable Banking) and prints the available account ids/names after consent; `fetch`/`sync` still require accounts. Enable Banking is the only supported provider; unsupported providers fail fast. Adding Trade Republic later means adding a Powens implementation without touching the rest of the pipeline.
- Bank access is read-only by design: only PSD2 AIS consent scopes are requested (`accounts` — covers accounts and balances — and `transactions`); PIS (payment initiation / transfers) is never requested.
- Keep a small pipeline shape (fetch → transform → sync) with room to add categorization/statistics/export stages later.
- Isolation strategy is deliberately informal ports-and-adapters seams, not a formal hexagonal architecture (ADR-0003): bank access behind `TransactionSource` (adding Powens = new implementation, no pipeline changes), HTTP behind `HttpExchange` (tests inject fakes), Google Sheets behind `GoogleSheetsClient` (factory-created), and `Main` as the composition root. Revisit only on concrete pain — second provider → provider-selection factory keyed by config; pipeline growth → extract a use case out of `Main`.
- No web frontend and no database unless a concrete requirement forces one.

## Invariants

- Sync is idempotent: existing rows are deduped by `TransactionId`, so a repeated `sync` appends no duplicates.
- The first sheet has fixed columns `Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`, `Cuenta`; the header is written once when missing. A legacy five-column sheet gets only `F1 = "Cuenta"` backfilled on the next sync that appends rows; historical rows keep the column blank.
- Every appended row carries a `Cuenta` label: the provider account display name when non-blank, otherwise the provider account id. `BANK2SHEETS_ACCOUNTS` may list multiple accounts and all rows go to one spreadsheet tab; dedupe stays global on `TransactionId`. One bank account per spreadsheet is a deployment pattern achieved by running one instance/fork per account (its own `BANK2SHEETS_ACCOUNTS`, `GOOGLE_SPREADSHEET_ID`, workflow, and secrets) — there is no account-to-spreadsheet routing configuration.
- Amounts are appended with `valueInputOption=RAW`, so they stay numeric cells rather than text or formulas.
- Secrets (Enable Banking app id + private key, Google service-account JSON, spreadsheet id) come only from environment variables; nothing sensitive is committed.
- Consent is AIS-only (`accounts` + `transactions`); PIS/transfers are never requested.
- `bank2sheets-state.properties` holds only the provider state id (`enablebanking.session_id`; no credentials, no bank tokens) and is gitignored. A legacy `saltedge.connection_id` may persist in existing state files; it is never read and no migration runs.
- `mvn test` enforces a JaCoCo 80% line-coverage gate (BUNDLE `LINE/COVEREDRATIO >= 0.80`).
- Java 21 is required (`maven.compiler.release=21`).
