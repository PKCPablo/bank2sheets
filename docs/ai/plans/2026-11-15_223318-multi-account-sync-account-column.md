---
status: completed
created: 2026-11-15
task: 3/3
required_gates:
  architect_review: false
  memory_update: true
---

# Multi-account sync account column implementation plan

**Goal:** Make multi-account syncs unambiguous in one spreadsheet by stamping every appended row with the source account label, while preserving global `TransactionId` dedupe and leaving one-spreadsheet-per-account as documented per-instance usage.

**Architecture:** Extend the fixed sheet layout from five to six columns (`Fecha, Descripción, Importe, Moneda, TransactionId, Cuenta`). `Main.sync()` resolves provider account names once per run via `TransactionSource.listAccounts()` and attaches the label to each `SheetRow`; `GoogleSheetsClient` writes/backfills the sixth header cell and keeps reading dedupe ids from the unchanged `E2:E` range. No routing configuration, new dependency, or provider change is introduced.

**Tech Stack:** Java 21, Maven (JUnit 5, Gson, JaCoCo 80% line gate), PowerShell wrapper `.\scripts\mvn.ps1`.

**Scope classification:** `multi-task`

## Discovery (verified in the repository, 2026-11-15)

- `src/main/java/bank2sheets/sheets/SheetRow.java:14-24` is a five-component record; `values()` fixes the column order used by the writer.
- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:45-51` fixes `RANGE="A1:E"`, `HEADER_RANGE="A1:E1"`, `IDS_RANGE="E2:E"` and the five-value header; `:96-105` writes the header only when `hasHeader` is false; `:112-124` detects the header solely from cell `A1`; `:198-205` only appends (no values update yet); `:207-215` builds the values/append URLs.
- `src/main/java/bank2sheets/Main.java:219-248` is the flat `sync()` loop: it reads existing ids, fetches each configured account, dedupes globally, and builds `SheetRow`s without an account label. `:163-171` already shows the account-name precedent used by `fetch` (`listAccounts()` map plus a blank-name fallback to the id).
- `src/main/java/bank2sheets/model/Account.java:9` exposes provider `id` and display `name`; `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:142-168` implements `listAccounts()` from `GET /sessions/{id}` plus per-account details.
- `src/main/java/bank2sheets/config/SheetsConfig.java:11` remains one spreadsheet id per run; `src/main/java/bank2sheets/config/AppConfig.java:82-92` already supports multiple `BANK2SHEETS_ACCOUNTS` entries. No new configuration is needed.
- Test seams: `src/test/java/bank2sheets/http/FakeHttp.java:34-41` stubs by path+query and records the HTTP method, so a new `PUT values/F1` call is testable without a new fake. `src/test/java/bank2sheets/sheets/SheetsTestSupport.java` and `MainCommandsTest.java:33-35,137-171,336-341` hold the current five-column header/append stubs that must move to six columns.
- Docs to update: `README.md:251-253` (Google Sheets layout) and the `BANK2SHEETS_ACCOUNTS` row at `:207`; `docs/ai/architecture.md:50-51` (invariants); `AGENTS.md` project overview/boundaries mention multi-account appends; `docs/ai/README.md` status and `docs/ai/logs/2026-11.md`.
- Baseline validation command: `.\scripts\mvn.ps1 test` (JUnit suite plus JaCoCo 80% line gate bound to the `test` phase). No formatter/linter is configured.

## Decisions

1. **Column position:** append `Cuenta` as column F. This preserves the existing header prefix, keeps `TransactionId` at column E, and leaves the dedupe read at `E2:E` untouched.
2. **Column value:** the provider account display name when non-blank, otherwise the provider account id. Unknown/unlisted account ids also fall back to the id. Do not append the id when a name exists; duplicate display names are an accepted limitation (recorded as a risk and cheap follow-up if it becomes real).
3. **Header backfill:** when `A1` is already `Fecha` but `F1` is missing or blank, write only `F1 = "Cuenta"` with `PUT .../values/F1?valueInputOption=RAW`. Do not rewrite `A1:E1`, do not insert a row, and do not overwrite a non-blank `F1`.
4. **Sequencing:** issue #12 lands first. Issue #8 (month tabs) must later keep `Cuenta` in every month tab and keep dedupe per tab; this plan does not implement month tabs.
5. **No new configuration:** one spreadsheet per run stays; the one-account-to-one-spreadsheet setup is documented as one instance/fork per account.

## Tasks

### Task 1: Add the Cuenta column and legacy-header backfill to the Sheets writer

**Objective:** New rows carry six columns and an existing five-column sheet gets `F1 = "Cuenta"` without disturbing historical rows or the `E2:E` dedupe read.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/SheetRow.java:6-24`
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:34-51,74-124,188-215`
- Validate: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`

