---
status: completed
created: 2026-11-16
task: 3/3
required_gates:
  architect_review: true
  memory_update: true
---

# Four-column Sheets layout with DD/MM/YYYY dates and a hidden dedupe ledger

**Goal:** Month tabs show only `Fecha`, `Importe`, `Descripción`, `Cuenta` with `Fecha` written as `DD/MM/YYYY`, while `sync` keeps exact transaction-id idempotency through a hidden `_sync` helper tab.

**Architecture:** The visible row shrinks from six columns to four (`A1:D`) and `SheetRow` carries a `LocalDate` formatted at write time, so batch ordering stays chronological. `TransactionId`, `Moneda`, and the visible id column are dropped; dedupe moves to a hidden `_sync` tab with columns `Tab`/`TransactionId`, read per month and appended after each successful visible append. Existing six-column month tabs are not migrated: `appendRows` rejects a legacy header with a reset instruction, and the owner resets tabs before the first post-upgrade sync.

**Tech Stack:** Java 21, Gson, Google Sheets v4 REST (raw HTTP), JUnit 5.

**Scope classification:** `multi-task`

## Decisions (from clarification)

- **Dedupe:** hidden helper tab `_sync` holding `Tab` + `TransactionId` (exact idempotency, no extra visible columns). The visible `TransactionId` column is removed.
- **Existing tabs:** owner resets them (delete/archive the Spanish month tabs and the `_sync` tab, or use a fresh spreadsheet); the next `sync` repopulates the four-column layout. Provider history is fully reproducible because `EnableBankingClient.fetch` pages all `BOOK` transactions with no date window.
- **Headers:** Spanish conventions — `Fecha`, `Importe`, `Descripción` (accented), `Cuenta`. This supersedes the raw `Descripcion` / `BANK ACCOUNT` wording in the request.
- **Date representation:** `DD/MM/YYYY` text cells (`valueInputOption=RAW` keeps them literal, not date-typed). Sorting is done on the underlying `LocalDate`, never on the formatted string.

## Current facts (verified)

