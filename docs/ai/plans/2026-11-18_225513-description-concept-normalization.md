---
status: completed
created: 2026-11-18
task: 1/1
required_gates:
  architect_review: true
  memory_update: true
---

# Description concept normalization Implementation plan

**Goal:** The `Descripción` column and the `fetch` JSON show only the BBVA transaction concept (the last non-blank `//` segment), while dedupe ids keep hashing the raw provider description so existing `_sync` ledgers remain valid (GitHub issue #21).

**Architecture:** `EnableBankingClient` already resolves one raw description from remittance lines, counterparty name, or note. The change splits display normalization from identity: `rawDescription(JsonObject)` keeps the existing resolution and feeds `canonicalFields()`, while `description(JsonObject)` returns the trimmed concept and feeds `Transaction.description`. `Main` (fetch JSON) and `SheetRow` (sync column) consume `Transaction.description`, so both surfaces get the same trimmed value without touching Sheets, ordering, tabs, or dedupe.

**Tech Stack:** Java 21, JUnit 5, JaCoCo 80% line gate, Gson (already used by tests). No new dependency and no CLI surface change.

**Scope classification:** `single-task`

## Decisions

- **Adopt issue option 1 (recommended): ids stay stable.** `canonicalFields()` hashes the raw provider description, not the display-normalized concept. Existing `_sync` ids (`eb:<reference>:<hash>`, `eb:<transaction_id>:<hash>`, `eb:sha256:<hash>`) therefore do not change, and no reset, migration, or one-off re-append is needed. Option 2 (accept id changes) is out of scope.
- **Display rule:** if the resolved description contains `//`, split on it, trim each segment, and return the last non-blank segment. If there is no `//`, return the text verbatim (including surrounding whitespace). If no segment survives trimming, fall back to the full raw text so the column is never blanked by normalization.
- **Scope stays display-only:** no changes to the four-column layout, `USER_ENTERED`/`RAW` split, date handling, ascending `Fecha` order, monthly tabs, account labels, or the `_sync` ledger.
- **No new ADR:** the hidden-ledger/dedupe contract in ADR-0008 is unchanged. The display-versus-identity split is recorded as an architecture invariant and in the plan/log.

## Current facts (verified)

- `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:177-181`: `mapTransaction()` builds `new Transaction(id, description(t), signedAmount(t), currency, madeOn(t))`; `description()` is the only description source for the domain object.
- `EnableBankingClient.java:191-202`: `transactionId()` always derives from `canonicalFields(t)`; reference-based ids append a 16-hex hash and the fallback id is `eb:sha256:<full hash>`.
- `EnableBankingClient.java:215-219`: `canonicalFields()` includes `description(t)` in the hashed string (`date|amount|currency|description|reference_number`).
- `EnableBankingClient.java:239-270`: `description()` joins `remittance_information` lines with spaces, else uses `creditor`/`debtor` name by direction, else `note`; it returns the raw text today.
- `src/main/java/bank2sheets/model/Transaction.java:13-18`: `Transaction` is a record with `id`, `description`, `amount`, `currency`, `madeOn`; no other description transformation exists.
- `src/main/java/bank2sheets/Main.java:259-262`: `sync` puts `transaction.description()` into `SheetRow.descripcion`; `Main.java:319`: `fetch` writes the same value under `"description"`.
- `src/main/java/bank2sheets/sheets/SheetRow.java:35-37`: the visible row writes `descripcion` via `literal(...)`; `GoogleSheetsClient` does not rewrite it.
- `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java:189-230`: pagination test asserts raw descriptions without `//` (`Nomina agosto`, `Cafe Central`, `Suscripcion`) and builds ids with `refId`/`hashId` helpers at `:390-409`.
- `src/test/java/bank2sheets/MainCommandsTest.java:118-140`: fetch CLI test prints `"description":"Nomina agosto"` from an array remittance; `:142-178`: sync CLI test extracts the visible append body and already asserts the `Cuenta` and `Fecha` cells.
- Live docs: `README.md:253` (Google Sheets layout), `docs/ai/architecture.md:50,55,60` (dedupe/layout/append invariants), `docs/ai/README.md:9` (repository status), `docs/ai/logs/2026-11.md` (monthly log).
- `AGENTS.md` has no `Descripción` semantics claim; verify and leave unchanged.

## Tasks

### Task 1: Normalize the displayed description to the concept and preserve dedupe ids

**Objective:** A `//`-segmented provider description renders as its last non-blank trimmed segment in both `fetch` and the month tab, while the same payload keeps its pre-change `TransactionId`.

**Files:**
- Modify: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:183-219,239-270`
- Modify: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java:118-178`
- Modify: `README.md:253`
- Modify: `docs/ai/architecture.md` (invariants after line 55)
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/logs/2026-11.md` (new dated entry)
- Validate: the files above

**Step 1: Establish expected behaviour**

- `TRANSFERENCIAS // TRANSFERENCIA REALIZADA // GYM` becomes `GYM`.
- `TRANSFERENCIAS // TRANSFERENCIA REALIZADA //   GYM  ` also becomes `GYM` (segments are trimmed).
- `A // B //   ` becomes `B` (a trailing blank segment is skipped).
- `Nomina agosto` and `  Nomina agosto  ` are returned verbatim (no `//` means no change, including surrounding whitespace).
- `  //   ` falls back to the full raw text because every segment is blank.
- For a payload whose raw description is `TRANSFERENCIAS // TRANSFERENCIA REALIZADA //   GYM  `, the id remains `refId("ref-1", "2026-08-01|1200.50|EUR|TRANSFERENCIAS // TRANSFERENCIA REALIZADA //   GYM  |")`; the hash is over the raw text, so a previously recorded ledger id is still a duplicate on the next sync.
- `fetch` JSON and the visible `Descripción` cell carry the same trimmed value. Layout, ordering, monthly tabs, `Cuenta`, and `_sync` behavior are unchanged.

**Step 2: Add or update validation**

- `EnableBankingClientTest` — add `descriptionUsesLastNonBlankSegmentWhileIdHashesRawText`: one `entry_reference: "ref-1"` transaction, `remittance_information: ["TRANSFERENCIAS // TRANSFERENCIA REALIZADA //   GYM  "]`, `booking_date: "2026-08-01"`, `amount: "1200.50"`, `EUR`, `CRDT`; assert `description()` is `GYM` and `id()` equals `refId("ref-1", "2026-08-01|1200.50|EUR|TRANSFERENCIAS // TRANSFERENCIA REALIZADA //   GYM  |")`. This single test locks both the display rule and option 1.
- `EnableBankingClientTest` — add `descriptionWithoutSeparatorIsReturnedVerbatim`: remittance `["  Nomina agosto  "]`; assert the exact string is unchanged.
- `EnableBankingClientTest` — add `descriptionSkipsTrailingBlankSegments`: remittance `["A // B //   "]`; assert `B`.
- `EnableBankingClientTest` — add `descriptionFallsBackToRawTextWhenAllSegmentsAreBlank`: remittance `["  //   "]`; assert the exact raw string.
- `MainCommandsTest.enableBankingFetchPrintsNormalizedTransactions` (`:118`): change the stubbed remittance to `["TRANSFERENCIAS // TRANSFERENCIA REALIZADA // GYM"]` and assert stdout contains `"description":"GYM"`; keep the `"id":"eb:ref-1:` assertion.
- `MainCommandsTest.enableBankingSyncAppendsThenIsIdempotent` (`:142`): use a test-local `//` transaction payload instead of `NOMINA_TRANSACTION_JSON`, then assert the visible append cell at index `2` is `"'GYM"` (the `literal(...)` apostrophe is part of the wire value). Keep the existing `Cuenta`/`Fecha` assertions and the ledger-id round trip so idempotency remains covered.
- Existing pagination expectations for descriptions without `//` stay unchanged and must still pass.

**Step 3: Write minimal implementation**

- `EnableBankingClient`:
  - Rename the current `description(JsonObject)` body to `rawDescription(JsonObject)` with Javadoc: “Raw provider description used for dedupe ids: remittance lines joined, else creditor/debtor name by direction, else note.”
  - Add `private static String description(JsonObject t) { return concept(rawDescription(t)); }` with Javadoc: “Display description: the transaction concept.”
  - Add `private static String concept(String text)`:
    - if `!text.contains("//")`, return `text` verbatim;
    - split on `//`, iterate from the last segment to the first, `trim()` each, return the first non-empty segment;
    - if none is non-empty, return the original `text`.
  - Change `canonicalFields()` (`:218`) to use `rawDescription(t)` and update its Javadoc to say the hash uses the raw description, so display normalization never invalidates ids.
  - Keep `mapTransaction()` calling `description(t)`; leave `Transaction`, `Main`, `SheetRow`, and `GoogleSheetsClient` untouched.
  - Update the `transactionId()` Javadoc only if needed to state that the hash suffix uses the raw description.
- `README.md:253`: in the Google Sheets layout paragraph, state that `Descripción` shows the concept: when the provider text contains `//` segments (e.g. `TRANSFERENCIAS // TRANSFERENCIA REALIZADA // GYM`), only the last non-blank trimmed segment is written; text without `//` is unchanged; an all-blank split falls back to the full text. Add that `fetch` JSON and the sheet use the same value and that dedupe ids are unaffected.
- `docs/ai/architecture.md`: add one invariant bullet near line 55: `Descripción` display normalization (last non-blank `//` segment, verbatim without `//`, raw fallback) and the identity rule (canonical dedupe ids hash the raw provider description, so existing `_sync` ids stay valid and no reset is required).
- `docs/ai/README.md:9`: prepend the new latest-change sentence (`Descripción` concept normalization with stable raw-text dedupe ids, issue #21) and keep the prior rename sentence as “Earlier:”.
- `docs/ai/logs/2026-11.md`: add `## 2026-11-18 - Description concept normalization` with what changed, the option-1 id-stability decision, test result, commit hash, and the human check below.
- `AGENTS.md`: verify it contains no live `Descripción` semantics claim; leave unchanged.

**Step 4: Run test to verify pass**

Run:
```powershell
.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest'
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'
.\scripts\mvn.ps1 test
git diff --check
```

Expected: targeted suites green; full suite green with the JaCoCo 80% line gate (`BUILD SUCCESS`); `git diff --check` clean; `git diff --name-only` lists only `EnableBankingClient.java`, the two test files, `README.md`, `docs/ai/architecture.md`, `docs/ai/README.md`, `docs/ai/logs/2026-11.md`, and this plan.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java src/test/java/bank2sheets/MainCommandsTest.java README.md docs/ai/architecture.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-18_225513-description-concept-normalization.md
git commit -m "feat: show only the transaction concept in Descripción (issue #21)"
```

## Validation ownership

| Validation | Owner | Command / procedure | Expected |
|---|---|---|---|
| Client normalization and id stability | agent | `.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest'` | Green; trimmed concept, verbatim no-separator text, trailing-blank skip, all-blank fallback, and raw-text id assertions pass |
| CLI fetch/sync surfaces | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'` | Green; fetch JSON and sync `Descripción` cell both carry the trimmed concept; idempotency assertion still passes |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` | `BUILD SUCCESS`; JaCoCo line ratio ≥ 0.80 |
| Diff hygiene | agent | `git diff --check` and `git diff --name-only` | No whitespace errors; only the expected code/test/doc/plan files changed |
| Real BBVA description | human | Run `.\scripts\run.ps1 fetch` once and inspect the JSON `description` values (one bank access; no `sync` needed because ids are unchanged) | Real BBVA descriptions show the concept (e.g. `GYM`); text without `//` is unchanged. If the bank allowance is tight, defer to the next scheduled sync instead |

## Risks and assumptions

- **Real payload shape:** the issue shows `//` in BBVA remittance text. The rule is applied to the resolved description (remittance, counterparty, or note), so a `//` in any of those sources is normalized; this is intentional.
- **Provider text drift:** ids remain stable only while the raw provider description is stable. A provider-side description change already changed ids before this plan and is not made worse by it.
- **No reset:** because `canonicalFields()` keeps the raw text, existing `_sync` rows stay valid. If option 2 were chosen instead, the plan would need a separate reset/migration and a different acceptance path.
- **Whitespace semantics:** “verbatim without `//`” intentionally preserves leading/trailing spaces; only segments around `//` are trimmed. This matches issue #21’s “tal cual”.
- **No extra dependency or abstraction:** the helper is private in `EnableBankingClient`; no new model field, CLI flag, or Sheets change.
- **Admin issue state:** issue #21 remains claimed (`status:doing`) and is not mutated by this planning turn.

## Required gates

- `architect_review: true` — confirm the display/identity split preserves the ADR-0008 hidden-ledger dedupe contract and that no reset or migration is implied.
- `memory_update: true` — `docs/ai/architecture.md`, `docs/ai/README.md`, and `docs/ai/logs/2026-11.md` are updated in Task 1.