**Step 1: Establish expected behaviour** (owner: agent)

- The header is `Fecha, Descripción, Importe, Moneda, TransactionId, Cuenta`; fresh sheets receive it in one append to `A1:F`.
- Data appends use the `A1:F` range and include the `cuenta` value as the sixth cell.
- `existingTransactionIds()` still reads only `E2:E`, so global dedupe is unchanged.
- When the header row already exists with five columns, exactly one `PUT` writes `F1 = "Cuenta"` before the data append; no header row is appended in the middle of the sheet.
- When `F1` already contains a non-blank value (including `Cuenta`), no write to `F1` happens.
- `appendRows(List.of())` still performs no HTTP calls, including no backfill.

**Step 2: Add or update validation** (owner: agent)

- Update the existing stubs/assertions in `GoogleSheetsClientTest` from `A1:E1`/`A1:E` to `A1:F1`/`A1:F`, and assert the six-value header body.
- Extend `appendRowsWritesHeaderThenDataWithAmountsAsNumbers` to construct six-column `SheetRow`s and assert cell index 5.
- Add `appendRowsBackfillsCuentaHeaderOnLegacyFiveColumnSheet`: stub `A1:F1` with the five-value header and `PUT /sheet-1/values/F1?valueInputOption=RAW` returning `{"spreadsheetId":"sheet-1"}`; run one append; assert request order (token, header read, `F1` update, data append), the `PUT` method, the update body `{"values":[["Cuenta"]]}`, and that only one data append follows.
- Add `appendRowsSkipsBackfillWhenCuentaHeaderIsPresent`: stub a six-value header; assert no `PUT` request.
- Keep the existing no-op, token-caching, and error-path tests passing with the new ranges.

**Step 3: Write minimal implementation** (owner: agent)

