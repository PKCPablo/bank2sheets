# ADR-0010: First sheet as month-tab template

- Status: accepted
- Date: 2026-11-19

## Context

Month tabs are created on demand with a bare `addSheet` request, so every new tab starts empty and gets only the canonical four-column header. The owner formats month tabs by hand (column widths, frozen header row, number formats), and repeating that formatting on every new month tab is manual work. Google Sheets' `duplicateSheet` batchUpdate copies a sheet's values, formulas, formats, column widths, frozen rows/columns, number formats, data validation, conditional formatting, and banded ranges — so a dedicated template sheet can carry the formatting once and every new month tab can inherit it. The plan `docs/ai/plans/2026-11-19_015501-duplicate-month-tabs-from-first-sheet.md` shipped the change (GitHub issue #16).

## Decision

- **The first sheet is always the template.** The template source is the spreadsheet's first sheet in sheet order (index 0), read from the lazy metadata GET. It is not configurable — no env var, CLI flag, or sheet-name setting.
- **Full duplicate.** A missing month tab is created with one `duplicateSheet` batchUpdate (`sourceSheetId` = the first sheet's id, `newSheetName` = the month title); the new tab inherits everything `duplicateSheet` copies: values, formulas, formats, column widths, frozen rows/columns, number formats, data validation, conditional formatting, and banded ranges. No header is appended after duplication: `appendRows` already reads `A1:D1`, a duplicated template header passes the guard and is not rewritten, and a header-less template makes the existing "write header when missing" branch run.
- **Explicit end insertion.** The duplicate request sets `insertSheetIndex` to the current sheet count, so the new tab lands at the end and the template stays first. Omitting it would leave the proto default `0`, inserting each new tab first and pushing the template out of position; the cached sheet count is incremented after every creation (both `addSheet` and `duplicateSheet` paths).
- **Fallback instead of failure.** When the first sheet is itself a month tab (its title parses as `<Spanish month> <year>`) or the `_sync` ledger tab, `sync` does not fail: the tab is created with the existing bare `addSheet` request and the canonical header is written when missing, exactly as before.
- **Ledger exclusion.** `_sync` is never duplicated and never used as a template source; it keeps its hidden `addSheet` + `Tab | TransactionId` schema.
- **Existing tabs are untouched.** Already-existing month tabs are not reformatted, migrated, or re-created; the change only affects tab creation.
- **Metadata widened once.** The lazy metadata GET now reads `?fields=sheets.properties(sheetId,title)` and caches the first sheet's id/title and the sheet count alongside the tab titles; it remains one request per run.

## Alternatives considered

- **Configurable template name** (env var or sheet-name setting): rejected — extra configuration for a decision the owner already controls by sheet order; the first sheet is the natural template position.
- **Header-only copy** (create a bare tab and copy `A1:D1` values): rejected — it would not carry formatting, widths, or frozen rows, which is the point of the template.
- **Fail when the first sheet is a month tab or `_sync`**: rejected — it would break every existing spreadsheet whose first sheet is a month tab; the fallback keeps today's behavior there.
- **Omit `insertSheetIndex`**: rejected — the proto default `0` would insert the new tab first and change which sheet is the template between runs.

## Consequences

- New month tabs inherit the template's formatting without manual work; the owner keeps the template as the first sheet and keeps extra data rows out of it.
- **Template data rows and protections may be copied.** If the template contains sample rows or a protected range, month tabs start with them; the API does not document whether `protectedRanges` carry over. Accepted consequence of the full-duplicate decision; the owner should not protect the template sheet and should keep it to header + formatting only.
- A first sheet with a non-canonical header (e.g. the legacy flat six-column tab) becomes the template and its copies are rejected by the `A1:D1` header guard; the owner must delete/archive it or make it match `Fecha | Movimientos | Descripción | Cuenta` (documented in the README reset paragraph).
- Spreadsheets whose first sheet is a month tab or `_sync` behave exactly as before (bare tab + canonical header).
- No new dependency, CLI command, env var, or public API surface; the metadata GET stays one lazy request per run.

## Validation

- Full suite green with the JaCoCo 80% line gate (119 tests, 0 failures/errors, `BUILD SUCCESS`); new unit tests lock the duplicate body (`sourceSheetId`, `newSheetName`, `insertSheetIndex`), the month-tab/`_sync` fallbacks, and the `duplicate sheet failed` error path.
- Real-spreadsheet verification (owner, pending): make the first tab a formatted template with the canonical header, run `sync`, and check the new month tab matches the template's values/formats/widths/frozen rows/validations with the template still first and unchanged; a second run appends 0. Also verify the fallback by moving a month tab to the first position. Each sync spends one bank access, so run at most twice.

## References

- Plan: `docs/ai/plans/2026-11-19_015501-duplicate-month-tabs-from-first-sheet.md`
- Issue: GitHub issue #16
- Invariants: `docs/ai/architecture.md` (month-tab creation and legacy-flat-tab bullets)
- Related: `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md` (four-column header contract and `A1:D1` guard, unchanged)
- Related: `docs/ai/decisions/ADR-0009-warning-only-protection-for-closed-month-tabs.md` (closed-month protection, unchanged; do not protect the template)
- User docs: `README.md` "Google Sheets layout"
