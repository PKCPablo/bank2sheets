---
status: completed
created: 2026-11-19
task: 2/2
required_gates:
  architect_review: false
  memory_update: true
---

# Chronological intra-day transaction order Implementation plan

**Goal:** Month tabs stay ascending by `Fecha`, and rows that share the same `Fecha` are written in chronological order — reverse of the provider's newest-first list — deterministically across runs (GitHub issue #22).

**Architecture:** `Main.sync` already groups transactions per month and hands each batch to `GoogleSheetsClient.appendRows`, which stable-sorts by `SheetRow.fecha`. The only missing tiebreaker is the provider index within a date. The fix reverses each account's fetched list in `Main.sync` before grouping (`List.reversed()`, Java 21, no new dependency), so the existing stable sort keeps same-day rows in reverse provider order while still guaranteeing ascending dates across days. `fetch` keeps dumping raw provider order, and `EnableBankingClient`, `SheetRow`, and the Sheets writer stay untouched.

**Tech Stack:** Java 21 (`SequencedCollection.reversed()`), Gson (existing, not exercised by the change), JUnit 5 with the in-memory `FakeHttp`, JaCoCo 80% line gate.

**Scope classification:** `multi-task`

Task budget rationale: 2 tasks — one reviewable behavior change with its tests (code + end-to-end/unit validation), and one docs/memory deliverable that records the provider-ordering assumption. Splitting them follows the repo's existing convention (plans keep code and documentation as separate reviewable commits) and neither depends on touching more than three files.

## Decisions (resolved during planning)

- **Invert in `Main.sync` only.** `fetch` keeps printing the raw provider list; it is a diagnostic command and its documented contract is provider order. `EnableBankingClient.fetchTransactions` is not changed, so the `fetch` JSON stays byte-for-byte comparable with the provider.
- **Provider direction is newest-first (owner-confirmed, to be verified with a real `fetch`).** Enable Banking's API reference documents no ordering guarantee for `GET /accounts/{account_id}/transactions`, and no `fetch` dump exists in the repo. The owner confirmed newest-first and accepted a human verification gate (Gate H1) before the code lands. If `fetch` shows oldest-first, stop: the inversion direction is wrong and the plan must be re-done (keeping provider order would already be chronological).
- **No hour parsing.** The harmonized payload has date-only `booking_date`/`value_date`/`transaction_date`; `EnableBankingClient.resolvedDate()` already truncates any timestamp to 10 characters. Do not add `LocalDateTime` parsing or invent a time — the provider order is the only intra-day tiebreaker.
- **`appendRows` keeps its stable `Fecha` sort.** Reversing before grouping is equivalent to an explicit `(Fecha asc, provider index desc)` comparator, but it reuses the existing writer guarantee and adds no comparator plumbing.
- **Existing rows are out of scope.** The writer is append-only; historical tabs are not re-sorted, and a late-arriving older transaction still lands at the end of the tab (accepted, unchanged from ADR-0007).
- **Dedupe key unchanged.** `TransactionId` generation and the `_sync` ledger schema are untouched. One nuance to accept: within a single account response, reversing changes which occurrence of a duplicated id survives (now the occurrence that appears later in provider order, i.e. earlier in the reversed list) — provider ids are unique in practice, and the acceptance criterion is "no duplicates", not "first occurrence wins".
- **Independent of issue #19.** This does not move payroll rows between month tabs or insert rows; it only orders same-date rows inside a batch.

## Current facts (verified)

