---
status: completed
created: 2026-11-17
task: 2/2
required_gates:
  architect_review: false
  memory_update: true
---

# Fix all ponytail audit rungs - implementation plan

**Goal:** Apply every actionable finding from the 2026-11-17 ponytail audit (M1-M3, L1-L6) with no intended behavior change, keeping the 101-test baseline and the JaCoCo 80% line gate green.

**Architecture:** Two separable slices. Task 1 removes production duplication and dead scaffolding (`Main` account-label resolution, `EnableBankingClient` unused import/one-call helper, `GoogleSheetsClient` lazy-load booleans, stale `pom.xml` comment). Task 2 dedupes the test fixtures (`GoogleSheetsClientTest` stubs, `SheetsTestSupport` header/metadata helpers, dead `accounts_data`, one redundant `AppConfigTest` case, test hash helper).

**Tech Stack:** Java 21, Maven 3.9.9, JUnit 5, Gson 2.11.0, PowerShell wrappers.

**Scope classification:** `multi-task`

**Baseline:** `HEAD eb1874f`; `.\scripts\mvn.ps1 test` -> 101 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`.

**Scope note:** "All rungs" means every actionable item in the audit table. Excluded on purpose: the "Justified - do not simplify" list, `L7` (the `ProviderSources` guard is unreachable in production but retained as factory defense-in-depth), and the informational request-index brittleness refactor (no behavior or line-count win).

**Note:** The planner cannot edit source. Apply this plan with the delivery agent (`/load-plan`, `/implement-task`), then run the validation steps. Do not commit unless the user explicitly asks.

## Tasks

### Task 1: Remove the production duplication and dead scaffolding

**Objective:** Collapse the duplicated `fetch`/`sync` account-label resolution into two helpers, drop the unused import and one-call helper, and replace the Google Sheets lazy-load boolean pairs with nullable sentinels.

**Files:**
- Modify: `src/main/java/bank2sheets/Main.java`
- Modify: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java`
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Modify: `pom.xml` (JUnit dependency comment only)
- Validate: existing `MainCommandsTest`, `EnableBankingClientTest`, `GoogleSheetsClientTest`

**Step 1: Establish expected behaviour**

- `fetch` still prints `Account <id> (<label>): N transaction(s)` and `sync` still labels each `Cuenta` cell with the configured label, else the provider display name, else the account id.
- When every configured account has a label, neither command calls `listAccounts()` (no `/sessions` or `/details` requests).
- `GoogleSheetsClient` still loads the spreadsheet titles and the `_sync` ledger exactly once per client instance, creates missing tabs once, and appends the visible rows before the ledger rows.
- `EnableBankingClient.connect()` still sets `valid_until` from `maximum_consent_validity` (or the 90-day default).

**Step 2: Add or update validation**

No new tests: the existing suite covers these paths (`enableBankingFetchPrintsNormalizedTransactions`, `enableBankingSyncUsesConfiguredLabelWithoutAccountDetails`, `enableBankingSyncLabelsRowsPerAccountAndStaysIdempotent`, `existingTransactionIdsForMissingMonthCreatesSheetThenReads`, `tokenRequestUsesJwtBearerGrantAndCachesToken`, `connectSendsBearerJwtAndReturnsAuthUrl`). Run the focused tests before the full suite:

```powershell
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest,EnableBankingClientTest,GoogleSheetsClientTest'
```

**Step 3: Write minimal implementation**

In `Main`:

- Replace the private `accountNames(TransactionSource)` method with two helpers:

```java
    /** Configured label, else provider display name, else the provider account id. */
    private static String accountLabel(AccountConfig account, Map<String, String> names) {
        String label = account.label();
        if (label.isBlank()) {
            label = names.getOrDefault(account.providerAccountId(), "");
        }
        return label.isBlank() ? account.providerAccountId() : label;
    }

    /**
     * Account id -> display name from one {@code listAccounts()} call, or an
     * empty map when every configured account already has a label (so no
     * session/details accesses are spent).
     */
    private static Map<String, String> accountLabels(AppConfig cfg, TransactionSource source) {
        if (cfg.accounts().stream().noneMatch(account -> account.label().isBlank())) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (Account account : source.listAccounts()) {
            names.put(account.id(), account.name());
        }
        return names;
    }
```

- In `fetch()`: delete the `needsNames`/`accountNames` lines, call `Map<String, String> names = accountLabels(resolved, source);`, replace the 3-tier block with `String name = accountLabel(account, names);`, and simplify the print to `"Account " + account.providerAccountId() + " (" + name + "): "` (the previous `name.isBlank()` branch is unreachable after the id fallback).
- In `sync()`: delete the `needsNames`/`accountLabels` lines, call `Map<String, String> labels = accountLabels(resolved, source);`, and replace the 3-tier block with `String label = accountLabel(account, labels);`.

In `EnableBankingClient`:

- Delete `import com.google.gson.JsonArray;` (unused).
- Delete the private `now()` method and inline its only use in `connect()`:
  `access.addProperty("valid_until", Instant.ofEpochSecond(epochSeconds.getAsLong()).plusSeconds(validitySeconds).toString());`