- `SheetRow`: add `String cuenta` after `transactionId`, append it to `values()`, and update the Javadoc to the six-column order.
- `GoogleSheetsClient`:
  - Change `RANGE` to `A1:F`, `HEADER_RANGE` to `A1:F1`, keep `IDS_RANGE = "E2:E"`.
  - Add `"Cuenta"` to `HEADER_VALUES` and update the class/Javadoc layout description.
  - Replace the boolean-only `hasHeader` check with a small header-row helper (for example `readHeaderRow(token)` returning the first `JsonArray` or `null`) used by `appendRows`.
  - In `appendRows`: if there is no header (or `A1` is not `Fecha`, preserving today's behavior), append the full six-value header; otherwise, if the row has fewer than six cells or the sixth cell is missing/blank, call a new `updateValues(token, "F1", body)` helper that issues `PUT` to `values/F1?valueInputOption=RAW` with `{"values":[["Cuenta"]]}`. Never write when `F1` is non-blank.
  - Keep `appendValues`, `toValuesJson`, error handling, and token caching unchanged apart from the new range constants.
- Update `SheetsTestSupport`/`MainCommandsTest` header fixtures only where they are compiled or asserted against the six-column layout (Task 2 adjusts the sync stubs it touches).

**Step 4: Run test to verify pass** (owner: agent)

Run:

```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: the client tests pass, including the legacy-backfill assertion; the full suite passes with JaCoCo line coverage >= 80%.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/sheets/SheetRow.java src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java
git commit -m "feat: add Cuenta column to the Google Sheets layout (issue #12)"
```

### Task 2: Stamp synced rows with the source account label

**Objective:** `sync` resolves account display names once per run, writes a usable label on every appended row (name, else id), and remains idempotent across multiple accounts.

**Files:**
- Modify: `src/main/java/bank2sheets/Main.java:213-248`
- Validate: `src/test/java/bank2sheets/MainCommandsTest.java:33-35,137-171,298-341`

**Step 1: Establish expected behaviour** (owner: agent)

- With two configured accounts, one `sync` appends both accounts' transactions to one tab, each row carrying its own `Cuenta` label.
- A blank provider account name falls back to the provider account id; an id missing from `listAccounts()` also falls back to the configured id.
- Running `sync` twice still appends zero duplicates across both accounts because dedupe remains global on `TransactionId`.
- `fetch` output and its account labeling are unchanged.
- `sync` now depends on `listAccounts()`; a provider failure there exits 1 with the existing `sync: ...` error prefix (documented as an added failure mode).

**Step 2: Add or update validation** (owner: agent)

- Update `stubReadySheet` and `HEADER_JSON` to the six-column header/`A1:F` append stub.
- Extend `enableBankingSyncAppendsThenIsIdempotent` with `/sessions/sess-eb-1` and `/accounts/uuid-1/details` stubs and assert the appended row's index 5 is `Cuenta Nominas`; keep the existing `E2:E` re-sync assertion.
- Add `enableBankingSyncLabelsRowsPerAccountAndStaysIdempotent`: config with `uuid-1` and `uuid-2` (add an overload of `enableBankingConfig` or build the `AppConfig` inline); stub both details names and both transaction endpoints; run `sync`; assert two appended rows with distinct labels; collect both transaction ids, stub `E2:E` with them, run `sync` again, and assert `appended 0 new row(s), skipped 2 duplicate(s)`.
- Add `enableBankingSyncFallsBackToAccountIdWhenNameBlank`: stub the details endpoint with a blank/missing name; assert the appended label is `uuid-1`.
- Leave the Sheets-config, missing-connection, and Sheets-read-error tests unchanged; they fail before `listAccounts()` is reached.

**Step 3: Write minimal implementation** (owner: agent)

- In `Main.sync()`, after creating `source`, build a `Map<String, String> accountLabels` from one `source.listAccounts()` call using `account.name().isBlank() ? account.id() : account.name()`.
- Inside the per-account loop, compute `String label = accountLabels.getOrDefault(account.providerAccountId(), account.providerAccountId())` and pass it as the new sixth `SheetRow` component.
- Do not change `fetch()`, `existingTransactionIds()`, the dedupe set, the flat fetch/append flow, or the sync summary text.
- Update the `sync()` Javadoc to state that rows are labeled with the source account and dedupe is global.

**Step 4: Run test to verify pass** (owner: agent)

Run:

```powershell
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: CLI tests pass with distinct labels, id blank-name fallback, and the second sync skipping all duplicates; full suite green with the 80% line gate.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/MainCommandsTest.java
git commit -m "feat: label synced rows with the source account (issue #12)"
```

### Task 3: Document the account column and the per-instance 1:1 pattern

**Objective:** README, architecture, `AGENTS.md`, and `docs/ai` memory describe the six-column layout, the label fallback, and the one-account-per-instance deployment pattern without adding routing configuration.

**Files:**
- Modify: `README.md:207,251-253`
- Modify: `docs/ai/architecture.md:48-53`
- Modify: `AGENTS.md` (project overview and/or boundaries)
- Modify: `docs/ai/README.md` (Repository Status)
- Modify: `docs/ai/logs/2026-11.md`
- Modify: this plan's frontmatter (`status`, `task`)

**Step 1: Establish expected behaviour** (owner: agent)

- Docs state the header is `Fecha, Descripción, Importe, Moneda, TransactionId, Cuenta` and that `Cuenta` is the provider account name (fallback: account id).
- Docs state that `BANK2SHEETS_ACCOUNTS` may list multiple accounts and all rows go to one spreadsheet tab, with `TransactionId` as the global dedupe key.
- Docs state that one bank account per spreadsheet is a deployment pattern: run one instance/fork per account with its own `BANK2SHEETS_ACCOUNTS`, `GOOGLE_SPREADSHEET_ID`, and its own scheduled workflow/secrets. No account-to-spreadsheet mapping variable exists.
- Existing five-column spreadsheets are described as getting `F1 = "Cuenta"` backfilled on the next sync that appends rows; historical rows keep the column blank.
- No secrets, `.env` values, state files, or personal ids are added.

**Step 2: Add or update validation** (owner: agent)

Run after editing:

```powershell
Select-String -Path README.md,AGENTS.md,docs/ai/architecture.md -Pattern 'Cuenta|TransactionId|per-instance|one instance'
Select-String -Path README.md,docs/ai/architecture.md -Pattern 'Fecha.*Moneda.*TransactionId(?!.*Cuenta)'
.\scripts\mvn.ps1 test
```

Expected: the first search finds the layout and 1:1 guidance; the second finds no five-column-only live description (historical logs/ADRs are exempt); full suite remains green with the JaCoCo gate.

**Step 3: Write minimal implementation** (owner: agent)

- `README.md`: update the Google Sheets layout section with the six columns, label semantics, global dedupe, legacy-header backfill, and the per-instance 1:1 guidance; adjust the `BANK2SHEETS_ACCOUNTS` table row to mention that multiple accounts are labeled by the `Cuenta` column.
- `docs/ai/architecture.md`: update the invariant that lists the fixed columns and add a short invariant/boundary bullet that multi-account rows are labeled and one-spreadsheet-per-account is achieved by separate instances, not routing code.
- `AGENTS.md`: add one concise clause to the project overview (or boundaries) stating rows carry a source-account `Cuenta` column and 1:1 uses one instance/fork per account; do not restate the full table.
- `docs/ai/README.md`: append a short repository-status sentence (issue #12, plan path, layout/1:1 behavior).
- `docs/ai/logs/2026-11.md`: add a `## 2026-11-15 — Multi-account sync account column` entry with the shipped behavior and the issue #8 sequencing note.
- Set this plan's frontmatter to `status: completed` and `task: 3/3` only after the full suite and the doc searches pass.

**Step 4: Run test to verify pass** (owner: agent)

Run: `.\scripts\mvn.ps1 test`
Expected: all tests pass, JaCoCo line coverage >= 80%, no new dependency.

**Step 5: Commit**

```powershell
git add README.md AGENTS.md docs/ai/architecture.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-15_223318-multi-account-sync-account-column.md
git commit -m "docs: document multi-account sheet layout (issue #12)"
```

## Validation summary

| Validation | Owner | Command / procedure | Expected |
| --- | --- | --- | --- |
| Sheets writer tests | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest' '-Djacoco.skip=true'` | pass, including legacy `F1` backfill |
| CLI sync tests | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest' '-Djacoco.skip=true'` | distinct labels, id fallback, idempotent across accounts |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` at the end of every task | BUILD SUCCESS, JaCoCo >= 80% |
| Docs consistency | agent | `Select-String` commands in Task 3 Step 2 | no stale five-column-only description |
| Real spreadsheet smoke test | human (owner) | Use a disposable spreadsheet with the old five-column header, configure two accounts, run `sync`, then run it again; confirm `F1 = Cuenta`, labeled rows, and `appended 0 new row(s)` | recommended before closing issue #12; not a code gate |

## Acceptance criteria mapping

- Multi-account single-tab rows labeled: Task 2 (client-level label) + Task 1 (column F).
- Header/backfill exactly once without a mid-sheet header row: Task 1.
- Repeated sync idempotent across accounts: Task 2 (global `E2:E` unchanged).
- Existing five-column sheets keep working: Task 1 (`E2:E` unchanged, `F1` update only, no historical rewrite).
- Blank name falls back to id: Task 2.
- 1:1 documented as per-instance, no routing config: Task 3 + unchanged `SheetsConfig`.
- Full suite and JaCoCo gate green: Task 1-3 Step 4.
- README/architecture/AGENTS describe the layout and 1:1 pattern: Task 3.
- No secrets committed: Tasks 1-3 file lists only.

## Out of scope / follow-ups

- Per-account spreadsheet routing, a `BANK2SHEETS_SHEETS` mapping, or multiple spreadsheet ids per run.
- One tab per account; issue #8 month tabs must preserve `Cuenta` and per-tab dedupe when it lands.
- Backfilling `Cuenta` values on historical rows or rewriting existing data.
- Provider changes, `Transaction`/provider parsing changes, `fetch` JSON/output changes, and issue #7 sequencing.
- New runtime dependencies or new environment variables.
- Distinguishing accounts that share an identical display name (accepted limitation; append the id in a follow-up only if it becomes a real ambiguity).

## Risks

- `sync` now calls `listAccounts()` (session plus account-details requests) on every run; a provider failure there is a new exit-1 path. Keep the existing `sync:` error prefix and document the added calls.
- Writing `F1` uses the values `PUT` endpoint, not append; a wrong range or missing `valueInputOption` would fail at runtime. The fake-HTTP tests pin the method, URL, and body before any real call.
- If `F1` already holds unrelated data, the backfill intentionally skips it rather than clobbering it; the header may remain non-standard for that sheet and the owner must fix it manually.
- Header backfill only happens when there is at least one row to append (unchanged early return for empty input); a sync with zero new rows does not repair the header.
- `Cuenta` duplicates when two accounts share a display name; accepted for now (see Decisions).