- `src/main/java/bank2sheets/Main.java:240-289` (`sync`): `for (Transaction transaction : source.fetchTransactions(account.providerAccountId()))` adds rows to `byMonth` in provider order (`:252-264`); `seen` is the in-run dedupe set; each month's `fresh` list is appended via `sheetsClient.appendRows(month, fresh)` (`:266-279`).
- `Main.java:224-239` is the `sync` Javadoc; line 232 currently says "Each month's new rows are appended in ascending `Fecha` order."
- `Main.java:169-192` (`fetch`) prints transactions in raw provider order — leave as is.
- `src/main/java/bank2sheets/openbanking/TransactionSource.java:33` returns `List<Transaction>`, so `List.reversed()` (Java 21 `SequencedCollection`) works directly; no compatibility shim needed.
- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:160-193` (`appendRows`): `:182-184` is `rows.stream().sorted(Comparator.comparing(SheetRow::fecha)).toList()` (stable), then the visible append (`:185-186`) and the `_sync` ledger append (`:187-190`) both use that same `sorted` order; the class Javadoc (`:52-54`) and method Javadoc (`:139-159`) say "same-date rows keep provider order".
- `src/main/java/bank2sheets/sheets/SheetRow.java:24-29` is the record `(LocalDate fecha, BigDecimal importe, String descripcion, String cuenta, String transactionId)`; visible `values()` are `[Fecha, Movimientos, Descripción, Cuenta]`.
- `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:160-175` accumulates pages in provider order; `:250-259` (`resolvedDate`) returns `YYYY-MM-DD`; `:309-312` (`madeOn`) parses only `LocalDate`.
- `src/test/java/bank2sheets/MainCommandsTest.java:377-412` (`enableBankingSyncWritesMonthRowsInDateOrder`) already models newest-first input `[08-20, 08-03]` and asserts `[08-03, 08-20]`; it is unaffected by reversal.
- `MainCommandsTest.java:290-374` covers month routing/tab creation; `:190-239` covers multi-account labels + idempotency; `:143-187` covers the ledger round trip; helpers `runEnableBanking`/`mainFor`/`stubMonthSheet`/`stubLedger`/`stubSpreadsheetTitles` are at `:664-746`; `NOMINA_TRANSACTION_JSON` is at `:36-44`.
- `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:296-313` (`appendRowsSortsRowsByDateAscending`) asserts cross-day order but has no same-date stability assertion.
- Docs to update: `README.md:253` ("same-day rows keep the provider's order"), `docs/ai/architecture.md:52` (ordering invariant), `docs/ai/decisions/ADR-0007-spanish-month-tab-persistence-key.md:18` (ordering consequence; `:32` "Ordering is batch-ascending only"), `docs/ai/README.md:9` (status), `docs/ai/logs/2026-11.md` (entry style: shipped/decision/docs/validation/commit/human validation).
- `AGENTS.md` overview says "each month's new rows appended in ascending `Fecha` order" — still true; reviewed, expected unchanged.

## Tasks

### Task 1: Reverse each account's provider list in `sync` (with tests)

**Objective:** Same-date rows in a month tab are written in chronological order (reverse of the newest-first provider list) while dates stay ascending, `fetch` stays raw, and dedupe/idempotency are unchanged.

**Files:**
- Modify: `src/main/java/bank2sheets/Main.java:224-264`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java` (new intra-day test next to `:377-412`; optional multi-account assertion near `:190-239`)
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:296-313`
- Validate: `MainCommandsTest`, `GoogleSheetsClientTest`, full suite

**Step 1: Establish expected behaviour** (owner: agent)

- Provider input `[15/08 Café, 15/08 Gym, 02/08 Nómina]` (newest-first) produces an `Agosto 2026` append whose rows are `[02/08 Nómina, 15/08 Gym, 15/08 Café]`.
- Same-date rows keep `fetch`'s list order reversed; no time is parsed and no synthetic time is used.
- `fetch` output order and content are unchanged (raw provider order).
- Multiple accounts: each account's list is reversed independently; the month append is still date-ascending, and same-date rows appear in account iteration order with each account's reversed sequence inside it.
- Dedupe: every `TransactionId` appears once in the visible append and once in the `_sync` append; a second `sync` appends zero rows (existing behavior, re-verified).
- Existing tabs are never read or rewritten; `appendRows`'s stable `Fecha` sort remains the inter-day guarantee.

**Step 2: Add or update validation** (owner: agent)

`MainCommandsTest`:
- Add `enableBankingSyncReversesSameDayProviderOrder`: stub one account with three `BOOK` transactions as above (same month, two dates), `stubSpreadsheetTitles(sheets, "Agosto 2026")`, `stubMonthSheet(sheets, "Agosto 2026")`, run `sync` once. Assert the `'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED` body has three rows in the expected order; assert `values.get(i).get(0)` (`Fecha`) is `2026-08-02`, `2026-08-15`, `2026-08-15` and the description cells (`index 2`, with the leading `'`) read `'Nómina`, `'GYM`, `'Café` in that order. This is the regression that fails today (today the two 15/08 rows come out `Café`, `Gym`).
- Extend `enableBankingSyncLabelsRowsPerAccountAndStaysIdempotent` (or add `enableBankingSyncReversesOrderPerAccount`): give `uuid-1` two same-day 15/08 rows newest-first and `uuid-2` two same-day 16/08 rows newest-first, then assert the single month append reads `[15/08 u1-old, 15/08 u1-new, 16/08 u2-old, 16/08 u2-new]` with the `Cuenta` cells grouped per account. Keep the existing ledger round trip/second-run assertion in the test you extend; if a new test is cleaner, keep the old one untouched and add the two-account order test separately.
- Do not change `enableBankingSyncWritesMonthRowsInDateOrder`; it must stay green (proves cross-day order is unaffected).

