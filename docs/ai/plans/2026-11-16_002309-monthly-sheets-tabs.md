---
status: completed
created: 2026-11-16
task: 3/3
required_gates:
  architect_review: false
  memory_update: true
---

# Monthly Google Sheets tabs implementation plan

**Goal:** Route every synced transaction to a `YYYY-MM` tab derived from `Transaction.madeOn()`, creating the tab on demand and keeping sync idempotent per month tab.

**Architecture:** `Main.sync()` groups fetched transactions by `YearMonth` and calls month-scoped `GoogleSheetsClient` operations. The client lazily loads the spreadsheet's sheet titles once per run, creates a missing month tab with `spreadsheets.batchUpdate` / `addSheet`, then reads `'YYYY-MM'!E2:E` for dedupe and appends to `'YYYY-MM'!A1:F` with the existing six-column header/`RAW` behavior. The legacy flat first tab is left untouched.

**Tech Stack:** Java 21, Maven (JUnit 5, Gson, JaCoCo 80% line gate), PowerShell wrapper `.\scripts\mvn.ps1`.

**Scope classification:** `multi-task` (3 tasks: Sheets writer, sync grouping, docs/memory)

## Discovery (verified in the repository, 2026-11-16)

- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:47-49` hard-codes unqualified `A1:F`, `A1:F1`, `E2:E`; `:77-114` read global ids from `E2:E` and append to the first sheet; `:231-239` builds `valuesUrl`/`appendUrl` without sheet qualification. `updateValues` (`:224-229`) is the only `PUT` path and exists solely for the legacy `F1 = "Cuenta"` backfill.
- `src/main/java/bank2sheets/Main.java:218-252` performs one global `existingTransactionIds()` read, fetches all accounts, dedupes into one `Set<String>`, and appends one flat `List<SheetRow>`.
- `src/main/java/bank2sheets/model/Transaction.java:13-18` carries `LocalDate madeOn`; `Main.java:240` already writes it as ISO `YYYY-MM-DD`.
- `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:226-235,281-283` resolves `booking_date` → `value_date` → `transaction_date` and throws when all are absent, so `madeOn()` is always non-null for the only provider. No fallback tab is needed.
- `src/main/java/bank2sheets/sheets/SheetRow.java:24-26` keeps the six-value row order `Fecha, Descripción, Importe, Moneda, TransactionId, Cuenta`; month tabs must keep it unchanged.
- Test seams: `src/test/java/bank2sheets/http/FakeHttp.java:34-41` records method/URL/body and stubs by path+query, so quoted ranges, `?fields=...`, and `:batchUpdate` are all testable without a new fake. `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:35-236` and `src/test/java/bank2sheets/MainCommandsTest.java:31-43,142-239,294-322,408-414` pin the current unqualified single-sheet URLs and must be retargeted.
- Real transport `src/main/java/bank2sheets/http/RealHttpExchange.java:19` uses `URI.create(url)`; `'` and `!` are RFC 3986 sub-delims, legal in a path segment, so quoted A1 ranges need no percent-encoding (and must not be passed through `URLEncoder`).
- Docs to update: `README.md:124-132` (first sync), `README.md:251-257` (Google Sheets layout), `docs/ai/architecture.md:48-53` (invariants), `AGENTS.md` project overview, `docs/ai/README.md` status, `docs/ai/logs/2026-11.md`.
- Baseline: `git status --short` clean at `14a9328`; validation command `.\scripts\mvn.ps1 test` (JUnit + JaCoCo 80% line gate).

## Decisions