- Add `import java.util.Base64;` and `import java.util.Locale;`, then replace the fully-qualified `java.util.Base64.getDecoder()` in `privateKeyPem()` with `Base64.getDecoder()` and `java.util.Locale.ROOT` in `failure()` with `Locale.ROOT`.

In `GoogleSheetsClient`:

- Replace the lazy-load field block with nullable sentinels:

```java
    /** Titles of the spreadsheet tabs; null until the first metadata read. */
    private Set<String> sheetTitles;
    /** Appended transaction ids per tab title; null until the first ledger read. */
    private Map<String, Set<String>> ledger;
```

- `ensureSheetTitles()`: guard with `if (sheetTitles != null) { return; }`, build the titles in a local `Set<String> loaded = new HashSet<>();`, then assign `sheetTitles = loaded;` at the end (remove `sheetTitlesLoaded`).
- `ledger()`: guard with `if (ledger == null) { ... ledger = loaded; }` (remove `ledgerLoaded`); keep the existing load body and final `return ledger;`.
- `ensureSheet`, `createSheet`, and `appendRows` keep using `sheetTitles`/`ledger` after the ensure calls, so they are non-null at those points.

In `pom.xml`:

- Replace the JUnit dependency comment `<!-- Test only: smoke test in Phase 1, unit tests in Phases 2-3 -->` with `<!-- Test only: JUnit 5 suite -->`.

**Step 4: Run test to verify pass**

Run:

```powershell
.\scripts\mvn.ps1 test
git diff --check
```

Expected: 101 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`; no whitespace errors.

Validation owner: `agent`.

**Step 5: Commit**

Do not commit unless the user explicitly asks. If asked:

```powershell
git add src/main/java/bank2sheets/Main.java `
        src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java `
        src/main/java/bank2sheets/sheets/GoogleSheetsClient.java `
        pom.xml
git commit -m "refactor: apply ponytail audit production cleanups"
```

### Task 2: Dedupe the test fixtures and remove the dead test data

**Objective:** Add shared sheets stubs and a shared header constant, remove the dead `accounts_data` fixture and one redundant parser test, and simplify the test hash helper.

**Files:**
- Modify: `src/test/java/bank2sheets/sheets/SheetsTestSupport.java`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Modify: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java`
- Modify: `src/test/java/bank2sheets/config/AppConfigTest.java`
- Validate: the same test classes plus the full suite

**Step 1: Establish expected behaviour**

The same client behaviors stay asserted: month-tab creation, header backfill, visible append before ledger append, ledger read/append, token caching, date ordering, and legacy-header rejection. The only change is where the stub bodies live. `AppConfig.parseAccounts("enablebanking:uuid-1")` still yields a blank label.

**Step 2: Add or update validation**

No new behaviors. Run the focused tests first:

```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest,MainCommandsTest,EnableBankingClientTest,AppConfigTest'
```

Then the full suite.

**Step 3: Write minimal implementation**

In `SheetsTestSupport`, add:

```java
    /** Header row body shared by the Sheets and Main sync tests. */
    public static final String HEADER_JSON =
            "{\"values\":[[\"Fecha\",\"Importe\",\"Descripci\u00f3n\",\"Cuenta\"]]}";

    /** Stubs the spreadsheet metadata response with the given tab titles. */
    public static void stubSheetTitles(FakeHttp http, String... titles) {
        StringBuilder json = new StringBuilder("{\"sheets\":[");
        for (int i = 0; i < titles.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"properties\":{\"title\":\"").append(titles[i]).append("\"}}");
        }
        json.append("]}");
        http.stub("/sheet-1?fields=sheets.properties.title", json.toString());
    }

    /** Stubs the hidden _sync ledger read range with the given raw body. */
    public static void stubLedgerRead(FakeHttp http, String dataJson) {
        http.stub("/sheet-1/values/'_sync'!A:B", dataJson);
    }

    /**
     * Stubs the common "existing month tab plus created _sync ledger" state:
     * metadata, ledger read, month header, and the visible append.
     */
    public static void stubExistingMonth(FakeHttp http, String title, String headerJson) {
        stubSheetTitles(http, title, "_sync");
        stubLedgerRead(http, "{}");
        String encoded = title.replace(" ", "%20");
        http.stub("/sheet-1/values/'" + encoded + "'!A1:D1", headerJson);
        http.stub("/sheet-1/values/'" + encoded + "'!A1:D:append?valueInputOption=RAW",
                "{\"spreadsheetId\":\"sheet-1\"}");
    }