- `src/main/java/bank2sheets/sheets/SheetRow.java:15-26` holds six fields and emits `fecha, descripcion, importe, moneda, transactionId, cuenta`.
- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:51-57` hard-codes `!A1:F`, `!A1:F1`, `!E2:E` and the six-value header; `:88-104` reads dedupe ids from `E2:E`; `:116-137` writes the header and the legacy `F1 = "Cuenta"` backfill; `:290-295` has the only `PUT` path (`updateValues`), used solely for that backfill.
- `src/main/java/bank2sheets/Main.java:224-274` builds `SheetRow` from `transaction.madeOn().toString()` (ISO), description, amount, currency, id, and account label; dedupe is `existingTransactionIds(month)` plus an in-run `seen` set.
- `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java` asserts six-value headers, `E2:E` reads, `F1` backfill, and ISO dates (`:139-186`, `:207-250`).
- `src/test/java/bank2sheets/MainCommandsTest.java:33-35, 573-580` shares a six-column header stub and stubs `A1:F1`, `E2:E`, and `A1:F:append`; sync tests read ids from row index 4 and the account label from index 5.
- `FakeHttp` (`src/test/java/bank2sheets/http/FakeHttp.java:35-41`) keys stubs by exact path+query, so every new range (including `_sync` ranges and the hidden `addSheet` body) needs an explicit stub.

## Tasks

### Task 1: Move dedupe to a hidden `_sync` ledger tab

**Objective:** `sync` dedupes on `_sync` (`Tab`, `TransactionId`) instead of the visible month-tab id column, and records ids only after a successful visible append, with no change to the visible row layout yet.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour**

- A hidden tab titled `_sync` is created on demand (`addSheet` properties `{"title":"_sync","hidden":true}`) with one header row `Tab | TransactionId`; data starts at row 2.
- `existingTransactionIds(YearMonth)` returns a mutable copy of the ids whose `Tab` equals `tabTitle(month)`; multiple months share one `_sync` read per client instance.
- `appendRows(month, rows)` appends the visible rows first, then appends `[tabTitle, transactionId]` pairs to `_sync!A2:B`; the in-memory ledger is updated only after that append succeeds.
- If the ledger append fails, the run throws (exit 1); a later run may duplicate rows, which is accepted over losing rows.
- No `E2:E` read remains.

**Step 2: Add or update validation**

In `GoogleSheetsClientTest`:
- Rework `existingTransactionIdsForExistingMonthReadsQuotedRange`: metadata lists `Agosto 2026` and `_sync`; stub `_sync!A:B` with `[["Tab","TransactionId"],["Agosto 2026","t1"],["Julio 2026","t9"],["Agosto 2026","t2"]]`; assert `Set.of("t1","t2")`.
- Rework `existingTransactionIdsForMissingMonthCreatesSheetThenReads`: expect the month `addSheet`, the hidden `_sync` `addSheet`, the `_sync` header append, then the id read; a second month must not re-read metadata or re-create `_sync`.
- Add `appendRowsRecordsTransactionIdsInHiddenLedger`: assert the visible append happens before the `_sync!A2:B` append and that the ledger body is `{"values":[["Agosto 2026","t1"],["Agosto 2026","t2"]]}`.
- Add `ledgerAppendFailureThrowsAfterVisibleAppend`: stub the `_sync` append with 500 and assert a `Bank2SheetsException` mentioning `append failed`.
- Keep the header/data/error tests working by adding the `_sync` stubs they now need.

In `MainCommandsTest`:
- Add a shared `stubLedger(FakeHttp sheets, String dataJson)` helper (stubs `_sync!A:B` and the `_sync` append URL) and call it from `stubMonthSheet`/`stubSpreadsheetTitles` call sites.
- Re-sync assertions stop stubbing `E2:E`; they stub `_sync!A:B` with `[[ "<tab title>", "<id>" ]]` for the captured id (ids are captured from the `_sync` append body, not the visible row).
- `syncWithSheetsErrorExitsNonZero` stubs the `_sync` read with 403 and still asserts `Google Sheets read failed`.

**Step 3: Write minimal implementation**

- Add constants `HELPER_TITLE = "_sync"`, `HELPER_HEADER = List.of("Tab","TransactionId")`, `HELPER_READ_RANGE_SUFFIX = "!A:B"`, `HELPER_APPEND_RANGE_SUFFIX = "!A2:B"`.
- Extract `ensureSheetTitles()` from `ensureSheet` and add `createSheet(String title, boolean hidden)` that writes `"hidden": true` only when requested.
- Add `ensureHelperSheet()`: ensures titles are loaded; when `_sync` is missing, creates it hidden and appends the header to `'_sync'!A1:B`.
- Add a lazily loaded `Map<String, Set<String>> ledger` built from one `readValues("'_sync'!A:B")`; skip blank rows and the `Tab` header row.
- `existingTransactionIds(month)`: `ensureSheet(tabTitle(month))`, then `new HashSet<>(ledger().getOrDefault(title, Set.of()))`.
- `appendRows(month, rows)`: after the visible append, build `[title, row.transactionId()]` records, append them to `'_sync'!A2:B`, then add them to the cached ledger. Call `ledger()` before the visible append so the cache is consistent even when `appendRows` is called standalone.
- Remove `IDS_RANGE_SUFFIX` and the `E2:E` read. Update the class Javadoc.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'`
Expected: all client tests pass; request-order assertions show token, metadata, `_sync` creation/header, reads, visible append, ledger append.

Run: `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'`
Expected: sync tests pass against the ledger stubs; re-runs report `appended 0 new row(s)`.

**Step 5: Commit**

`git add src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/MainCommandsTest.java`
`git commit -m "feat: dedupe Sheets rows via hidden _sync ledger"`

---

### Task 2: Write the four-column layout with DD/MM/YYYY dates

