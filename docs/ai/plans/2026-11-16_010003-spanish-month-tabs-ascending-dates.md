---
status: completed
created: 2026-11-16
task: 2/2
required_gates:
  architect_review: false
  memory_update: true
---

# Spanish month tabs and ascending transaction order

**Goal:** Rename the monthly Google Sheets tabs to Spanish month + year (e.g. `Enero 2026`, `Febrero 2026`) and append each month's new rows in ascending `Fecha` order.

**Architecture:** Keep `YearMonth` as the internal routing key and centralize the display name in `GoogleSheetsClient.tabTitle(YearMonth)`. `ensureSheet` and the quoted A1 ranges use that title; the range path percent-encodes the space (`%20`) because `URI.create` rejects raw spaces. `appendRows` sorts only the incoming batch by `SheetRow.fecha` (ISO `YYYY-MM-DD`) before the append; it does not rewrite existing tab rows. Existing `YYYY-MM` tabs stay untouched and are renamed manually by the owner; one spreadsheet per year is a manual operational switch, documented but not automated.

**Tech Stack:** Java 21, Gson, JUnit 5, Maven/JaCoCo (80% line gate), PowerShell wrapper `.\scripts\mvn.ps1`.

**Scope classification:** `multi-task` (2 tasks: behavior + tests; docs/memory)

## Discovery (verified 2026-11-16)

- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:81-84` builds `sheet = month.toString()` for ids; `:111-112` does the same for appends. `:133-168` `ensureSheet` compares/creates raw titles. `:296-299` `quoted` wraps the title without encoding.
- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:285-294` builds `valuesUrl`/`appendUrl`; `src/main/java/bank2sheets/http/RealHttpExchange.java:19` uses `URI.create(url)`, which rejects a literal space in the path. Spanish titles introduce a space, so the quoted range must encode it.
- `src/main/java/bank2sheets/Main.java:232-265` groups transactions into `TreeMap<YearMonth, List<SheetRow>>` and preserves provider order inside each month; no sort is applied before `appendRows`.
- `src/main/java/bank2sheets/sheets/SheetRow.java:15-26` carries `fecha` as ISO `YYYY-MM-DD` (from `LocalDate.toString()`), so lexicographic ascending order equals chronological order within a month; the six-column `values()` order is unchanged.
- `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:38-90,128-236,300-327` stubs the current `'2026-08'` ranges and asserts `YYYY-MM` addSheet titles; `src/test/java/bank2sheets/MainCommandsTest.java:143-173,175-221,246-315,317-350,420-435,521-541` uses `stubMonthSheet(sheets, "2026-08")` and raw quoted URLs.
- Docs to update: `README.md:132` (first sync), `README.md:251-259` (Google Sheets layout), `AGENTS.md:5` (overview), `docs/ai/architecture.md:50-54` (invariants), `docs/ai/README.md:9` (status), `docs/ai/logs/2026-11.md` (new entry).
- Baseline: `git status --short` clean at `757f1b3`; validation command `.\scripts\mvn.ps1 test`.

## Decisions

1. **Tab title:** explicit Spanish month list, first letter uppercase, space, four-digit year: `Enero 2026` … `Diciembre 2026`. No `Locale`/`DateTimeFormatter` dependency; `YearMonth` remains the routing/dedupe key. Titles are deterministic and locale-independent.
2. **Ordering:** sort only the rows of the current `appendRows` batch by `SheetRow.fecha` ascending. Use a stable sort, so same-day rows keep provider order. Do not read/rewrite existing rows; a late-arriving transaction with an older date can therefore land at the end of the tab (accepted).
3. **URL encoding:** percent-encode spaces in the quoted A1 range (`'Agosto%202026'!A1:F`); keep `'`, `!`, and `:` literal because they are legal URI path characters and the A1 parser needs them. Do not use `URLEncoder` (it would encode `:` and turn spaces into `+`).
4. **Existing `YYYY-MM` tabs:** leave untouched in code. Document a manual rename (`2026-11` → `Noviembre 2026`) to preserve history and per-tab dedupe. No auto-migration.
5. **One spreadsheet per year:** operational/documentation only. The app does not switch spreadsheets; the owner updates `GOOGLE_SPREADSHEET_ID` in `.env` and the Actions secret before the first sync that returns new-year transactions. No new env var, dependency, or routing logic.
6. **Gates:** no architect review (stays inside `GoogleSheetsClient`, no dependency/config boundary change); memory update required.