```

Use the exact UTF-8 `Descripción` text already present in `MainCommandsTest.HEADER_JSON`; do not introduce a replacement character.

In `GoogleSheetsClientTest`:

- Delete the local metadata/ledger/header/append stub quartets that match `stubExistingMonth` and replace them with `SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", SheetsTestSupport.HEADER_JSON)` (imports are unnecessary because the class is in the same package).
- For `existingTransactionIdsForExistingMonthReadsQuotedRange`, use `SheetsTestSupport.stubSheetTitles(http, "Agosto 2026", "_sync")` plus `SheetsTestSupport.stubLedgerRead(http, <data>)`.
- Replace every literal header body `{"values":[["Fecha","Importe","Descripción","Cuenta"]]}` with `SheetsTestSupport.HEADER_JSON`.
- Leave the missing-month creation test, the failure tests, and the request-index assertions unchanged in behavior.

In `MainCommandsTest`:

- Delete the private `HEADER_JSON` constant and reference `SheetsTestSupport.HEADER_JSON`.
- Delete the private `stubSpreadsheetTitles` method and replace its calls with `SheetsTestSupport.stubSheetTitles` (keep `SheetsTestSupport.stubToken(sheets)` inside it by adding `stubToken` at the start of the shared helper — do this only if `stubSheetTitles` is also used after a token stub; otherwise keep `SheetsTestSupport.stubToken(sheets)` at the call sites as today).
- Keep the private `stubMonthSheet` and `stubLedger` helpers (they also stub `batchUpdate`/`_sync` header appends), but have them delegate the metadata call to the shared helper where applicable.
- Add `import bank2sheets.config.SheetsConfig;` and replace `new bank2sheets.config.SheetsConfig("", "")` with `new SheetsConfig("", "")` in `syncWithoutSheetsConfigExitsNonZero`.

In `EnableBankingClientTest`:

- Delete the `accounts_data` array from the `listAccountsMapsUuidAndDetailsNameAndToleratesDetailsFailure` session stub (production no longer reads it); keep the `status` and `accounts` members.
- Simplify the hash helper:

```java
    private static final String HASH_PREFIX = "eb:sha256:";

    private static String shortHash(String canonical) {
        return hashId(canonical).substring(HASH_PREFIX.length(), HASH_PREFIX.length() + 16);
    }

    private static String hashId(String canonical) {
        ...
        return HASH_PREFIX + HexFormat.of().formatHex(...);
    }
```

In `AppConfigTest`:

- Delete `labelIsOptional`; `parsesSingleEnableBankingAccount` already asserts the same input and equal record (blank label).

**Step 4: Run test to verify pass**

Run:

```powershell
.\scripts\mvn.ps1 test
git diff --check
git status --short
```

Expected: 100 tests (one redundant test removed), 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`; no whitespace errors; only the intended test files and the `SheetsTestSupport` helper changed.

Validation owner: `agent`.

**Step 5: Commit**

Do not commit unless the user explicitly asks. If asked:

```powershell
git add src/test/java/bank2sheets/sheets/SheetsTestSupport.java `
        src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java `
        src/test/java/bank2sheets/MainCommandsTest.java `
        src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java `
        src/test/java/bank2sheets/config/AppConfigTest.java
git commit -m "test: dedupe ponytail audit fixtures"
```

## Validation summary

After both tasks:

```powershell
.\scripts\mvn.ps1 test
git diff --check
git status --short
```

Expected: 100 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`. Then update `docs/ai/README.md` status if the baseline changed and append a short `docs/ai/logs/2026-11.md` entry. Do not commit unless asked.

## Implementation status (2026-11-17)

- Applied: M1, M2, M3, L1, L2, L3, L4, L5, L6. Retained intentionally: all "Justified" items, L7 (factory guard), and the informational request-index refactor.
- Task 1 (production): `Main` now has one `accountLabel`/`accountLabels` pair shared by `fetch`/`sync`; `EnableBankingClient` dropped the unused `JsonArray` import, inlined `now()`, and imports `Base64`/`Locale`; `GoogleSheetsClient` uses nullable `sheetTitles`/`ledger` sentinels; `pom.xml` comment refreshed.
- Task 2 (tests): `SheetsTestSupport` gained `HEADER_JSON`, `stubSheetTitles`, `stubLedgerRead`, and `stubExistingMonth`; `GoogleSheetsClientTest` stubs collapsed onto them; `MainCommandsTest` reuses them and imports `SheetsConfig`; removed the dead `accounts_data` fixture and the redundant `AppConfigTest.labelIsOptional`; simplified the test hash helper.
- Validation: `.\scripts\mvn.ps1 test` -> 100 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`; `git diff --check` reports no whitespace errors (only the repository's expected CRLF notices). Net change: 9 files, +93/-129 lines.
- Nothing committed; `docs/ai/plans/2026-11-17_180822-fix-ponytail-audit-rungs.md` is the only untracked file.
- The real-spreadsheet smoke test remains a pending human validation (unchanged from ADR-0008), since this change is behavior-neutral.

## Deliberately out of scope

- All items in the 2026-11-17 audit "Justified - do not simplify" list (single-provider seam, `HttpExchange`/`TransactionSource`/`Rs256Jwt`, Gson-only, JaCoCo gate, hardcoded Spanish month list, etc.).
- `L7` (`ProviderSources` provider guard): unreachable in production but retained as factory defense-in-depth.
- The informational request-index brittleness refactor in tests (no behavior or line-count win).
