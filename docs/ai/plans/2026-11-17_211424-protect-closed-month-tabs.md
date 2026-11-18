---
status: planned
created: 2026-11-17
task: 2/3
required_gates:
  architect_review: true
  memory_update: true
---

# Warning-only protection for closed month tabs

**Goal:** After a `sync`, every Spanish month tab whose month is already over is protected as a whole sheet with the "show a warning when editing" mode, while the current month stays editable and the service account can still append late transactions.

**Architecture:** `GoogleSheetsClient` gains a post-append `protectCompletedMonths(YearMonth)` pass: it reads `sheets(properties(sheetId,title),protectedRanges)` once, parses month tab titles, and sends a single `batchUpdate` with one `addProtectedRange` per unprotected closed month (`warningOnly: true`, unbounded range on `sheetId`, i.e. the whole sheet). `Main.sync` calls it after the append loop and reports the count. `fetch`/`connect` are untouched, and no new env var is introduced.

**Tech Stack:** Java 21, Gson, Google Sheets v4 REST via the existing `HttpExchange` seam, JUnit 5, JaCoCo 80% gate.

**Scope classification:** `multi-task`

## Decisions (from clarification)

- **Protection mode:** warning-only, equivalent to the Google Sheets "Mostrar una advertencia al editar este intervalo" dialog the owner showed. Every editor (including the service account) can still write; the UI asks for confirmation. No editor list is sent (`editors` is ignored by the API when `warningOnly` is true).
- **Range:** the whole sheet — `range: {"sheetId": N}` with no row/column bounds (Google documents a fully unbounded range as a protected sheet).
- **"Month completed":** a tab title that parses to Spanish month name + four-digit year and whose `YearMonth` is strictly before the run month (`YearMonth.now()`). The current and future months are never protected.
- **Idempotency:** a sheet that already has any `protectedRanges` entry is skipped, so repeated syncs never add duplicate protections and owner-managed protections win.
- **Late transactions:** allowed. Appends are written before protection runs in the same sync, and warning-only protection never blocks API writes by an editor.
- **Failure handling:** a protection HTTP failure propagates through `requireOk` and fails the sync (exit 1), consistent with metadata/read/append failures. Accepted trade-off: the next scheduled retry spends another bank access; the plan documents it rather than adding best-effort special-case handling.
- **Trigger:** only `sync`. No new CLI command, flag, env var, or per-account configuration.

## Current facts (verified)

- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:55` holds `API_BASE`; `:72-74` the `SPANISH_MONTHS` list; `:374-377` `tabTitle(YearMonth)`; `:338-343` `requireOk(Response, action)`; `:278-321` `accessToken()`.
- `GoogleSheetsClient.java:164-191` reads spreadsheet metadata once per client with `?fields=sheets.properties.title` and caches titles in `Set<String> sheetTitles`; `:194-214` creates tabs with `addSheet`; nothing reads `protectedRanges` today.
- `GoogleSheetsClient.java:124-156` `appendRows` writes the visible rows and then the `_sync` ledger; `:105-109` `existingTransactionIds` reads the ledger. Both are already append-only.
- `src/main/java/bank2sheets/Main.java:227-274` is `sync()`: fetch → group by `YearMonth` → per-month dedupe/append → one summary line at `:267-269`. The constructor at `:74-81` is the only injection point for test doubles.
- `src/test/java/bank2sheets/sheets/SheetsTestSupport.java:55-66` stubs `?fields=sheets.properties.title`; `src/test/java/bank2sheets/http/FakeHttp.java:26-41` matches stubs by exact path+query, so a new metadata URL needs its own stub.
- `src/test/java/bank2sheets/MainCommandsTest.java:575-642` wires `Main` and stubs Sheets; `:621-625` `stubSpreadsheetTitles` is called by every sync test. `GoogleSheetsClientTest.java:54-100` asserts exact metadata URL and request counts.
- Google Sheets API reference (`Sheet.protectedRanges`, `ProtectedRange`): `warningOnly: true` shows a confirmation warning and lets every user edit; `editors` is ignored in that mode; a fully unbounded `range` is a protected sheet. Verified 2026-11-17 at `developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/sheets`.
- `README.md:251-259` documents the month-tab layout; `docs/ai/architecture.md:50-58` holds the sync invariants; `docs/ai/README.md:9` carries the project status line.

## Tasks

### Task 1: Add the idempotent whole-sheet protection pass to `GoogleSheetsClient`

**Objective:** A single client method protects every completed month tab with warning-only whole-sheet protection, skips current/future/non-month tabs, and never duplicates an existing protection.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`
- Modify: `src/test/java/bank2sheets/sheets/SheetsTestSupport.java`

**Step 1: Establish expected behaviour**

