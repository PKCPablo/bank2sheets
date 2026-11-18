---
status: completed
created: 2026-11-18
task: 2/2
required_gates:
  architect_review: true
  memory_update: true
---

# Typed `Fecha` cells with `USER_ENTERED` and literal text Implementation plan

**Goal:** New `sync` rows carry a real date cell in `Fecha` (no apostrophe, display per sheet locale) while `Descripción`/`Cuenta` stay literal text and `Importe` stays numeric (GitHub issue #18).

**Architecture:** Keep the append-only, four-column, `_sync`-deduped layout. Split the append contract by purpose: visible month rows use `valueInputOption=USER_ENTERED` with `Fecha` as an ISO `yyyy-MM-dd` string (Sheets parses it as a date in any locale) and `Descripción`/`Cuenta` prefixed with an apostrophe (literal-text escape); the header row and the `_sync` ledger keep `RAW`. `SheetRow` owns the cell representation, so `GoogleSheetsClient` only selects the option per append. Existing text-date rows are not migrated; mixed tabs are accepted.

**Tech Stack:** Java 21, Gson 2.11.0, Google Sheets v4 REST through `HttpExchange`, JUnit 5, JaCoCo 80% line gate.

**Scope classification:** `multi-task`

## Decisions (from clarification)

- **Date representation:** send `Fecha` as ISO `yyyy-MM-dd`; let `USER_ENTERED` parse it into a date-typed cell whose display follows the spreadsheet locale (e.g. `dd/mm/aaaa`). Do not set a number format or locale in code.
- **Option split:** `USER_ENTERED` only for the visible month-row append; header row and `_sync` ledger stay `RAW`. `Importe` remains a JSON number (non-string JSON values are not parsed even under `USER_ENTERED`).
- **Text safety:** every non-empty `Descripción`/`Cuenta` value gets one leading apostrophe; Sheets treats it as the literal-text marker and does not display it. This covers formula-like (`=`, `+`, `-`, `@`) and number/date-like strings (e.g. `2-3-4`). A value that already starts with `'` keeps it (`''x` stores `'x`).
- **Legacy tabs:** existing rows written as `DD/MM/YYYY` text stay text. No reset, migration, or repair pass is added; the owner can still reset manually. The header-rejection guard for legacy six-column tabs is unchanged.
- **No new CLI surface:** no command, flag, env var, or configuration.

## Current facts (verified)

- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:61` has a single `VALUE_INPUT_OPTION = "RAW"`; `appendUrl` (`:447-449`) always emits it, and every `appendValues` call (`:140`, `:153`, `:155`, `:303`) shares it. The four call sites are: visible header, visible rows, `_sync` data, `_sync` header.
- `src/main/java/bank2sheets/sheets/SheetRow.java:28-33` formats `Fecha` with `DateTimeFormatter.ofPattern("dd/MM/uuuu")` and emits raw `descripcion`/`cuenta`.
- `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md:13,32` decides and justifies text dates (`RAW`, "No date-typed cells"); the same claim is in `README.md:253` and `docs/ai/architecture.md:55`; `docs/ai/architecture.md:60` says amounts are appended with `RAW`.
- Tests pin the old contract: `GoogleSheetsClientTest` asserts header/data/ledger URLs end in `valueInputOption=RAW` and dates like `01/08/2026`; `SheetsTestSupport.stubExistingMonth` (`:91`) and `MainCommandsTest.stubMonthSheet` (`:715`) stub the month append with `RAW`; `MainCommandsTest` asserts `RAW` in every month-append filter (`:159,209,249,272,325,383,412,431,475`) and `_sync` stays `RAW` (`:168,219,338,416,723,724`).
- `FakeHttp` keys stubs by exact path+query, so a URL change without a stub change fails the test loudly (no silent mismatch).
- `USER_ENTERED` semantics: strings may be converted to numbers/dates/formulas like UI input (Google `ValueInputOption` docs, `https://developers.google.com/workspace/sheets/api/reference/rest/v4/ValueInputOption`). The leading-apostrophe escape with `USER_ENTERED` is the accepted answer in `https://stackoverflow.com/questions/37785216/google-sheets-api-v4-and-valueinputoption` and is the complement of the reported `RAW` apostrophe behavior in `https://stackoverflow.com/questions/58173687/google-sheets-api-appending-extra-apostrophe`.
- `SheetRow` inputs are non-null in production (`Main.java:261-262` passes `Transaction.description()`, which `EnableBankingClient` builds as a joined string; `Cuenta` comes from config or provider data), so the escape helper needs no null branch.

## Tasks

### Task 1: Write typed dates and literal text on the visible append

**Objective:** A sync append sends `Fecha` as an ISO date and apostrophe-escaped text under `USER_ENTERED`, while the header and `_sync` appends still send `RAW` and amounts stay numbers.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/SheetRow.java`
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Modify: `src/test/java/bank2sheets/sheets/SheetsTestSupport.java`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Validate: the files above

**Step 1: Establish expected behaviour**

The visible row append must hit
`.../values/'<Tab>'!A1:D:append?valueInputOption=USER_ENTERED` with a body where `[0]` is `yyyy-MM-dd`, `[1]` is a JSON number, `[2]`/`[3]` are `'`-prefixed non-empty text. The header append (`A1:D` on an empty tab), the `_sync` header (`A1:B`), and the `_sync` data (`A2:B`) must keep `valueInputOption=RAW`. Sorting still uses the underlying `LocalDate`, so ascending order is unchanged. Mixed tabs (old text-date rows above new typed rows) must not be rejected: the only existing-row read is the `A1:D1` header check.

**Step 2: Add or update validation**

- `SheetsTestSupport.stubExistingMonth` (`:91`): change the month append stub to `...!A1:D:append?valueInputOption=USER_ENTERED`.
- `GoogleSheetsClientTest`:
  - `appendRowsWritesHeaderThenDataWithAmountsAsNumbers`: keep the header append assertion at `RAW`; assert the data append URL ends in `USER_ENTERED`; expect `2026-08-01` in `[0]`, `'Nomina` in `[2]`, `'Cuenta Principal` in `[3]`, and keep the `BigDecimal` assertions.
  - `appendRowsRecordsTransactionIdsInHiddenLedger`, `appendRowsSortsRowsByDateAscending`, `appendRowsSkipsHeaderWhenAlreadyPresent`, `ledgerAppendFailureThrowsAfterVisibleAppend`: month-append URL → `USER_ENTERED`; ISO date expectations (`2026-08-02`, `2026-08-15`).
  - `appendRowsRejectsLegacySixColumnHeader`: the "nothing appended" check targets `USER_ENTERED`.
  - `nonSuccessAppendThrows` (`:358`): stub the month append at `USER_ENTERED` with the 500 response.
  - Add `appendRowsEscapesTextColumnsAsLiteralText`: one row with `descripcion = "=SUM(A1:A2)"` and `cuenta = "2-3-4"`; assert the request URL (USER_ENTERED), `[0] = "2026-08-01"`, `[2] = "'=SUM(A1:A2)"`, `[3] = "'2-3-4"`, `[1]` numeric.
- `MainCommandsTest`:
  - Month-append filters/stubs `:159,209,249,272,325,383,412,431,475,715` → `USER_ENTERED`; leave `_sync` filters/stubs (`:168,219,338,416,723,724`) at `RAW`.
  - Date assertions `:163` → `2026-08-01`; `:329` → `2026-07-31` / `2026-08-01`; `:389-390` → `2026-08-03` / `2026-08-20`.
  - `Cuenta` assertions `:161,214-215,251,274` → `'Cuenta Nominas`, `'Cuenta Ahorro`, `'uuid-1`, `'Cuenta Nóminas`.

**Step 3: Write minimal implementation**

- `SheetRow`:
  - Replace `FECHA_FORMAT` with `DateTimeFormatter.ISO_LOCAL_DATE` and return `fecha.format(FECHA_FORMAT)`.
  - Wrap text cells: `literal(descripcion)`, `literal(cuenta)` with
    `private static String literal(String value) { return value.isEmpty() ? value : "'" + value; }`.
  - Update the record Javadoc: ISO date for `USER_ENTERED`; text columns apostrophe-escaped; amounts remain JSON numbers.
- `GoogleSheetsClient`:
  - Replace `VALUE_INPUT_OPTION` with `RAW` and `USER_ENTERED` constants.
  - Add a `String valueInputOption` parameter to `appendValues(...)` and `appendUrl(...)`; keep `appendUrl` as `valuesUrl(range) + ":append?valueInputOption=" + valueInputOption`.
  - Pass `RAW` for the visible header (`:140`) and both `_sync` appends (`:155`, `:303`); pass `USER_ENTERED` for the visible rows (`:153`).
  - Update the class Javadoc (`:37-52`) and `appendRows` Javadoc: visible rows `USER_ENTERED` + ISO dates + escaped text; header/ledger `RAW`; mixed legacy tabs accepted.

**Step 4: Run test to verify pass**

Run:
```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'
.\scripts\mvn.ps1 test
```
Expected: targeted suites green, then the full suite green with the JaCoCo 80% line gate (`BUILD SUCCESS`), all appends routed as described.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/sheets/SheetRow.java src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/SheetsTestSupport.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/MainCommandsTest.java
git commit -m "fix: write typed Fecha cells with literal text (issue #18)"
```

---

### Task 2: Amend ADR-0008 and refresh user/architecture memory

**Objective:** Durable documentation states typed dates and mixed legacy tabs, with no stale "text date / not a date-typed cell" claim in the live docs.

**Files:**
- Modify: `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md`
- Modify: `README.md` (`## Google Sheets layout`)
- Modify: `docs/ai/architecture.md` (Invariants)
- Modify: `docs/ai/README.md` (Repository Status)
- Modify: `docs/ai/logs/2026-11.md`
- Validate: the files above
- Review: `AGENTS.md` (expectation: no date-format claim, so no edit)

**Step 1: Establish expected behaviour**

The live docs describe: `Fecha` as a date-typed cell (ISO input, `USER_ENTERED` on visible rows, display per sheet locale), `Descripción`/`Cuenta` as escaped literal text, `Importe` numeric, header/`_sync` still `RAW`, and old text-date rows left untouched (mixed tabs possible). ADR-0008 keeps its original decision text (history) and gains a dated amendment superseding only Decision bullet 2 and the Consequences bullet "No date-typed cells".

**Step 2: Add or update validation**

Docs checks (PowerShell):
```powershell
Select-String -Path README.md,docs/ai/architecture.md,docs/ai/README.md -Pattern 'not a date-typed|DD/MM/YYYY text|Fecha.*text'
Select-String -Path README.md,docs/ai/architecture.md -Pattern 'USER_ENTERED|apostrophe|ISO|date-typed'
Select-String -Path docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md -Pattern 'Amendment \(2026-11-18\)'
```
Expected: the first check returns no live-doc claims of text dates; the second returns the new wording in both files; the third returns the amendment. Also `Select-String -Path AGENTS.md -Pattern 'DD/MM|date-typed|USER_ENTERED'` should return nothing.

**Step 3: Write minimal implementation**

- ADR-0008: append `## Amendment (2026-11-18) — date-typed Fecha cells supersede the text-date decision`, stating the superseded bullets, the new append contract (USER_ENTERED visible rows, ISO date, escaped text, RAW header/`_sync`, numeric `Importe`), that existing text rows are not migrated, and links to this plan and issue #18. Do not rewrite the original Decision/Consequences text.
- `README.md` "Google Sheets layout": replace the text-date sentence with the date-typed cell behavior (display follows sheet locale), `USER_ENTERED` for visible rows vs `RAW` for header/`_sync`, and literal-text escaping for descriptions/accounts. Add one sentence that rows written by older versions stay text and mixed tabs are accepted (manual reset remains optional). Keep the existing legacy-header reset paragraph and the per-year paragraph.
- `docs/ai/architecture.md`: rewrite invariant `:55` (typed date, ISO input, locale display, legacy text rows accepted, header guard unchanged) and `:60` (visible rows `USER_ENTERED`; numbers stay numeric; text escaped; header/`_sync` `RAW`).
- `docs/ai/README.md`: update the status sentence to mention date-typed `Fecha` cells via `USER_ENTERED` and this plan/issue.
- `docs/ai/logs/2026-11.md`: add a `## 2026-11-18 - Date-typed Fecha cells` entry with what changed, test results, and commit hashes.
- `AGENTS.md`: verify no date-format claim; leave unchanged unless the check finds one.

**Step 4: Run test to verify pass**

Run the three `Select-String` checks from Step 2 plus:
```powershell
.\scripts\mvn.ps1 test
```
Expected: docs checks as described and the full suite still green with the JaCoCo gate.

**Step 5: Commit**

```powershell
git add README.md docs/ai/architecture.md docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-18_182437-date-typed-fecha-cells.md
git commit -m "docs: record date-typed Fecha cells (issue #18)"
```

## Validation ownership

| Validation | Owner | Command / procedure | Expected |
|---|---|---|---|
| Client + CLI unit suites | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'` and `'-Dtest=MainCommandsTest'` | Green, new escaping test included |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` | `BUILD SUCCESS`, JaCoCo line ratio ≥ 0.80 |
| Docs freshness checks | agent | `Select-String` commands in Task 2 | No stale live-doc text-date claim; new wording present |
| Real-sheet typing and escaping | human | 1) Use a disposable spreadsheet or the normal one with an unsynced current-month transaction. 2) `.\scripts\run.ps1 sync` once. 3) In Sheets: select a new `Fecha` cell — no leading apostrophe, right-aligned, `=ISNUMBER(A2)` is `TRUE`, column sorts chronologically; a `Descripción` beginning with `=` displays literally; `Importe` is numeric (`=ISNUMBER(B2)` `TRUE`); old text-date rows are still visible in the same tab. 4) `.\scripts\run.ps1 sync` again. | Typed dates in new rows, literal descriptions, numeric amounts, mixed tab not rejected, second run `appended 0 new row(s)`. Each sync spends one bank access (~4/24 h): run at most twice; do not loop. |

## Risks and assumptions

- **Apostrophe escape not in official docs:** the Sheets `ValueInputOption` page documents `USER_ENTERED` parsing but not the apostrophe marker; the behavior is community-verified (see Current facts) and is exactly the inverse of the `RAW` apostrophe reported in issue #18. The human validation is the gate that confirms it in the owner's spreadsheet. If it fails, the fallback is an extra `repeatCell` `numberFormat: TEXT` batchUpdate for columns C:D (new API call; requires re-approval before implementing).
- **Explicit column formats:** the escape relies on automatic cell typing; if the owner formatted `Descripción`/`Cuenta` with a non-text number format, display may differ. Validate in the real sheet; code does not override owner formatting.
- **Locale display:** ISO input guarantees date parsing, not `dd/mm/aaaa` display; the sheet locale controls the rendering (per intent). No locale is set in code.
- **Warning-protected closed tabs:** `USER_ENTERED` appends are still service-account writes and are unaffected by `warningOnly` protection (ADR-0009); no change planned.

## Required gates

- `architect_review`: true — this plan partially supersedes ADR-0008 (date representation) and changes a documented sheet-data invariant.
- `memory_update`: true — Task 2 updates `docs/ai/architecture.md`, ADR-0008, the index status, and the monthly log.