`GoogleSheetsClientTest`:
- Extend `appendRowsSortsRowsByDateAscending` with two same-date rows in a known input order and assert the writer preserves that input order (stable sort) — this locks the guarantee `Main` relies on without changing writer code.

**Step 3: Write minimal implementation** (owner: agent)

`Main.sync` (`:252-264`): fetch into a local list and iterate its reversed view, keeping the loop body unchanged:

```java
for (AccountConfig account : resolved.accounts()) {
    String label = accountLabel(account, labels);
    List<Transaction> accountTransactions = source.fetchTransactions(account.providerAccountId());
    // The provider lists newest first; reverse so same-date rows read chronologically.
    for (Transaction transaction : accountTransactions.reversed()) {
        fetched++;
        ...
    }
}
```

- Do not change `GoogleSheetsClient.appendRows`, `SheetRow`, `EnableBankingClient`, `fetch`, or any dedupe code.
- Update the `sync` Javadoc (`:224-239`): keep "each month's new rows are appended in ascending `Fecha` order" and add that same-date rows are written in reverse provider order (chronological, under the newest-first provider contract), and that `fetch` is unchanged.
- Keep the change to one production file.

**Step 4: Run test to verify pass** (owner: agent)

```powershell
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'
.\scripts\mvn.ps1 test
```

Expected: targeted suites green; new intra-day assertion fails before Step 3 and passes after; full suite green with `BUILD SUCCESS` and the JaCoCo 80% line gate met.

**Step 5: Commit** (owner: agent)

```powershell
git add src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/MainCommandsTest.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java
git commit -m "fix: keep intra-day transaction order chronological (issue #22)"
```

### Task 2: Document the intra-day ordering contract and provider assumption

**Objective:** README, architecture invariants, ADR-0007, and AI memory state the new same-day rule and the newest-first provider assumption, without changing code.

