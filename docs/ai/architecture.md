# Architecture

## Scope

`bank2sheets` is a personal application that uses Open Banking to automatically fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. It runs periodically via GitHub Actions and never stores bank credentials. The architecture is deliberately simple and extensible so future features (automatic expense categorization, statistics, export to other formats) can be added.

Planned stack (confirmed with owner, 2026-08-04): Java. No frontend and no database.

## Boundaries

- Never store or commit bank credentials or long-lived access tokens; all sensitive values live in GitHub Actions secrets at runtime.
- Scheduled, headless execution via GitHub Actions is the primary run mode.
- Read source: Open Banking (PSD2) account transaction APIs. Write target: Google Sheets.
- Keep a small pipeline shape (fetch → transform → sync) with room to add categorization/statistics/export stages later.
- No web frontend and no database unless a concrete requirement forces one.