1. **Tab naming:** `YYYY-MM` via `YearMonth.toString()` (e.g. `2026-11`). ASCII, sortable, identical to the routing key, no locale/month-name formatting. This follows the issue recommendation; changing it later touches one helper plus test fixtures.
2. **Legacy flat tab:** leave untouched — no rename, no migration, no deletion. Consequence to document: its rows are not part of the month-tab dedupe keys, so the first `sync` after upgrading may append whatever history the provider still returns into month tabs. The owner may delete/archive the legacy tab manually before the first upgraded sync; automating that is out of scope.
3. **Dedupe scope:** read only the month tabs targeted by the current batch (one `E2:E` read per distinct month) plus an in-run `seen` set. Bound API calls; do not read all tabs.
4. **Dates:** `madeOn()` is guaranteed non-null by the Enable Banking parser; derive the tab with `YearMonth.from(madeOn())`, never from the run date. No empty/unknown-date fallback.
5. **Sheet existence:** one lazy `GET spreadsheets/{id}?fields=sheets.properties.title` per client instance/run, cached in memory; on a miss, `POST spreadsheets/{id}:batchUpdate` with `addSheet`. Reuse the existing per-tab header logic (write header when A1 is not `Fecha`; backfill only `F1` when a five-column header pre-exists).
6. **A1 ranges:** always quote the title: `'2026-11'!A1:F`, `'2026-11'!A1:F1`, `'2026-11'!E2:E`, `'2026-11'!F1`. Month titles are generator-controlled (`YearMonth`), so no apostrophe-escaping logic is added (YAGNI).
7. **Client API:** replace the flat `existingTransactionIds()` / `appendRows(List<SheetRow>)` with `existingTransactionIds(YearMonth)` / `appendRows(YearMonth, List<SheetRow>)`; no dual API, so no caller can accidentally keep writing to the first sheet.
8. **Output:** keep the sync summary text unchanged (`fetched N ... appended N ... skipped N`).
9. **Gates:** no architect review — this stays inside the existing `GoogleSheetsClient` seam and adds no dependency/config. Memory update is required.

## Tasks

### Task 1: Scope the Sheets writer to month tabs

**Objective:** `GoogleSheetsClient` resolves/creates a `YYYY-MM` tab, reads its ids, and appends to it with the existing header and `RAW` semantics.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:34-114,203-239`
- Validate: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`

**Step 1: Establish expected behaviour** (owner: agent)

- `existingTransactionIds(YearMonth month)` ensures the month tab exists, then reads `'YYYY-MM'!E2:E` and parses column E as today.
- The first call to any month operation loads `sheets[].properties.title` once; a title already present causes no `addSheet`; a missing title causes exactly one `addSheet` for that month and is cached for the rest of the run.
- `appendRows(month, rows)` ensures the tab exists, writes the six-column header when A1 is missing/not `Fecha`, backfills only `F1 = "Cuenta"` when a five-column header exists, and appends rows to `'YYYY-MM'!A1:F` with `valueInputOption=RAW` and amounts as JSON numbers.
- `appendRows(month, List.of())` performs no HTTP calls (no metadata GET, no tab creation).
- Errors keep the `Google Sheets <action> failed (HTTP <status>)` shape with actions `metadata`, `add sheet`, `read`, `append`, `update`.

**Step 2: Add or update validation** (owner: agent)

In `GoogleSheetsClientTest`:

- Replace the single-sheet id test with `existingTransactionIdsForExistingMonthReadsQuotedRange`: metadata `{"sheets":[{"properties":{"title":"2026-08"}}]}` at `/sheet-1?fields=sheets.properties.title`, ids at `/sheet-1/values/'2026-08'!E2:E`; assert the set, exactly one metadata request, and no `:batchUpdate`.
- Add `existingTransactionIdsForMissingMonthCreatesSheetThenReads`: metadata only `Hoja 1`; stub `/sheet-1:batchUpdate`; assert one POST whose JSON body equals `{"requests":[{"addSheet":{"properties":{"title":"2026-11"}}}]}` (parse, do not compare raw key order), followed by the quoted read; call a second month and assert the metadata GET stayed at one and a second `addSheet` names the second month.
- Retarget `appendRowsWritesHeaderThenDataWithAmountsAsNumbers` to `appendRows(YearMonth.of(2026,8), ...)` with quoted `'2026-08'!A1:F1`/`!A1:F:append` stubs; keep header-body and numeric-amount assertions.
- Retarget `appendRowsSkipsHeaderWhenAlreadyPresent`, `appendRowsBackfillsCuentaHeaderOnLegacyFiveColumnSheet` (assert `PUT .../values/'2026-08'!F1?valueInputOption=RAW`), `appendRowsSkipsBackfillWhenCuentaHeaderIsPresent`, and `appendRowsWithNoRowsDoesNothing` (assert `http.requests.isEmpty()`).
- Update token-caching and failure tests to the month overload; add `nonSuccessMetadataThrows` (`"metadata failed"`) and `nonSuccessCreateSheetThrows` (`"add sheet failed"`).
- Keep `invalidServiceAccountBase64Fails`/`serviceAccountWithoutCredentialsFails` unchanged except the month argument.