## Tasks

### Task 1: Use Spanish month titles and sort appended rows

**Objective:** `GoogleSheetsClient` creates/reads/writes tabs titled with Spanish month + year and appends each month's new rows in ascending `Fecha` order, with existing dedupe/header/`RAW` behavior intact.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:35-41,80-125,133-168,285-299`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:38-236,300-327`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java:143-350,420-435,521-541`

**Step 1: Establish expected behaviour** (owner: agent)

- `existingTransactionIds(YearMonth.of(2026, 8))` ensures/reads `'Agosto%202026'!E2:E`; a missing title creates exactly one tab whose `addSheet` body is `{"requests":[{"addSheet":{"properties":{"title":"Agosto 2026"}}}]}`.
- `appendRows(YearMonth.of(2026, 8), rows)` ensures/creates `Agosto 2026`, writes the six-column header when missing, and appends rows sorted by `Fecha` ascending; same-date rows keep input order.
- The append body still has the six columns in order and amounts as JSON numbers; `valueInputOption=RAW` is unchanged.
- `appendRows(month, List.of())` remains a no-op with no HTTP calls.
- Dedupe remains per month tab (`E2:E` + in-run id set); no `Main` behavior change.
- Existing tabs titled `2026-08` are neither found nor modified by the new naming.

**Step 2: Add or update validation** (owner: agent)

In `GoogleSheetsClientTest`:

- Retarget every `'2026-08'` stub/URL to `'Agosto%202026'`; retarget `'2026-11'`/`'2026-12'` expectations to `Noviembre 2026`/`Diciembre 2026` (addSheet bodies and quoted reads).
- Add `appendRowsSortsRowsByDateAscending`: pass rows `2026-08-15`, `2026-08-02` (with distinct ids) and assert the parsed append `values` order is `2026-08-02` then `2026-08-15`.
- Keep the existing header/backfill/no-op/error tests, retargeted to the new encoded range; assert `nonSuccessAppendThrows` still reports `append failed`.
- Assert `appendRowsWithNoRowsDoesNothing` still makes no HTTP requests.

In `MainCommandsTest`:

- Update `stubMonthSheet` to accept a raw title and encode spaces for all three stubs (`A1:F1`, `E2:E`, `A1:F:append`), e.g. `String encoded = title.replace(" ", "%20")`.
- Replace `"2026-08"` calls with `"Agosto 2026"` and `"2026-07"` with `"Julio 2026"`; update direct `E2:E` stubs and URL-suffix assertions to the encoded titles.
- Update `enableBankingSyncCreatesOneTabPerMonthAndRoutesRows` to expect addSheet titles `Set.of("Julio 2026", "Agosto 2026")` and append URLs containing `'Julio%202026'!` / `'Agosto%202026'!`; keep the `Fecha`-prefix routing assertions.
- Add `enableBankingSyncWritesMonthRowsInDateOrder`: one account returns `2026-08-20` before `2026-08-03`; assert the single append body lists `2026-08-03` first and `2026-08-20` second.
- Keep the idempotency, label, fallback, and Sheets-error tests; only their tab titles/URLs change.

**Step 3: Write minimal implementation** (owner: agent)