- New `public int protectCompletedMonths(YearMonth currentMonth)` returns the number of protections added in this call.
- It performs one `GET {API_BASE}/{spreadsheetId}?fields=sheets(properties(sheetId,title),protectedRanges)` with the bearer token. The fields expression must select `sheetId`, `title`, and `protectedRanges`.
- A tab is a candidate only when its title parses as `<Spanish month> <four-digit year>` (reuse `SPANISH_MONTHS`; `"Hoja 1"`, `"_sync"`, and other titles parse to `null` and are ignored) and its `YearMonth.isBefore(currentMonth)`.
- A candidate is skipped when `protectedRanges` is present and non-empty. An empty or absent list means it must be protected.
- All new protections go in one `POST {API_BASE}/{spreadsheetId}:batchUpdate` body:
  `{"requests":[{"addProtectedRange":{"protectedRange":{"description":"Mes cerrado: aviso antes de editar","warningOnly":true,"range":{"sheetId":<id>}}}}]}`
- No candidate means no POST and a return of `0`. A non-2xx metadata or batchUpdate response throws `Bank2SheetsException` via `requireOk` with actions `"metadata"` and `"protect month"`.
- The method does not create tabs, does not touch `_sync`, `sheetTitles`, or `ledger`, and is safe to call repeatedly.

**Step 2: Add or update validation**

In `SheetsTestSupport`, add:

```java
public static final String PROTECTION_METADATA_PATH =
        "/sheet-1?fields=sheets(properties(sheetId,title),protectedRanges)";

public static void stubProtectionMetadata(FakeHttp http, String json) {
    http.stub(PROTECTION_METADATA_PATH, json);
}
```

In `GoogleSheetsClientTest` (all use the existing `client(FakeHttp)` helper, which stubs the token):

- `protectCompletedMonthsWarnsOnWholeClosedSheet`: metadata `{"sheets":[{"properties":{"sheetId":11,"title":"Octubre 2026"}}]}`; batchUpdate stub; call with `YearMonth.of(2026, 11)`; assert return `1`, exactly one `:batchUpdate` POST, and the body's `requests[0].addProtectedRange.protectedRange` has `warningOnly == true`, `range.sheetId == 11`, and no A1/row/column fields.
- `protectCompletedMonthsSkipsCurrentAndFutureMonths`: metadata lists `Octubre 2026` (id 11), `Noviembre 2026` (id 12), `Diciembre 2026` (id 13); call with `YearMonth.of(2026, 11)`; assert only id 11 is in the batch body.
- `protectCompletedMonthsIsIdempotentWhenAlreadyProtected`: metadata for `Octubre 2026` includes `"protectedRanges":[{"protectedRangeId":7,"warningOnly":true}]`; assert return `0` and no `:batchUpdate` request.
- `protectCompletedMonthsIgnoresNonMonthTabs`: metadata lists `_sync` and `Hoja 1` alongside `Octubre 2026`; assert only the month tab is protected.
- `protectCompletedMonthsBatchesMultipleClosedMonths`: `Julio 2026` (id 7) and `Octubre 2026` (id 11) with `currentMonth = Noviembre 2026`; assert one POST with two `addProtectedRange` requests, in metadata order, and return `2`.
- `protectCompletedMonthsDoesNothingWhenNoCandidate`: metadata `{}` or only current/future tabs; assert `0` and no `:batchUpdate`.
- `protectCompletedMonthsNonSuccessMetadataThrows` and `protectCompletedMonthsNonSuccessBatchUpdateThrows`: stub 403/500 and assert the message contains `"metadata failed"` / `"protect month failed"` plus the status; for the batchUpdate case assert the POST happened.

**Step 3: Write minimal implementation**

- Add `private static final String PROTECTION_FIELDS = "?fields=sheets(properties(sheetId,title),protectedRanges)";` and `private static final String PROTECTION_DESCRIPTION = "Mes cerrado: aviso antes de editar";`.
- Add `public int protectCompletedMonths(YearMonth currentMonth)`:
  - `String token = accessToken();`
  - GET `API_BASE + "/" + config.spreadsheetId() + PROTECTION_FIELDS`, `requireOk(response, "metadata")`, parse with `JsonSupport.parseObject`.
  - Iterate `spreadsheet.getAsJsonArray("sheets")` defensively (absent → nothing to do); read `properties.sheetId` as int and `properties.title` with `JsonSupport.stringOrEmpty`.
  - Build the `requests` array with the exact JSON shape above; use `JsonObject`/`JsonArray` like `createSheet`.
  - Return `0` before any POST when the array is empty; otherwise POST to `API_BASE + "/" + config.spreadsheetId() + ":batchUpdate"` with `Authorization` + `Content-Type: application/json`, `requireOk(response, "protect month")`, and return the request count.