**Step 3: Write minimal implementation** (owner: agent)

- Add `import java.time.YearMonth;`, `HashSet`/`Set` state `sheetTitles` + `sheetTitlesLoaded`, and range suffix constants (`"!A1:F"`, `"!A1:F1"`, `"!E2:E"`); drop the flat `RANGE`/`HEADER_RANGE`/`IDS_RANGE` constants.
- `public Set<String> existingTransactionIds(YearMonth month)`: `String sheet = month.toString(); ensureSheet(sheet);` return ids parsed from `readValues(quoted(sheet) + "!E2:E", accessToken())`.
- `public void appendRows(YearMonth month, List<SheetRow> rows)`: return early on empty; `ensureSheet(month.toString())`; move the current header/backfill/append body into private helpers that take the quoted sheet prefix (e.g. `readHeaderRow(token, sheetPrefix)`, `appendValues(token, sheetPrefix, body)`, `appendUrl(sheetPrefix)`).
- `private void ensureSheet(String title)`: on first use, `GET API_BASE + "/" + config.spreadsheetId() + "?fields=sheets.properties.title"` and collect titles; on miss, `POST API_BASE + "/" + config.spreadsheetId() + ":batchUpdate"` body `{"requests":[{"addSheet":{"properties":{"title":"<title>"}}}]}`; add to the cache.
- Add `private static String quoted(String sheet) { return "'" + sheet + "'"; }`; keep `valuesUrl` unencoded except for the new quoted range, and update the class Javadoc (`README`-level layout: one tab per `YYYY-MM`).
- Do not touch `SheetRow`, the header values, `HEADER_VALUES`, auth/JWT code, or `Main` in this task.

**Step 4: Run test to verify pass** (owner: agent)

```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: client tests pass with quoted ranges and create-on-demand assertions; full suite green with JaCoCo line coverage >= 80%.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java
git commit -m "feat: write synced rows to monthly sheet tabs (issue #8)"
```

### Task 2: Group sync by month and dedupe per month tab

**Objective:** `sync` sends each fetched transaction to its `madeOn()` month, creates tabs as needed, and stays idempotent across single- and multi-month runs.

**Files:**
- Modify: `src/main/java/bank2sheets/Main.java:210-252`
- Validate: `src/test/java/bank2sheets/MainCommandsTest.java:31-43,142-239,294-322,408-414`

**Step 1: Establish expected behaviour** (owner: agent)

- Fetch first, then group all accounts' transactions into a `TreeMap<YearMonth, List<SheetRow>>`; the tab comes from `YearMonth.from(transaction.madeOn())`, never `LocalDate.now()`.
- Per month: read that tab's existing ids, filter new rows, and append only those rows; months with zero new rows cause no append HTTP call.
- Same-run duplicates are removed by a global `seen` id set before grouping, preserving today's cross-account dedupe.
- Re-running `sync` skips ids already present in each month tab and reports `appended 0 new row(s), skipped N duplicate(s)`.
- `sync` with a Sheets read failure exits 1 with the existing `sync:` prefix. Note: the failure now happens after provider fetch, so tests must stub the session/account/tab metadata before the failing read.

**Step 2: Add or update validation** (owner: agent)

In `MainCommandsTest`:

- Replace `stubReadySheet` with helpers: `stubSpreadsheetTitles(sheets, titles...)` at `/sheet-1?fields=sheets.properties.title` and `stubMonthSheet(sheets, "2026-08")` for header, empty ids, and quoted append.
- Retarget `enableBankingSyncAppendsThenIsIdempotent` to the `2026-08` tab: collect the appended id from the quoted append body, stub `/sheet-1/values/'2026-08'!E2:E` with it, and keep the second-run summary assertion.
- Retarget `enableBankingSyncLabelsRowsPerAccountAndStaysIdempotent` and `enableBankingSyncFallsBackToAccountIdWhenNameBlank` to month stubs; keep label assertions (column index 5).
- Add `enableBankingSyncCreatesOneTabPerMonthAndRoutesRows`: one account, two transactions with `booking_date` `2026-07-31` and `2026-08-01`; metadata contains only `Hoja 1`; stub `/sheet-1:batchUpdate` to return `{"spreadsheetId":"sheet-1"}`; assert two distinct `addSheet` bodies, two quoted appends, and that each append contains exactly the row whose `Fecha` matches its tab. Stub both months' `E2:E` with the appended ids and run sync again; assert `appended 0 new row(s), skipped 2 duplicate(s)` and no additional `:batchUpdate` requests.
- Add `enableBankingSyncSkipsAppendForAllDuplicateMonth`: ids pre-stubbed for the month; assert no `:append` request and the skip summary.
- Update `syncWithSheetsErrorExitsNonZero`: add `stubSession`, `stubAccountDetails`, and `stubSpreadsheetTitles(sheets, "2026-08")` (so the tab already exists and no `addSheet` stub is needed) before the `'2026-08'!E2:E` 403 stub; keep the `Google Sheets read failed` assertion.
- Leave the missing-config, missing-connection, and fetch tests unchanged.