**Files:**
- Modify: `README.md:253`
- Modify: `docs/ai/architecture.md:52-53`
- Modify: `docs/ai/decisions/ADR-0007-spanish-month-tab-persistence-key.md:18-19,27-33`
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/logs/2026-11.md` (new dated entry under the existing 2026-11-19 entry)
- Modify: `docs/ai/plans/2026-11-19_105115-provider-intraday-order.md` (frontmatter `status: completed`, `task: 2/2`)
- Validate: `README.md`, `docs/ai/**` grep checks

**Step 1: Establish expected behaviour** (owner: agent)

- User docs say rows are written ascending by `Fecha`, and that same-date rows read chronologically (oldest movement first) by reversing the provider's newest-first list; `fetch` keeps raw provider order.
- Architecture records the invariant: `Main.sync` reverses each account's list before month grouping; `appendRows` stable-sorts by `Fecha`; existing rows are never rewritten.
- ADR-0007 gains a dated amendment (original text kept as history, matching the ADR-0008 convention) that records the newest-first assumption, the reversal, and the risk: if the provider changes direction, same-day order flips and the reversal must be revisited.
- AI status/log name the change, its validation, and the pending human checks.

**Step 2: Add or update validation** (owner: agent)

- Grep for stale wording and confirm the only remaining "same-day rows keep provider order" text is historical (dated plans/logs/ADR history):
  ```powershell
  Select-String -Path README.md,docs/ai/architecture.md,docs/ai/decisions/ADR-0007-spanish-month-tab-persistence-key.md -Pattern 'same-day|provider order|ascending'
  ```
- Expected: README/architecture/ADR read "reverse provider order (chronological)" plus the newest-first assumption; no live claim that same-day rows keep the provider's raw order.

**Step 3: Write minimal documentation changes** (owner: agent)

- `README.md:253`: replace "Rows within each append are written in ascending date order (same-day rows keep the provider's order)" with the ascending-date + same-date-chronological wording and the `fetch`-keeps-raw-order note; leave the rest of the Google Sheets layout paragraph intact.
- `docs/ai/architecture.md:52`: update the append-order invariant: batch sorted by `LocalDate` ascending (stable); before grouping, `Main.sync` reverses each account's fetched list because the provider is newest-first, so same-date rows are written chronologically; existing rows are still never read or rewritten.
- `docs/ai/decisions/ADR-0007-...md`: append `## Amendment (2026-11-19): same-day ordering` stating the provider assumption (Enable Banking documents no order; owner observed newest-first), the `List.reversed()` point in `Main.sync`, that batch-ascending/append-only behavior is unchanged, and the risk/consequence if provider order changes.
- `docs/ai/README.md:9`: move the current "Latest change" to "Earlier:" and prepend this change (plan path + issue #22).
- `docs/ai/logs/2026-11.md`: add a `## 2026-11-19 - Chronological intra-day order` entry: shipped summary, the newest-first decision, docs updated, validation results (test counts), `Commit: pending (delivery-owned)`, and the pending human validations (Gate H1 pre-check and the spreadsheet smoke test).
- Update this plan's frontmatter to `status: completed`, `task: 2/2`.
- `AGENTS.md` reviewed; expected unchanged.

**Step 4: Run verification** (owner: agent)

```powershell
Select-String -Path README.md,docs/ai/architecture.md,docs/ai/decisions/ADR-0007-spanish-month-tab-persistence-key.md -Pattern 'same-day|provider order|ascending|revers'
git diff --check
```

Expected: intended wording present, no trailing whitespace, no live stale claim.

**Step 5: Commit** (owner: agent)

```powershell
git add README.md docs/ai/architecture.md docs/ai/decisions/ADR-0007-spanish-month-tab-persistence-key.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-19_105115-provider-intraday-order.md
git commit -m "docs: record chronological intra-day ordering (issue #22)"
```

## Validation ownership

| Validation | Owner | Command | Expected |
| --- | --- | --- | --- |
| Provider order confirmation (Gate H1, before Task 1 code lands) | human | `.\scripts\mvn.ps1 package` then `.\scripts\run.ps1 fetch` | Printed JSON lines are newest-first: the first transaction's resolved date is the most recent, and dates do not increase down the list. If oldest-first, stop and re-plan before implementing the reversal. |
| Intra-day CLI regression | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'` | New same-date test green; cross-day test `enableBankingSyncWritesMonthRowsInDateOrder` still green |
| Writer stability | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'` | Same-date input order preserved by the stable sort; all client tests green |
| Full suite + coverage | agent | `.\scripts\mvn.ps1 test` | 0 failures/errors, `BUILD SUCCESS`, JaCoCo 80% line gate met |
| Docs cross-check | agent | `Select-String` over `README.md`, `docs/ai/architecture.md`, ADR-0007 | New wording present; only historical dated docs mention the old rule |
| Real spreadsheet smoke | human | Point `GOOGLE_SPREADSHEET_ID` at a disposable sheet, run `.\scripts\run.ps1 sync` once with a day that has two or more transactions | Rows ascending by `Fecha`; same-day rows read oldest→newest; second `sync` appends 0. Each sync spends one bank access — at most two runs. |

## Out of scope

- Re-sorting existing/historical rows or tabs.
- Changing `enableBankingClient`-level ordering or the `fetch` JSON order.
- Parsing or synthesizing transaction times.
- Payroll-first-of-month behavior (issue #19).
- Category classification (issue #15).