**Objective:** Month tabs contain exactly `Fecha | Importe | Descripción | Cuenta`, dates render as `DD/MM/YYYY`, ordering uses the real date, and a legacy six-column tab fails fast with a reset instruction.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/SheetRow.java`
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Modify: `src/main/java/bank2sheets/Main.java`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour**

- `SheetRow` keeps `LocalDate fecha`, `BigDecimal importe`, `String descripcion`, `String cuenta`, and `String transactionId` (internal dedupe value, not written to the visible row).
- `SheetRow.values()` returns four cells: formatted `DD/MM/YYYY` fecha, importe (numeric), descripcion, cuenta.
- `HEADER_VALUES` is `Fecha, Importe, Descripción, Cuenta`; ranges become `!A1:D`, `!A1:D1`, and appends target `!A1:D`.
- The incoming batch sorts by `SheetRow::fecha` (`LocalDate`), so `02/08/2026` precedes `15/08/2026`.
- The legacy `F1 = "Cuenta"` backfill branch is gone. A tab whose existing header does not match the four expected cells throws a `Bank2SheetsException` telling the owner to delete/archive that tab and `_sync` and re-run; no header row is appended to a legacy tab.
- `Main.sync()` constructs `new SheetRow(transaction.madeOn(), transaction.amount(), transaction.description(), label, transaction.id())`. The sync summary text and `fetch` JSON output are unchanged.

**Step 2: Add or update validation**

In `GoogleSheetsClientTest`:
- `appendRowsWritesHeaderThenDataWithAmountsAsNumbers`: header body `{"values":[["Fecha","Importe","Descripción","Cuenta"]]}`; row 0 is `"01/08/2026"`, row 1 is `"Nomina"`, row 2 is the `1200.50`-equivalent `BigDecimal`, row 3 is `"Cuenta Principal"`; `_sync` append follows.
- `appendRowsSortsRowsByDateAscending`: expect `"02/08/2026"` before `"15/08/2026"`.
- `appendRowsSkipsHeaderWhenAlreadyPresent`: stub the four-value header and assert four requests (token, metadata, header read, visible append) plus the ledger append; no `PUT`.
- Replace the two backfill tests with `appendRowsRejectsLegacySixColumnHeader`: stub `["Fecha","Descripción","Importe","Moneda","TransactionId","Cuenta"]`; assert `Bank2SheetsException`, a message mentioning the four-column header and `_sync`, and no `:append` request for the visible range.
- Update `nonSuccessAppendThrows` to the `A1:D1`/`A1:D:append` ranges.

In `MainCommandsTest`:
- `HEADER_JSON` becomes the four-value header.
- `stubMonthSheet` stubs `A1:D1`, `A1:D:append`, and the ledger ranges; drop `E2:E`.
- Sync assertions read `Cuenta` at index 3 and dates as `"01/08/2026"`, `"31/07/2026"`, `"03/08/2026"`, `"20/08/2026"`.
- `enableBankingSyncSkipsAppendForAllDuplicateMonth` filters the visible append at `A1:D:append` and captures ids from the `_sync` append.

**Step 3: Write minimal implementation**

- `SheetRow`:
  ```java
  public record SheetRow(LocalDate fecha, BigDecimal importe, String descripcion,
          String cuenta, String transactionId) {
      private static final DateTimeFormatter FECHA_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu");

      List<Object> values() {
          return List.<Object>of(fecha.format(FECHA_FORMAT), importe, descripcion, cuenta);
      }
  }
  ```
- `GoogleSheetsClient`: set `RANGE_SUFFIX = "!A1:D"`, `HEADER_RANGE_SUFFIX = "!A1:D1"`, `HEADER_VALUES = List.of("Fecha","Importe","Descripción","Cuenta")`; sort with `Comparator.comparing(SheetRow::fecha)`; replace the backfill branch with a header-match guard (`stringAt(row, i).equals(HEADER_VALUES.get(i))` for all four cells) that throws the reset message; delete `updateValues` and the legacy Javadoc.
- `Main.sync()`: pass `transaction.madeOn()` and the reordered `SheetRow` fields; update the method Javadoc to describe the hidden-ledger dedupe.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`
Expected: full suite green; JaCoCo 80% line gate met.

**Step 5: Commit**

`git add src/main/java/bank2sheets/sheets/SheetRow.java src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/MainCommandsTest.java`
`git commit -m "feat: write four-column rows with DD/MM/YYYY dates"`

---

### Task 3: Document the layout, dedupe ledger, and reset gate

**Objective:** README, architecture memory, and a new ADR describe the four-column layout, DD/MM/YYYY text dates, hidden `_sync` dedupe ledger, and the required one-time reset, with no remaining six-column/`E2:E` claims.

**Files:**
- Modify: `README.md` (`BANK2SHEETS_ACCOUNTS` table row ~207, "Google Sheets layout" ~253-259)
- Modify: `AGENTS.md` (overview clause about the `Cuenta` column, only if it now misstates the layout)
- Modify: `docs/ai/architecture.md` (invariants ~50-59)
- Modify: `docs/ai/README.md` (Repository Status)
- Create: `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md`
- Modify: `docs/ai/logs/2026-11.md` (append one dated entry after validation)