**Step 3: Write minimal implementation** (owner: agent)

- In `sync()`: keep `sheetsClient = sheets.get()`, `source`, and `accountNames(source)`; replace the flat `existing` set and `toAppend` list with `Map<YearMonth, List<SheetRow>> byMonth = new TreeMap<>()` and `Set<String> seen = new HashSet<>()`.
- Inside the per-account loop: `fetched++`; `if (!seen.add(transaction.id())) continue;`; compute `YearMonth month = YearMonth.from(transaction.madeOn())`; add the six-column `SheetRow` via `byMonth.computeIfAbsent(month, m -> new ArrayList<>())`.
- After fetching: iterate `byMonth` in key order; `Set<String> existing = sheetsClient.existingTransactionIds(month);` build `List<SheetRow> fresh` with `existing.add(row.transactionId())`; call `sheetsClient.appendRows(month, fresh)`; accumulate `appended`.
- Print the unchanged `sync: fetched X transaction(s), appended Y new row(s), skipped Z duplicate(s)` line with `Z = fetched - appended`.
- Update the `sync()` Javadoc: destination tab is the transaction's calendar month, dedupe is per month tab (plus in-run id dedupe), tabs are created on demand.
- No change to `fetch()`, `connect()`, provider code, or configuration.

**Step 4: Run test to verify pass** (owner: agent)

```powershell
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: CLI tests prove multi-month routing, on-demand creation, per-month idempotency, and the existing labels/fallbacks; full suite green with the 80% line gate.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/MainCommandsTest.java
git commit -m "feat: group sync by month tab (issue #8)"
```

### Task 3: Document the monthly layout and refresh memory

**Objective:** README, architecture, `AGENTS.md`, and `docs/ai` memory describe month tabs, per-month dedupe, on-demand creation, and the untouched legacy tab without contradicting the current six-column/`Cuenta` contract.

**Files:**
- Modify: `README.md:124-132,251-257`
- Modify: `docs/ai/architecture.md:48-53`
- Modify: `AGENTS.md` (project overview)
- Modify: `docs/ai/README.md` (Repository Status)
- Modify: `docs/ai/logs/2026-11.md`
- Modify: this plan's frontmatter (`status`, `task`)

**Step 1: Establish expected behaviour** (owner: agent)

- README "Google Sheets layout" states: one tab per `YYYY-MM` derived from `Fecha`/`madeOn()`; each tab has `Fecha, Descripción, Importe, Moneda, TransactionId, Cuenta`; the tab and header are created on demand; `RAW` and numeric amounts are unchanged; dedupe is per month tab; `Cuenta` semantics and one-instance-per-account guidance are unchanged.
- README explains the legacy flat first tab is left untouched and not migrated; its rows are not dedupe keys, so the first upgraded sync can re-append whatever the provider still returns — delete/archive it manually first if unwanted.
- README "First sync" no longer implies a single first sheet and mentions month tabs.
- `architecture.md` invariants: idempotency is per month tab; tabs are created on demand; the legacy flat tab is not migrated; the fixed columns/`Cuenta`/`RAW` invariants remain.
- `AGENTS.md` overview says transactions are appended idempotently to a per-month tab; no new config/dependency is implied.
- No secrets, `.env` values, state ids, or personal spreadsheet ids appear in docs.

**Step 2: Add or update validation** (owner: agent)

```powershell
Select-String -Path README.md,AGENTS.md,docs/ai/architecture.md -Pattern 'YYYY-MM|month tab|per month|per-month'
Select-String -Path README.md,docs/ai/architecture.md -Pattern 'first sheet'
.\scripts\mvn.ps1 test
```

