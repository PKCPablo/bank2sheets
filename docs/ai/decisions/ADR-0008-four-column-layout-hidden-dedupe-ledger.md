# ADR-0008: Four-column month tabs with DD/MM/YYYY dates and a hidden `_sync` dedupe ledger

- Status: accepted
- Date: 2026-11-16

## Context

Month tabs previously carried six columns (`Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`, `Cuenta`), with dedupe reading ids from the visible `E2:E` range of each month tab. The owner asked for a cleaner visible layout: only the human-relevant columns, with the transaction id kept out of the sheet. Idempotency must stay exact (provider `TransactionId`), and dates should read naturally in Spanish format. The plan `docs/ai/plans/2026-11-16_013743-four-column-sheet-layout-hidden-dedupe-ledger.md` shipped the change (Tasks 1-2: commits `c80cce4`, `cc827f0`).

## Decision

- Every month tab has exactly four columns: `Fecha`, `Importe`, `Descripción`, `Cuenta` (ranges `!A1:D` / `!A1:D1`). The visible `TransactionId` and `Moneda` columns are gone.
- `Fecha` is written as `DD/MM/YYYY` text (`valueInputOption=RAW` keeps it literal, not a date-typed cell). Batch ordering sorts on the underlying `LocalDate`, never on the formatted string.
- Dedupe moves to a hidden helper tab `_sync` (created on demand with a hidden `addSheet`) holding `Tab | TransactionId` pairs. One `'_sync'!A:B` read per client run; ids are appended to `'_sync'!A2:B` only after the visible month-tab append succeeds. The visible append always precedes the ledger append.
- A tab whose existing header does not match the four expected cells is rejected with a `Bank2SheetsException` carrying the reset instruction; no header is appended to a legacy tab and no in-place migration runs. The owner resets before the first post-upgrade sync: delete/archive every existing Spanish month tab **and** the `_sync` tab (or point `GOOGLE_SPREADSHEET_ID` at a fresh spreadsheet). The reset is safe because `EnableBankingClient.fetch` pages all `BOOK` transactions with no date window, so provider history is fully re-fetched.
- The writer is append-only: the `PUT`/`updateValues` path (previously used only for the `F1 = "Cuenta"` backfill) is deleted.

**Partial supersession of ADR-0007:** the manual rename path (ADR-0007 Decision bullet 3, Consequences bullet 1) and the `!A1:F` range example (Decision bullet 2) are void — a renamed tab keeps its six-column header and is now rejected by the header guard. What still stands from ADR-0007: the Spanish tab title as persistence key, no auto-rename, batch-ascending order, and the manual per-year spreadsheet switch.

## Alternatives considered

- **Fingerprint dedupe** (hash of row contents): rejected — provider `TransactionId` is already stable and unique (`eb:`-prefixed); a fingerprint is lossier and harder to reason about.
- **Keep the visible `TransactionId` column**: rejected — the owner wanted the id out of the visible sheet; the hidden ledger preserves exact idempotency without it.
- **In-place migration of existing six-column tabs** (rewrite headers and drop columns): rejected — the writer is append-only by design and never rewrites existing rows; a one-time owner reset is simpler and safe because provider history is fully reproducible.

## Consequences

- The sheet gains a hidden `_sync` tab; owners see it in the spreadsheet UI (it is hidden, not deleted).
- A one-time reset is required before the first sync of this version; a legacy tab makes `sync` fail fast with the reset instruction. The reset must be **full**: delete `_sync` only together with every month tab (or use a fresh spreadsheet). Deleting only the month tabs while keeping `_sync` silently skips every row as an already-recorded duplicate; deleting only `_sync` is safe but may re-append duplicates.
- If the ledger append fails after the visible append, the run throws (exit 1) and a later run may duplicate rows — accepted over losing rows.
- `Moneda` is no longer persisted anywhere in the sheet; currency survives only in the `fetch` JSON output.
- No date-typed cells: `Fecha` is literal `DD/MM/YYYY` text, so Sheets date locale/format settings never affect it.
- The writer is append-only (`PUT` removed); future changes must not reintroduce row rewrites without superseding this ADR.

## Validation

- Full suite: 95 tests, 0 failures/errors; JaCoCo 80% line gate met.
- Docs checks: no stale six-column/`E2:E`/rename claims remain in `README.md`, `AGENTS.md`, or `docs/ai/architecture.md`; the four-column header, `DD/MM/YYYY`, `_sync`, and `Cuenta` are documented.
- The real-spreadsheet smoke test (full reset, `sync` twice → four-column tabs, `DD/MM/YYYY` dates, hidden `_sync`, second run `appended 0 new row(s)`) remains a pending human validation, not a code gate.

## References

- Plan: `docs/ai/plans/2026-11-16_013743-four-column-sheet-layout-hidden-dedupe-ledger.md`
- Supersedes (partially): `docs/ai/decisions/ADR-0007-spanish-month-tab-persistence-key.md`
- Invariants: `docs/ai/architecture.md` (month-tab layout, `_sync` ledger, legacy-header rejection)
- User docs: `README.md` "Google Sheets layout"
