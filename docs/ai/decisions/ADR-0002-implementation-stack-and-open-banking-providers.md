# ADR-0002: Implementation stack and Open Banking provider strategy

- Status: accepted
- Date: 2026-08-04

## Context

`bank2sheets` is a personal Open Banking → Google Sheets transaction sync application, scheduled headless via GitHub Actions, with no frontend, no database, and credentials never stored. The owner's primary language is Java and the repository is expected to stay small — at most syncing several bank accounts, currently one (BBVA), with Trade Republic as a possible future addition.

Key research facts (2026-08-04):

- Google Sheets API v4 has official client libraries for Java.
- GoCardless/Nordigen — previously the obvious free aggregator — stopped onboarding new customers in 2025; its open banking API is now enterprise-only. Rejected.
- BBVA (Spain) is supported by ~18 API aggregators. Salt Edge offers a documented free production tier (100 live connections) with self-service signup and an official Java SDK.
- Trade Republic is reachable through exactly one verified aggregator: Powens (formerly Budget Insight). Direct PSD2 access to Trade Republic requires a registered TPP license (eIDAS QWAC, costly) and is infeasible for personal use.
- No single free aggregator covers both BBVA and Trade Republic.

## Decision

- Language: **Java 21 LTS (Temurin)**. Java 25 LTS (longer support runway) and Python/Node.js (less boilerplate) were considered; Java wins because the owner's familiarity minimizes the dominant maintenance cost of a small personal project.
- Build: **Maven**, single module, `maven-shade-plugin` → executable fat JAR (`java -jar bank2sheets.jar`).
- No application framework (no Spring Boot). Plain Java.
- HTTP: JDK `java.net.http.HttpClient`; JSON: **Jackson** (`jackson-databind`).
- Google Sheets: official `google-api-services-sheets` v4 + `google-api-client`; auth via **Google service account** (`google-auth-library-oauth2-http`) — headless-friendly, the spreadsheet is shared with the service account email. No interactive OAuth.
- Testing: **JUnit 5** (minimum).
- Execution: GitHub Actions (`ubuntu-latest`, `actions/setup-java` Temurin 21, cron). All sensitive values live in Actions secrets (provider credentials, service account JSON as base64, spreadsheet id).
- Open Banking provider v1: **Salt Edge** for BBVA (free production tier, 100 live connections — ample for personal use).
- Future provider for Trade Republic: **Powens (formerly Budget Insight)** — the only aggregator with a verified Trade Republic connection. Deferred until needed; re-check coverage before implementing.
- Design consequence: the pipeline exposes a `TransactionSource` interface; the provider is selected per account via configuration. v1 implements only Salt Edge. This is the minimum abstraction required by the stated BBVA-now / Trade Republic-later path, not speculative.

## Consequences

- `docs/ai/architecture.md` updated with the concrete stack.
- Provider isolation keeps the pipeline stable when adding Powens (or another aggregator) later.
- During implementation the owner must create a Google service account, enable the Sheets API, and share the target spreadsheet with the service account email.
- Salt Edge free tier limit: 100 live connections; exceeding it requires the Growth plan (~$500/month) — monitor, unlikely for personal use.
- GoCardless/Nordigen is documented as not viable for new users, superseding any earlier recommendation.
- Security boundary (confirmed with owner): consent is AIS-only (read-only: account details, balances, transactions). PIS / transfers are never requested.

## Implementation note (2026-08-04, MVP pipeline delivered)

- JSON library refined from Jackson to **Gson 2.11.0** (smallest stable lib, zero transitive deps) — implementation choice, ponytail: avoid dependency weight for a two-API personal app.
- Google Sheets access refined: no official `google-api-services-sheets` / `google-api-client` dependency — stdlib `HttpClient` + stdlib RS256 service-account JWT (RFC 7523) + Gson. Same auth model (service account shared with the spreadsheet), far smaller dependency tree.
- Env var name: `GOOGLE_SERVICE_ACCOUNT_B64` (base64 service-account JSON) — supersedes the earlier `GOOGLE_SERVICE_ACCOUNT_JSON_BASE64` draft.
- Execution in v1 is on-demand locally; GitHub Actions cron stays deferred (backlog).