Expected: the first search finds the monthly layout and dedupe invariant; the second finds no live claim that sync targets the first sheet (historical logs/plans are exempt); full suite green with the JaCoCo gate.

**Step 3: Write minimal implementation** (owner: agent)

- Update the three live docs as described; keep the existing `Cuenta`/multi-account and `RAW` paragraphs, editing only what the monthly layout changes.
- Add the `docs/ai/README.md` status sentence (issue #8, plan path, month-tab behavior, legacy-tab caveat).
- Add a `docs/ai/logs/2026-11.md` entry titled for issue #8 with shipped behavior, decisions, and the verification command/results.
- Set the frontmatter to `status: completed`, `task: 3/3` only after the full suite and doc searches pass.

**Step 4: Run test to verify pass** (owner: agent)

```powershell
.\scripts\mvn.ps1 test
git status --short
```

Expected: all tests pass, JaCoCo >= 80%, only intended docs/tests/source files changed, no `.env` or state file.

**Step 5: Commit**

```powershell
git add README.md AGENTS.md docs/ai/architecture.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-16_002309-monthly-sheets-tabs.md
git commit -m "docs: document monthly sheet tabs (issue #8)"
```

## Validation summary

| Validation | Owner | Command / procedure | Expected |
| --- | --- | --- | --- |
| Sheets writer unit tests | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest' '-Djacoco.skip=true'` | quoted ranges, lazy metadata, addSheet, header, empty no-op, error paths pass |
| CLI sync tests | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest' '-Djacoco.skip=true'` | multi-month routing, on-demand tabs, per-month idempotency, labels/fallback pass |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` after each task | BUILD SUCCESS, JaCoCo line coverage >= 80% |
| Docs consistency | agent | `Select-String` commands in Task 3 Step 2 | monthly layout documented; no stale "first sheet" claim |
| Real spreadsheet smoke test | human (owner) | Against a disposable spreadsheet: run `sync` with transactions spanning two months, then run it again; confirm one tab per month with the six-column header, rows in the right tab, and `appended 0 new row(s)` on the second run | recommended before closing issue #8; not a code gate |

## Acceptance criteria mapping

- Multiple months create one tab each and route rows by month: Task 2 (grouping) + Task 1 (`ensureSheet`/quoted append).
- Header written exactly once per tab: Task 1 (existing header logic, retargeted tests).
- Double sync appends no duplicates, including mixed months: Task 2 (per-month `E2:E` + in-run `seen`).
- Tabs created automatically and existing tabs reused: Task 1 (lazy metadata + `addSheet`; Task 2 stubs cover both).
- Row format, `RAW`, numeric amounts, `TransactionId` key preserved: Task 1 (unchanged `SheetRow`/`HEADER_VALUES`).
- Month selected from `madeOn()`, not run date: Task 2 implementation + multi-month test.
- `.\scripts\mvn.ps1 test` green with JaCoCo gate: Tasks 1-3 Step 4.
- README/architecture/AGENTS describe the layout and per-month dedupe: Task 3.
- No secrets/state committed: file lists in all tasks.

## Out of scope / follow-ups

- Backfilling, migrating, renaming, or deleting the legacy flat first tab (owner-manual only).
- Reading all month tabs for dedupe, or dedupe across tabs when a provider changes a transaction's date between runs (known limitation: the transaction may be appended again to the new month tab).
- Tab formatting (freeze rows, date cells, widths), tab ordering, or localized month names.
- Per-account or per-month spreadsheet routing, new environment variables, new dependencies.
- Provider/`Transaction` parsing changes, `fetch` output changes, or scheduled-workflow changes.

## Risks

- Sheets API eventual consistency immediately after `addSheet` could make the first values read/append fail; tests cover ordering only. If observed in the owner smoke test, add one retry around the post-create read (follow-up, not planned).
- The upgrade changes dedupe keys from one flat `E2:E` to per-month `E2:E`; historical flat-sheet rows are ignored, so the first post-upgrade sync can duplicate them into month tabs (Decision 2, documented).
- A provider-side date correction moves an existing transaction to a new month tab and re-appends it there; accepted per Decision 3.
- One extra spreadsheet metadata GET per run; cached, so it does not grow with the number of months.
- Existing tests that stub the unqualified ranges must be retargeted; a missed stub surfaces as a 404 in `FakeHttp`, which fails loudly rather than silently passing.
