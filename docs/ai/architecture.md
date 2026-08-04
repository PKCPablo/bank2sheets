# Architecture

## Scope

`bank2sheets` is a personal application that uses Open Banking to automatically fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. It runs periodically via GitHub Actions and never stores bank credentials. The architecture is deliberately simple and extensible so future features (automatic expense categorization, statistics, export to other formats) can be added.

## Stack (ADR-0002)

- Language: Java 21 LTS (Temurin). Java 25 LTS is an acceptable future upgrade.
- Build: Maven single module + `maven-shade-plugin` → executable fat JAR (`java -jar bank2sheets.jar`).
- No application framework (no Spring Boot). Plain Java.
- HTTP: JDK `java.net.http.HttpClient`; JSON: Jackson.
- Google Sheets: official `google-api-services-sheets` v4 + `google-api-client`; auth with a Google service account (the spreadsheet is shared with its email). No interactive OAuth.
- Testing: JUnit 5 (minimum).
- Execution: GitHub Actions (`ubuntu-latest`, `actions/setup-java` Temurin 21, cron). All secrets in Actions secrets.
- Open Banking providers: Salt Edge for BBVA (v1, free production tier). Powens (ex Budget Insight) is the only aggregator verified for Trade Republic and is deferred until needed. GoCardless/Nordigen is not viable for new users (stopped onboarding, enterprise-only).

## Boundaries

- Never store or commit bank credentials or long-lived access tokens; all sensitive values live in GitHub Actions secrets at runtime.
- Scheduled, headless execution via GitHub Actions is the primary run mode.
- Read source: Open Banking (PSD2) account transaction APIs. Write target: Google Sheets.
- Bank access is isolated behind a `TransactionSource` interface; the provider is selected per account via configuration. v1 implements only Salt Edge; adding Trade Republic later means adding a Powens implementation without touching the rest of the pipeline.
- Keep a small pipeline shape (fetch → transform → sync) with room to add categorization/statistics/export stages later.
- No web frontend and no database unless a concrete requirement forces one.