- Add `private static YearMonth parseTabTitle(String title)`: split at the last space, require a four-digit numeric year, look up the month name in `SPANISH_MONTHS`, and return `null` for anything else (including `_sync`, `Hoja 1`, and malformed titles).
- Update the class Javadoc with one sentence: completed month tabs are warning-protected on demand; current/future tabs are left editable.
- Do not change `ensureSheetTitles`, `appendRows`, or the ledger.
- The fake backend does not validate the `fields` grammar, so the real behavior is only confirmed by the human gate; if the live API rejects `sheets(properties(sheetId,title),protectedRanges)`, use the equivalent `?fields=sheets.properties(sheetId,title),sheets.protectedRanges` (update the test constant to match).

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'`
Expected: all client tests green, including the new protection cases; no existing assertion changes.

Then run the full suite once to catch coverage regressions: `.\scripts\mvn.ps1 test`
Expected: green with the JaCoCo 80% line gate.

**Step 5: Commit**

`git add src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/sheets/SheetsTestSupport.java`
`git commit -m "feat: warn-protect completed month tabs"`

---

### Task 2: Run the protection pass from `sync` and report it

**Objective:** Every successful `sync` protects closed month tabs after appending rows, with a deterministic integration test; `fetch`/`connect` and existing sync behaviour are unchanged.

**Files:**
- Modify: `src/main/java/bank2sheets/Main.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour**

- `sync` calls `sheetsClient.protectCompletedMonths(currentMonth.get())` exactly once, after the per-month append loop and before the summary print. It still runs when zero transactions were fetched.
- The summary line becomes `sync: fetched N transaction(s), appended M new row(s), skipped K duplicate(s), protected P closed month tab(s)`.
- Protection errors propagate to the existing `catch (Bank2SheetsException | IllegalStateException e)` block, so the run prints `sync: ...` and exits 1.
- `fetch` and `connect` never call the protection pass.
- Test seam: add a package-private `Main` constructor overload with a trailing `Supplier<YearMonth> currentMonth`; keep the existing 5-argument constructor delegating with `YearMonth::now`, so `Main.run` and current tests are untouched.

**Step 2: Add or update validation**

In `MainCommandsTest`:

- Update the local helper `stubSpreadsheetTitles(...)` (around `:621-625`) to also call `SheetsTestSupport.stubProtectionMetadata(sheets, "{}")`. Every existing sync test then satisfies the extra GET, sees no sheets, and keeps its existing `:batchUpdate` count assertions.
- Add `enableBankingSyncProtectsClosedMonthsAfterAppend`:
  - Enable Banking fake with one transaction dated `2026-10-15`.
  - Sheets stubs: titles `["Octubre 2026", "_sync"]`, header, ledger `{}`, visible append, and `stubProtectionMetadata` with `{"sheets":[{"properties":{"sheetId":7,"title":"Octubre 2026"}}]}`.
  - Construct `Main` with the 6-argument constructor and `() -> YearMonth.of(2026, 11)`.
  - Assert exit `0`; stdout contains `protected 1 closed month tab(s)`; exactly one `:batchUpdate` body whose JSON has `requests[0].addProtectedRange.protectedRange.range.sheetId == 7` and `warningOnly == true`; and that request appears after the `'Octubre%202026'!A1:D:append` request in `sheets.requests`.
- Add `syncLeavesCurrentMonthUnprotected` (unit-level is already covered by Task 1; keep this only if it adds value cheaply): metadata lists `Noviembre 2026` with `currentMonth = Noviembre 2026`; assert `protected 0 closed month tab(s)` and no protection batchUpdate.
- Keep `syncWithSheetsErrorExitsNonZero`, duplicate-sync, and multi-month tests passing unchanged apart from the new empty-protection stub.

**Step 3: Write minimal implementation**

- Add field `private final Supplier<YearMonth> currentMonth;`.
- Keep `Main(..., Runnable waitForEnter)` delegating to a new `Main(..., Runnable waitForEnter, Supplier<YearMonth> currentMonth)`; assign the supplier plus the existing fields in the new constructor.
- In `sync()`, after the `for (Map.Entry<YearMonth, List<SheetRow>> ...)` loop, add `int protectedMonths = sheetsClient.protectCompletedMonths(currentMonth.get());`.
- Extend the summary `System.out.println` with `", protected " + protectedMonths + " closed month tab(s)"`.
- Update the `sync()` Javadoc with one sentence about the post-append protection pass.
- No change to `USAGE`, config, `fetch`, or `connect`.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'`
Expected: green, including the new protection-order test.

Then run: `.\scripts\mvn.ps1 test`
Expected: full suite green with the JaCoCo 80% gate.