- Add an explicit `private static final List<String> SPANISH_MONTHS` (`Enero`…`Diciembre`) and `private static String tabTitle(YearMonth month)` returning `SPANISH_MONTHS.get(month.getMonthValue() - 1) + " " + month.getYear()`.
- Replace `month.toString()` in `existingTransactionIds` and `appendRows` with `tabTitle(month)`; keep the raw title for `ensureSheet` and the `addSheet` JSON.
- In `appendRows`, after the empty check, sort the incoming list with `Comparator.comparing(SheetRow::fecha)` and serialize that sorted list; add `import java.util.Comparator`. Do not mutate the caller's list.
- In `quoted`, percent-encode spaces (`sheet.replace(" ", "%20")`) and update its Javadoc to show `'Agosto%202026'!A1:F`. Keep `'`, `!`, `:` literal.
- Update the class Javadoc (Spanish month tab titles, ascending `Fecha` append order) and the `appendRows` Javadoc; leave `HEADER_VALUES`, `SheetRow`, auth/JWT, and `Main` unchanged.

**Step 4: Run test to verify pass** (owner: agent)

```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: client tests prove Spanish titles, encoded ranges, addSheet names, and ascending order; CLI tests prove end-to-end routing/order; full suite green with JaCoCo line coverage >= 80%.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/MainCommandsTest.java
git commit -m "feat: use Spanish month tabs and ascending date order"
```

### Task 2: Document the naming, ordering, rename, and per-year switch

**Objective:** README, `AGENTS.md`, architecture, and AI memory describe Spanish month tabs, ascending row order, manual rename of `YYYY-MM` tabs, and the manual one-spreadsheet-per-year switch without contradicting the six-column/`Cuenta`/`RAW` contract.

**Files:**
- Modify: `README.md:132,251-259`
- Modify: `AGENTS.md:5`
- Modify: `docs/ai/architecture.md:50-54`
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/logs/2026-11.md` (append new entry)

**Step 1: Establish expected behaviour** (owner: agent)

- README "First sync" and "Google Sheets layout" state: one tab per month titled `Enero 2026`, `Febrero 2026`, etc. (Spanish month + year from `Fecha`); tab and header created on demand; rows appended in ascending `Fecha` order (same-day provider order); `RAW`/numeric amounts and `TransactionId` dedupe unchanged.
- README explains existing `YYYY-MM` tabs are not migrated; rename them manually to the Spanish title to preserve history/dedupe, otherwise a new tab is created and provider history may be re-appended.
- README explains one spreadsheet per year is manual: update `GOOGLE_SPREADSHEET_ID` in `.env` and the Actions secret before the first sync that returns new-year transactions (with the monthly cron, before the February 1 run); switching late creates new-year tabs in the old spreadsheet.
- `AGENTS.md` overview names Spanish month tabs and ascending order; no new config/dependency is implied.
- `architecture.md` invariants state the Spanish title helper, ascending append order, no auto-migration of `YYYY-MM` tabs, and the manual per-year spreadsheet switch.
- `docs/ai/README.md` status and `docs/ai/logs/2026-11.md` entry reference this plan and the shipped behavior.
- No secrets, `.env` values, state ids, or personal spreadsheet ids appear in docs.

**Step 2: Add or update validation** (owner: agent)

```powershell
Select-String -Path README.md,AGENTS.md,docs/ai/architecture.md -Pattern 'Enero 2026|Febrero 2026|ascending|%20|one spreadsheet per year|YYYY-MM'
Select-String -Path README.md,docs/ai/architecture.md -Pattern 'named `YYYY-MM`|tab per month, named `YYYY-MM`'
.\scripts\mvn.ps1 test
```

Expected: the first search finds the Spanish naming/order and the manual per-year switch; the second finds no live claim that tabs are named `YYYY-MM` (historical logs/plans exempt); full suite green with the JaCoCo gate.

**Step 3: Write minimal implementation** (owner: agent)

- Update README first-sync sentence and Google Sheets layout with the exact tab examples (`Enero 2026`, `Febrero 2026`), ascending order, manual `YYYY-MM` rename, and manual per-year `GOOGLE_SPREADSHEET_ID` switch.
- Update `AGENTS.md` overview and `docs/ai/architecture.md` invariants; keep the existing `Cuenta`, `RAW`, header, and legacy-flat-tab paragraphs, editing only what the naming/order/per-year switch changes.
- Update `docs/ai/README.md` status sentence with the plan path and shipped behavior.
- Append a `docs/ai/logs/2026-11.md` entry titled for this change with shipped behavior, decisions (manual rename, manual per-year switch), and validation results.
- Do not modify source or tests in this task.

**Step 4: Run test to verify pass** (owner: agent)

```powershell
.\scripts\mvn.ps1 test
git status --short
```

Expected: all tests pass, JaCoCo >= 80%, only intended docs/src/test files changed, no `.env` or state file.

**Step 5: Commit**

```powershell
git add README.md AGENTS.md docs/ai/architecture.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-16_010003-spanish-month-tabs-ascending-dates.md
git commit -m "docs: document Spanish month tabs and ascending order"
```

## Validation summary

| Validation | Owner | Command / procedure | Expected |
| --- | --- | --- | --- |
| Sheets writer tests | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest' '-Djacoco.skip=true'` | Spanish titles, `%20` ranges, addSheet names, ascending order, header/backfill/no-op/errors pass |
| CLI sync tests | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest' '-Djacoco.skip=true'` | Spanish tab routing, encoded append URLs, ascending order end-to-end, idempotency/labels pass |
| Full suite + coverage | agent | `.\scripts\mvn.ps1 test` | BUILD SUCCESS, JaCoCo line coverage >= 80% |
| Docs consistency | agent | `Select-String` commands in Task 2 Step 2 | Spanish naming/order and per-year switch documented; no stale live `YYYY-MM` naming claim |
| Real spreadsheet smoke test | human (owner) | Disposable spreadsheet: run `sync` with transactions out of date order, confirm a tab named e.g. `Agosto 2026`, rows 1→30, second run `appended 0 new row(s)`; rename an existing `2026-08` tab and confirm dedupe still skips | recommended before closing; not a code gate |