**Step 1: Establish expected behaviour**

- README "Google Sheets layout" states: four columns `Fecha, Importe, Descripción, Cuenta`; `Fecha` is `DD/MM/YYYY` text; rows are appended in ascending date order; amounts stay numeric via `RAW`; dedupe is exact on `TransactionId` stored in the hidden `_sync` tab; `sync` twice appends no duplicates.
- README documents the one-time reset: before the first sync of this version, delete/archive the existing Spanish month tabs **and** any `_sync` tab (or point `GOOGLE_SPREADSHEET_ID` at a fresh spreadsheet); otherwise `sync` fails fast on the legacy header. The reset is safe because provider history is fully re-fetched.
- `architecture.md` invariants replace the six-column/`E2:E`/`F1` backfill paragraphs with the four-column layout, the `DD/MM/YYYY` text date rule (ordered by `LocalDate`), the hidden `_sync` ledger (`Tab`, `TransactionId`; ids recorded after the visible append), and the legacy-header rejection.
- ADR-0008 records the decision, alternatives (fingerprint dedupe, keeping the id column, in-place migration), and consequences (hidden tab, reset gate, duplication-only failure mode if the ledger append fails, no date-typed cells).
- `docs/ai/README.md` status mentions the new layout and links the plan/ADR.

**Step 2: Add or update validation**

Run: `Select-String -Path README.md,AGENTS.md,docs/ai/architecture.md -Pattern 'Moneda|TransactionId|E2:E|A1:F|Descripción.*Moneda'`
Expected: no stale six-column/`E2:E` claim remains; `TransactionId` appears only in the hidden-ledger/README reset context.

Run: `Select-String -Path README.md,docs/ai/architecture.md -Pattern 'dd/MM|DD/MM|_sync|Cuenta'`
Expected: the date format, hidden ledger, and `Cuenta` header are documented.

**Step 3: Write minimal implementation**

- Rewrite the README layout and reset paragraphs; keep the one-spreadsheet-per-account and per-year switch guidance.
- Update the `AGENTS.md` overview only where it implies the six-column layout; keep the `Cuenta` clause.
- Rewrite the affected `architecture.md` invariants; do not duplicate the full README text.
- Write `ADR-0008` in the same shape as `ADR-0007` (Context, Decision, Alternatives considered, Consequences, Validation, References).
- Update the `docs/ai/README.md` status line and append a `## 2026-11-16 — Four-column layout with hidden dedupe ledger` entry to `docs/ai/logs/2026-11.md` after the full suite and human smoke test pass.

**Step 4: Run docs checks**

Run the two `Select-String` commands above.
Expected: no stale six-column claims; new terms present.

**Step 5: Commit**

`git add README.md AGENTS.md docs/ai/architecture.md docs/ai/README.md docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md docs/ai/logs/2026-11.md`
`git commit -m "docs: document four-column layout and hidden dedupe ledger"`

## Validation ownership

| Validation | Owner | Command / procedure | Expected |
|---|---|---|---|
| Sheets client tests | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'` | Pass; ranges, header, ledger order asserted |
| CLI sync tests | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'` | Pass; idempotent second run |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` | All tests pass; JaCoCo ≥ 80% lines |
| Docs consistency | agent | `Select-String` checks above | No stale six-column/`E2:E` claims |
| Real-spreadsheet smoke test | human | Reset: delete/archive existing Spanish month tabs and `_sync` (or use a fresh spreadsheet). Run `.\scripts\run.ps1 sync` twice. | Tabs have `Fecha/Importe/Descripción/Cuenta`; `Fecha` is `DD/MM/YYYY`; `_sync` is hidden with `Tab/TransactionId`; second run prints `appended 0 new row(s)` |
| Legacy-header failure | human (optional) | Keep one six-column month tab, run `sync`. | Exit 1 with the reset instruction; no rows appended to the legacy tab |

## Out of scope

- Migrating or rewriting existing six-column rows; the owner resets tabs.
- Date-typed (numeric serial) cells; `Fecha` stays literal `DD/MM/YYYY` text.
- Changing the `fetch` JSON output, provider client, month-tab naming, or the monthly workflow.
- Re-adding `Moneda`, `TransactionId`, or any other visible column.
- Fingerprint-based dedupe; the hidden ledger is the chosen mechanism.
