---
status: completed
created: 2026-11-19
task: 1/1
required_gates:
  architect_review: true
  memory_update: true
---

# First sheet as month-tab template Implementation plan

**Goal:** New month tabs are created as a full duplicate of the spreadsheet's first sheet (values, formulas, formats, widths, frozen rows, validations, conditional formatting) when that first sheet is a dedicated template, and fall back to today's bare `addSheet` + canonical header when the first sheet is a month tab or the hidden `_sync` ledger (GitHub issue #16).

**Architecture:** The whole change lives in `GoogleSheetsClient`'s tab-creation path. The existing lazy spreadsheet-metadata GET is widened from `sheets.properties.title` to `sheets.properties(sheetId,title)` so the client can also cache the first sheet's id/title and the sheet count. `createSheet` keeps `addSheet` for the hidden `_sync` ledger and for the fallback case, and sends one `duplicateSheet` batchUpdate for eligible month tabs, inserted at the end (`insertSheetIndex = sheet count`) so the template stays first. The existing `A1:D1` header guard and the "write header when missing" path in `appendRows` stay untouched and remain the safety net.

**Tech Stack:** Java 21, Gson (existing), JDK `HttpClient` via `HttpExchange`, JUnit 5 with the in-memory `FakeHttp`, JaCoCo 80% line gate. No new runtime dependency, no new CLI command, no new env var.

**Scope classification:** `single-task`

Rationale: one reviewable deliverable — the tab-creation behavior plus the tests and memory updates that describe it. The metadata-field, creation-branch, and test-support edits are inseparable (a partial change leaves the metadata cache inconsistent), and splitting docs from code would create an artificial second task.

## Decisions (from clarification)

- **Template source:** always the first sheet of the spreadsheet, in sheet order (index 0). Not configurable — no env var, CLI flag, or sheet-name setting.
- **Copy scope: full duplicate.** The new month tab inherits everything `duplicateSheet` copies: values, formulas, formats, column widths, frozen rows/columns, number formats, data validation, conditional formatting, and banded ranges.
- **Fallback instead of failure:** when the first sheet is itself a month tab (title parses as `<Spanish month> <year>`) or the `_sync` ledger tab, `sync` does **not** fail; it creates a normal bare tab and appends the canonical header, exactly as today.
- **Ledger exclusion:** `_sync` is never duplicated and never used as a template source; it keeps its hidden `addSheet` + `Tab | TransactionId` schema.
- **Existing tabs:** already-existing month tabs are not reformatted, migrated, or re-created.
- **Insert position:** duplicate at the end of the spreadsheet (`insertSheetIndex = current sheet count`). Omitting `insertSheetIndex`/leaving the proto default `0` would insert the new tab first and push the template out of position, so it must be explicit.
- **No header append after duplication:** `appendRows` already reads `A1:D1`; a duplicated template header passes the guard and is not rewritten, while a header-less template makes the existing "write header when missing" branch run.

## Current facts (verified)

- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:181-191` (`ensureSheet`) loads titles once and calls `createSheet(title, false)` on a miss; `:215-236` (`createSheet`) always sends one `addSheet` batchUpdate with `properties.title` (plus `hidden: true` for `_sync`).
- `GoogleSheetsClient.java:193-213` (`ensureSheetTitles`) reads `?fields=sheets.properties.title` and caches only a `Set<String>` (`:92-93`). It does not keep order, ids, or the first sheet's identity.
- `GoogleSheetsClient.java:315-323` (`ensureHelperSheet`) creates `_sync` through the same `createSheet` and then appends `HELPER_HEADER` (`Tab`, `TransactionId`).
- `GoogleSheetsClient.java:154-166` reads `!A1:D1` and either appends `HEADER_VALUES` (`Fecha, Movimientos, Descripción, Cuenta`, `:76-78`) under `RAW` or fails fast on a mismatch; `:145-178` is the only visible-row append path.
- `GoogleSheetsClient.java:299-313` (`parseTabTitle`) recognizes month tabs and returns `null` for anything else — reuse it for template eligibility instead of adding a new title check.
- Sheets API `DuplicateSheetRequest` accepts `sourceSheetId`, `insertSheetIndex`, `newSheetId`, `newSheetName`; the operation "duplicates the contents of a sheet" (values, formulas, formatting, grid data) and documented behavior does not state whether protected ranges are copied (flagged as a risk).
- Test seams: `src/test/java/bank2sheets/http/FakeHttp.java:34-41` keys stubs by path+query; `src/test/java/bank2sheets/sheets/SheetsTestSupport.java:55-66` stubs metadata and `:52-53` holds the shared `HEADER_JSON`.
- Metadata-path references that must change: `SheetsTestSupport.java:65`, `GoogleSheetsClientTest.java:50,57,92,338,350,364,378`.
- `src/test/java/bank2sheets/MainCommandsTest.java:289-362` asserts "three distinct `addSheet` bodies: two months plus the hidden `_sync` ledger"; after this change the two month tabs are `duplicateSheet` requests and only `_sync` is `addSheet`.
- `docs/ai/plans/2026-11-16_002309-monthly-sheets-tabs.md` introduced the current `addSheet` behavior; `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md` owns the four-column/`_sync` contract; no ADR currently covers how month tabs come into existence.

## Tasks

### Task 1: Create missing month tabs by duplicating the first sheet (with bare-tab fallback)

**Objective:** A missing month tab is a full copy of the first sheet when that first sheet is a dedicated template, and a bare `addSheet` tab with the canonical header when the first sheet is a month tab or `_sync`; tests and memory describe the new behavior.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:37-58,76-95,181-236`
- Modify: `src/test/java/bank2sheets/sheets/SheetsTestSupport.java:51-66`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:38-100,336-359`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java:319-330`
- Create: `docs/ai/decisions/ADR-0010-first-sheet-as-month-tab-template.md`
- Modify: `README.md:253,255`
- Modify: `docs/ai/architecture.md:53,57`
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/logs/2026-11.md` (new dated entry)
- Validate: the test files above and `.\scripts\mvn.ps1 test`

**Step 1: Establish expected behaviour**

- Spreadsheet whose first sheet is a dedicated template (e.g. `Plantilla`, `Hoja 1`) with the four-column header: creating a missing month tab sends one `duplicateSheet` batchUpdate with `sourceSheetId` = the first sheet's id, `newSheetName` = the month title (e.g. `Noviembre 2026`), and `insertSheetIndex` = the current number of sheets. No `addSheet` is sent for that month, and the header is not re-appended because the copied `A1:D1` already matches.
- First sheet is a month tab (`parseTabTitle(title) != null`) or `_sync`: creation uses the existing `addSheet` request; `appendRows` then writes the canonical header because `A1:D1` is empty.
- `_sync` creation is unchanged: hidden `addSheet`, then `Tab | TransactionId` header under `RAW`.
- A duplicated tab whose header does not match the canonical four cells still fails fast in `appendRows`; no rows are appended to it.
- Idempotency and existing tabs are unchanged: the second `sync` appends zero rows, no page is reformatted, and `protectCompletedMonths` is untouched.

**Step 2: Add or update validation**

`SheetsTestSupport`:
- Replace the metadata path with a shared constant, e.g. `SHEET_METADATA_PATH = "/sheet-1?fields=sheets.properties(sheetId,title)"`, and make `stubSheetTitles` emit ordered sheet ids (for example `sheetId: index`) so tests can assert `sourceSheetId`.
- Keep `stubExistingMonth` and other helpers working through the updated `stubSheetTitles`.

`GoogleSheetsClientTest`:
- Update every `?fields=sheets.properties.title` path/assertion (`:50,57,92,338,350,364,378`) to the new constant.
- Update `existingTransactionIdsForMissingMonthCreatesSheetThenReads`: with metadata `Hoja 1` (id 0) the month request is now `duplicateSheet` (`sourceSheetId: 0`, `newSheetName: "Noviembre 2026"`, `insertSheetIndex: 1`); `_sync` remains the only `addSheet` with `hidden: true`; second month (`Diciembre 2026`) duplicates with `insertSheetIndex: 2`.
- Add `createsMonthTabByDuplicatingFirstSheet`: first sheet `Plantilla` id 7 plus `_sync`; assert the duplicate body and that no `addSheet` body carries the month title.
- Add `fallsBackToBareTabWhenFirstSheetIsAMonthTab`: metadata first sheet `Octubre 2026`; assert `addSheet` with the new month title (today's behavior).
- Add `fallsBackToBareTabWhenFirstSheetIsTheSyncLedger`: metadata first sheet `_sync`; assert `addSheet` with the new month title.
- Keep `appendRowsRejectsLegacySixColumnHeader` unchanged to prove the guard still protects a duplicated-with-wrong-header tab.
- Add `nonSuccessDuplicateSheetThrows` if the duplicate-specific error action is asserted (500 on `:batchUpdate` for a template-eligible creation; message contains `duplicate sheet failed` and the status).

`MainCommandsTest`:
- `enableBankingSyncCreatesOneTabPerMonthAndRoutesRows`: replace the "three addSheet bodies" assertion with one `addSheet` (`_sync`, title from `addSheet.properties.title`) plus two `duplicateSheet` bodies whose `newSheetName` values are `Julio 2026` and `Agosto 2026` and whose `sourceSheetId` is the stubbed first sheet (`Hoja 1`).
- The second-run assertion (batchUpdate count stays 3) and all month-tab header/append stubs stay valid; tests whose first sheet is a month tab keep exercising the fallback without extra stubs.

**Step 3: Write minimal implementation**

`GoogleSheetsClient`:
- Change the metadata fields expression to `sheets.properties(sheetId,title)` (comment it as "first sheet id/title + titles").
- Extend the cached state: keep `sheetTitles` and add `firstSheetId` (int), `firstSheetTitle`, and `sheetCount` (int). Populate them in `ensureSheetTitles` from the `sheets` array: first element = first sheet, `sheetCount = sheets.size()`.
- Add a constant for the metadata suffix and a helper `boolean isTemplateSheet()` that returns `firstSheetId` set, `parseTabTitle(firstSheetTitle) == null`, and `!HELPER_TITLE.equals(firstSheetTitle)`.
- In `createSheet(String title, boolean hidden)`:
  - Build and send `addSheet` as today for `hidden == true` and for `!isTemplateSheet()`.
  - Otherwise build one request `{"duplicateSheet":{"sourceSheetId":<firstSheetId>,"insertSheetIndex":<sheetCount>,"newSheetName":"<title>"}}` and POST it to `:batchUpdate`; use the action label `duplicate sheet` in `requireOk`.
  - After success, `sheetTitles.add(title)` and increment `sheetCount` (both paths).
- Do not add a header write after duplication and do not change `appendRows`, `protectCompletedMonths`, the ledger read/append, or the `A1:D1` guard.
- Update the class Javadoc (`:37-58`) and `ensureSheet` Javadoc (`:181-191`) to describe template duplication, the month-tab/`_sync` fallback, and the explicit end insertion.

Tests/helpers: implement exactly what Step 2 describes; no production API surface change beyond private members.

**Step 4: Run test to verify pass**

Run:
```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'
.\scripts\mvn.ps1 test
Select-String -Path src/main/java/bank2sheets/sheets/GoogleSheetsClient.java -Pattern 'sheets.properties.title'
```

Expected: targeted suites green; full suite green with the JaCoCo 80% line gate (`BUILD SUCCESS`); the last search returns no residual old metadata field expression.

**Step 5: Update memory and docs**

- `README.md` "Google Sheets layout" (`:253`): state that a missing month tab is a full copy of the first sheet when that sheet is a dedicated template, and that a first sheet that is a month tab or `_sync` falls back to a bare tab with the canonical header; keep the rest of the section intact.
- `README.md` reset paragraph (`:255`): add one sentence that a first sheet with a non-canonical header (e.g. the legacy flat six-column tab) becomes the template and its copies are rejected by the header guard, so delete/archive it or make it match `Fecha | Movimientos | Descripción | Cuenta`.
- `docs/ai/architecture.md:53`: extend the "Month tabs are created on demand" invariant with the duplicate path, the fallback eligibility, the explicit end insertion, and the unchanged header-write-on-missing behavior.
- `docs/ai/architecture.md:57`: note that the legacy flat first tab is now also the template source when present, so it must be removed or made header-compatible.
- `docs/ai/README.md:9`: prepend a short "Latest change" clause for the template duplication (issue #16, this plan) and shift the previous latest-change clause to "Earlier".
- Create ADR-0010 recording the durable decision: first sheet is the template, full duplicate, fallback on month-tab/`_sync`, explicit end insertion, `_sync` excluded, existing tabs untouched, and the accepted consequence that template data/protections may be copied.
- `docs/ai/logs/2026-11.md`: add `## 2026-11-19 - Month tabs duplicated from the first sheet` with what changed, the fallback decision, test results, commit hash, and the pending human real-sheet check.

**Step 6: Commit**

```powershell
git add src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/SheetsTestSupport.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/MainCommandsTest.java README.md docs/ai/architecture.md docs/ai/README.md docs/ai/decisions/ADR-0010-first-sheet-as-month-tab-template.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-19_015501-duplicate-month-tabs-from-first-sheet.md
git commit -m "feat: duplicate first sheet as month-tab template (issue #16)"
```

## Validation ownership

| Validation | Owner | Command / procedure | Expected |
|---|---|---|---|
| Sheets client unit suite | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'` | Green; duplicate/fallback/header-guard assertions pass |
| CLI sync suite | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'` | Green; one `addSheet` for `_sync`, two `duplicateSheet` bodies for months, second run appends 0 |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` | `BUILD SUCCESS`, JaCoCo line ratio ≥ 0.80 |
| Docs freshness | agent | `Select-String -Path README.md,docs/ai/architecture.md,docs/ai/README.md -Pattern 'first sheet'` and the metadata-field search above | Live text matches the new contract; no stale `sheets.properties.title` |
| Real-sheet template behavior | human | In the disposable test spreadsheet, make the first tab a formatted template with header `Fecha \| Movimientos \| Descripción \| Cuenta`, run `.\scripts\run.ps1 sync`, open the created month tab | New tab matches the template's values/formats/widths/frozen rows/validations; template still first and unchanged; a second run appends 0. Each sync spends one bank access, so run at most twice |
| Real-sheet fallback | human | Move a month tab to the first position (or point at a spreadsheet whose first tab is a month tab), run `sync` | Sync succeeds and creates a bare tab with the canonical header; no failure |

## Risks and assumptions

- **Template protections may be copied.** The API documents `duplicateSheet` as copying sheet contents/grid data but does not state whether `protectedRanges` carry over. If they do, the copied month tab has a protection the protection pass then skips. Accepted consequence of the full-duplicate decision; the owner should not protect the template sheet. Verify during the human real-sheet check.
- **A legacy flat first tab is now a template source.** It is neither a month tab nor `_sync`, so it is duplicated and its six-column header is rejected by the guard. Documented in the README reset paragraph; the existing reset path already tells the owner to delete it.
- **Full duplicate copies extra data rows.** If the owner's template contains sample rows or formulas, month tabs start with them. This is the confirmed copy scope; keep extra rows out of the template.
- **Insert index must be explicit.** Relying on the proto default (`0`) would insert each new tab at position 0 and change the template between runs. The plan sets `insertSheetIndex` to the current sheet count and increments the cached count after every creation.
- **Named ranges / formulas.** Duplicated formulas and named ranges may reference the template by name; behavior follows Sheets' duplicate semantics and is not adjusted in code.
- **Coverage gate.** The new duplicate branch and cache fields are exercised by the new unit tests; if the gate drops below 80%, add the duplicate-failure test rather than lowering the gate.
- **No new dependency or configuration.** Only Gson and JDK HTTP are used; the metadata GET remains one lazy request per run.

## Required gates

- `architect_review: true` — the change adds a durable tab-creation contract (first sheet as template, full duplicate, fallback) and a new ADR; the architect should confirm it does not conflict with ADR-0008 (four-column/`_sync` ledger), ADR-0009 (closed-month protection), or the "legacy flat first tab is left untouched" invariant.
- `memory_update: true` — `README.md`, `docs/ai/architecture.md`, `docs/ai/README.md`, `docs/ai/decisions/ADR-0010-...md`, and `docs/ai/logs/2026-11.md` are updated in Task 1.