## Acceptance criteria mapping

- Tabs are titled `Enero 2026`, `Febrero 2026`, etc. from transaction dates: Task 1 (`tabTitle`, addSheet/quoted ranges) + tests.
- Rows within each month are written in ascending date order: Task 1 (`appendRows` sort) + writer/CLI tests.
- Existing rows are not rewritten; only the new batch is sorted: Task 1 (stream sort; no read/rewrite of data rows).
- Dedupe, header/backfill, `RAW`, numeric amounts, six columns, and `Cuenta` remain unchanged: Task 1 (unchanged `SheetRow`/`HEADER_VALUES`; retargeted tests).
- Existing `YYYY-MM` tabs and history are not auto-migrated; manual rename documented: Task 2.
- One spreadsheet per year is a manual switch documented for `.env` and Actions: Task 2.
- `.\scripts\mvn.ps1 test` green with JaCoCo gate: Tasks 1-2 Step 4.
- No secrets/state committed: file lists in both tasks.

## Out of scope / follow-ups

- Automatic migration/rename of existing `YYYY-MM` tabs, or reading both old and new titles for dedupe.
- Reordering existing rows or inserting late transactions at their date position within an already-written tab (only the new batch is sorted).
- Automatic per-year spreadsheet selection or new configuration/env vars.
- Reordering the *tabs themselves* in the Google Sheets UI (creation order is unchanged; `TreeMap` still processes months chronologically within a run).
- Localizing other columns, dates, or the `Fecha` cell format; no new dependencies.
- Provider/`Transaction` parsing, `fetch` output, or scheduled-workflow changes.

## Risks

- A raw space in the A1 range would make `URI.create` throw; the plan encodes spaces as `%20`. A missed test stub surfaces as a FakeHttp 404, failing loudly.
- Existing `YYYY-MM` tabs are invisible to the new dedupe until renamed, so an un-renamed tab can cause history to be re-appended into a new Spanish-named tab. Documented for manual rename.
- A late-arriving transaction with an older date is appended at the end, so the tab is not globally re-sorted. Accepted per Decision 2.
- If the owner does not switch `GOOGLE_SPREADSHEET_ID` at year-end, new-year tabs are created in the old spreadsheet. Documented; no code guard.
- Test suite must be fully retargeted; a missed `'2026-08'` stub fails with a 404 rather than a false pass.