**Step 5: Commit**

`git add src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/MainCommandsTest.java`
`git commit -m "feat: protect closed month tabs during sync"`

---

### Task 3: Document the behaviour and record the decision

**Objective:** The README, architecture invariants, and a new ADR describe exactly what is protected, when, why warning-only, and the accepted trade-offs.

**Files:**
- Modify: `README.md` (`## Google Sheets layout`, around `:251-259`)
- Modify: `docs/ai/architecture.md` (`## Invariants`)
- Modify: `docs/ai/README.md` (status line `:9`)
- Create: `docs/ai/decisions/ADR-0009-warning-only-protection-for-closed-month-tabs.md`
- Modify: `AGENTS.md` (project overview sentence)

**Step 1: Establish expected behaviour**

- README states: on each `sync`, tabs for months before the current month are protected as a whole sheet with the warning-only mode; the current month is not protected; the service account keeps appending late transactions; existing protections are respected; to edit a closed month, confirm the warning (or remove the protection in the Sheets UI).
- Architecture invariants gain one bullet: "Month tabs for completed months are protected as a whole sheet with `warningOnly: true` on each `sync` (after appends, idempotent by skipping tabs that already have a `protectedRanges` entry); the current and future months stay unprotected, and warning-only protection does not block the service account's appends."
- ADR-0009 records context, the warning-only/whole-sheet decision, the "month completed = parsed tab month < run month" definition, idempotency, late-transaction behaviour, the fail-fast failure policy, and rejected alternatives (strict protection with an editor list; unprotect → append → reprotect; warn-and-continue).
- `docs/ai/README.md` status line mentions the new protection pass; `AGENTS.md` project overview gains a short clause ("…and warning-protects month tabs once their month is over").
- No new configuration, command, or migration documentation.

**Step 2: Add or update validation**

- `git diff --check` → no whitespace errors.
- Read the changed README/architecture/ADR sections and confirm they match Task 1/2 behaviour (warning-only, whole sheet, current month excluded, service-account appends allowed, idempotent).
- Human validation (owner, not the agent): run `.\scripts\run.ps1 sync` against the real spreadsheet; open Google Sheets and confirm a closed month tab shows the "Permisos de edición de intervalos" dialog with "Mostrar una advertencia al editar este intervalo" and the sidebar shows the tab as protected, while the current month is not protected; run `sync` again and confirm no duplicate protected ranges appear.

**Step 3: Write minimal implementation**

- Add the README paragraph under `## Google Sheets layout` (Spanish-facing terminology, English prose like the rest of the README).
- Append the invariant bullet to `docs/ai/architecture.md`.
- Create the ADR with the standard structure used by ADR-0007/0008: Status, Date, Context, Decision, Consequences, Alternatives considered.
- Update the status sentence in `docs/ai/README.md` and the overview clause in `AGENTS.md`.
- Do not rewrite unrelated docs.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`
Expected: full suite green with the JaCoCo 80% gate (docs changes must not affect the build).

Run: `git diff --check` and `git status --short`
Expected: only the intended docs/plan files changed; no secrets or `.env`/state files.

**Step 5: Commit**

`git add README.md AGENTS.md docs/ai/architecture.md docs/ai/README.md docs/ai/decisions/ADR-0009-warning-only-protection-for-closed-month-tabs.md`
`git commit -m "docs: record closed-month tab protection"`

## Validation ownership

- **agent:** `.\scripts\mvn.ps1 test`, targeted surefire runs, `git diff --check`, `git status`/`git diff` review. Bounded and non-interactive.
- **human:** the real-spreadsheet verification in Task 3 (external Google service, real credentials, interactive UI) and the final visual check that no duplicate protected ranges exist.

## Out of scope

- Migrating, renaming, archiving, or deleting existing tabs.
- Blocking the spreadsheet owner entirely; strict protection with an editor list.
- Protecting the `_sync` helper tab, the legacy flat tab, non-month tabs, or current/future months.
- New env vars, CLI flags, per-account settings, or a UI.
- Changing append, dedupe, header, or routing behaviour.
- Retrofitting protection for spreadsheets/accounts that never run `sync` again.

## Risks and trade-offs

- Warning-only does not prevent the owner's edits (Google always lets the owner edit, even strict ranges); it interrupts accidental edits with a confirmation, which is the issue's threat model.
- One extra `spreadsheets.get` per `sync` (small; Sheets quota is not a concern).
- Any pre-existing protected range on a month tab makes that tab skip the whole-sheet protection; owner-managed protections take precedence.
- A protection failure fails the whole sync after rows were appended; the scheduled retry may spend another bank access. This is accepted for consistency with existing Sheets error handling.
