---
status: completed
created: 2026-11-18
task: 1/1
required_gates:
  architect_review: true
  memory_update: true
---

# Rename the visible `Importe` column to `Movimientos` Implementation plan

**Goal:** Month tabs show the header `Fecha, Movimientos, Descripción, Cuenta`, and the header guard treats the old `Importe` name as an unexpected (legacy) header (GitHub issue #20).

**Architecture:** The visible header is one constant in `GoogleSheetsClient` (`HEADER_VALUES`); the `A1:D1` guard compares that constant per cell and interpolates it into the error message. Renaming the constant is the whole behavior change: fresh tabs get `Movimientos`, legacy tabs fail fast with the existing reset message (which now lists `Movimientos`). The decision from clarification: **minimal change — no legacy `Importe` alias**; the owner will delete the previously generated tabs, so the existing reset path is enough.

**Tech Stack:** Java 21, JUnit 5, JaCoCo 80% line gate; no new dependency, no new CLI surface.

**Scope classification:** `single-task`

## Decisions (from clarification)

- **No backward-compatible alias:** `Importe` is not accepted as a header. Any existing tab with `Importe` is a legacy header and keeps hitting the existing fail-fast guard with the reset instruction.
- **No migration code:** the writer stays append-only; the owner deletes/archives old month tabs and `_sync` (or uses a fresh spreadsheet) as documented today.
- **Internal names unchanged:** the `SheetRow` record component and parameter stay `importe` (not user-visible); only the visible header/value meaning changes. No rename of the domain field, no new constant, no option.
- **Docs** follow the same minimal line: rename the column name wherever the live contract is stated, plus a dated amendment in ADR-0008.

## Current facts (verified)

- `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:77-78`: `HEADER_VALUES = List.<Object>of("Fecha", "Importe", "Descripción", "Cuenta")`; this is the only source of the visible header.
- `GoogleSheetsClient.java:154-165`: header read is `!A1:D1`; when present, each cell is compared to `HEADER_VALUES.get(i)`; on mismatch it throws `Bank2SheetsException("Tab '...' has an unexpected header; this version writes the four-column layout " + HEADER_VALUES + ". Delete or archive that tab and the '_sync' tab ...")`. The message already interpolates the constant, so it needs no edit.
- `GoogleSheetsClient.java:39` (class Javadoc) lists `Fecha, Importe, Descripción, Cuenta`; `SheetRow.java:10` (record Javadoc) lists the same order, and `:12` names `Importe` as the `BigDecimal` component.
- `src/test/java/bank2sheets/sheets/SheetsTestSupport.java:52-53`: shared `HEADER_JSON` is `{"values":[["Fecha","Importe","Descripción","Cuenta"]]}`; used by `GoogleSheetsClientTest` (`:153`, `:173`, `:195`, `:217`, `:234`, `:253`, `:381`) and `MainCommandsTest` (`:713`). Renaming it updates every stub consistently.
- `GoogleSheetsClientTest.java:272` stubs the legacy six-column header `["Fecha","Descripción","Importe","Moneda","TransactionId","Cuenta"]`; `:280` asserts the error message contains `"Importe"` (from the interpolated `HEADER_VALUES`). After the rename that assertion must expect `"Movimientos"`; the legacy fixture can stay as the historical six-column shape.
- No other `Importe` occurrence exists in `src/main`, `src/test`, or `AGENTS.md` (searched 2026-11-18).
- Live docs that state the visible column name: `README.md:253` (and the reset paragraph at `:255`), `docs/ai/architecture.md:55` and `:60`, `docs/ai/README.md:9`, and `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md:12` (Decision bullet 1) plus its 2026-11-18 amendment at `:55` (uses `Importe` as the amount column name).

## Tasks

### Task 1: Rename the visible header to `Movimientos` and record the contract change

**Objective:** Fresh month tabs are written with `Fecha, Movimientos, Descripción, Cuenta`; a tab with `Importe` is rejected by the existing guard/journal; tests and live docs state the new name.

**Files:**
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:39,77-78`
- Modify: `src/main/java/bank2sheets/sheets/SheetRow.java:10,12`
- Modify: `src/test/java/bank2sheets/sheets/SheetsTestSupport.java:53`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java:280`
- Modify: `README.md:253` (optional touch-up at `:255` only if the reset wording needs the new name)
- Modify: `docs/ai/architecture.md:55,60`
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md` (append a second dated amendment)
- Modify: `docs/ai/logs/2026-11.md` (new log entry)
- Validate: the files above

**Step 1: Establish expected behaviour**

A tab with no header receives `{"values":[["Fecha","Movimientos","Descripción","Cuenta"]]}` under `RAW` (`!A1:D` append). An existing tab whose `A1:D1` row is `Fecha, Movimientos, Descripción, Cuenta` appends rows normally. An existing tab whose second cell is `Importe` (old header) throws the existing `Bank2SheetsException`; its message lists `Fecha, Movimientos, Descripción, Cuenta` and the reset instruction, and no visible append happens. Row values, ordering, `USER_ENTERED`/`RAW` split, amounts, escaping, and `_sync` behaviour are unchanged.

**Step 2: Add or update validation**

- `SheetsTestSupport.HEADER_JSON` → `{"values":[["Fecha","Movimientos","Descripción","Cuenta"]]}` (all stubs pick it up).
- `GoogleSheetsClientTest.appendRowsRejectsLegacySixColumnHeader` (`:280`): change `contains("Importe")` to `contains("Movimientos")`; keep the six-column fixture at `:272` unchanged as the legacy shape. Keep the other `contains(...)` assertions.
- No new test is needed: existing tests already assert the exact header body (`:153-154`), that a matching header is accepted (`appendRowsSkipsHeaderWhenAlreadyPresent`), and that a mismatched header rejects with no append (`:285-286`).

**Step 3: Write minimal implementation**

- `GoogleSheetsClient.java:77-78`: `HEADER_VALUES = List.<Object>of("Fecha", "Movimientos", "Descripción", "Cuenta")`.
- `GoogleSheetsClient.java:39`: class Javadoc column list → `Fecha, Movimientos, Descripción, Cuenta`.
- `SheetRow.java:10`: record Javadoc column list → `Fecha, Movimientos, Descripción, Cuenta`; `:12`: replace the sentence naming `Importe` with `Movimientos` (component stays `importe`).
- `README.md:253`: in the four-column sentence, `Importe` → `Movimientos`; add one short clause that tabs written by older versions keep the old `Importe` header and are rejected by the guard (the existing reset paragraph at `:255` already tells the owner to delete/archive them). Do not rewrite the rest of the section.
- `docs/ai/architecture.md:55`: column list → `Movimientos` (keep the rest of the invariant: typed `Fecha`, legacy rows, reset-on-mismatch). `:60`: `Importe` → `Movimientos` in the `USER_ENTERED` invariant; the amount stays a JSON number under the new column name.
- `docs/ai/README.md:9`: column list in the status sentence → `Movimientos`; keep the rest of the sentence intact.
- `docs/ai/decisions/ADR-0008-...md`: append `## Amendment (2026-11-18) — visible \`Importe\` column renamed to \`Movimientos\`` after the existing amendment. State that it supersedes the column name in Decision bullet 1 (and the amount-column name in the 2026-11-18 amendment), that header matching now expects `Movimientos`, that no legacy alias exists (tabs with `Importe` are rejected and require the documented reset), that the `SheetRow` component and numeric semantics are unchanged, and link this plan and issue #20. Do not rewrite the original Decision/Consequences text.
- `AGENTS.md`: verify it contains no column-name claim (`Select-String -Pattern 'Importe' AGENTS.md` returns nothing); leave unchanged.
- `docs/ai/logs/2026-11.md`: add `## 2026-11-18 - Importe column renamed to Movimientos` with what changed, the decision (no alias; owner deletes old tabs), test result, commit hash, and the pending human check.

**Step 4: Run test to verify pass**

Run:
```powershell
.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'
.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'
.\scripts\mvn.ps1 test
Select-String -Path README.md,docs/ai/architecture.md,docs/ai/README.md -Pattern 'Importe'
```
Expected: targeted suites green; full suite green with the JaCoCo 80% line gate (`BUILD SUCCESS`); the `Select-String` check returns only any deliberate historical mention (there should be none in the live-contract sentences; ADR-0008 is not in that check) — investigate and fix any stray live occurrence.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/sheets/GoogleSheetsClient.java src/main/java/bank2sheets/sheets/SheetRow.java src/test/java/bank2sheets/sheets/SheetsTestSupport.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java README.md docs/ai/architecture.md docs/ai/README.md docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-18_210831-rename-importe-column-to-movimientos.md
git commit -m "feat: rename visible Importe column to Movimientos (issue #20)"
```

## Validation ownership

| Validation | Owner | Command / procedure | Expected |
|---|---|---|---|
| Sheets client unit suite | agent | `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest'` | Green; header body and legacy rejection assertions updated |
| CLI sync suite | agent | `.\scripts\mvn.ps1 test '-Dtest=MainCommandsTest'` | Green with shared `HEADER_JSON` |
| Full suite + coverage gate | agent | `.\scripts\mvn.ps1 test` | `BUILD SUCCESS`, JaCoCo line ratio ≥ 0.80 |
| Docs freshness | agent | `Select-String -Path README.md,docs/ai/architecture.md,docs/ai/README.md -Pattern 'Importe'` | No stale live-contract occurrence |
| Real-sheet header | human | Delete/archive the old month tabs and `_sync` (or use a fresh spreadsheet), then `.\scripts\run.ps1 sync` once and open the new tab | Header `Fecha \| Movimientos \| Descripción \| Cuenta`; a leftover old tab makes `sync` fail fast with the reset message; each sync spends one bank access, so run at most twice |

## Risks and assumptions

- **Owner will delete old tabs:** the clarification said the previously generated pages will be removed, so the minimal no-alias change is acceptable. A tab kept with `Importe` fails until reset — the error names `Movimientos` via the interpolated constant.
- **Shared header fixture:** `HEADER_JSON` drives all month-tab stubs; a partial rename would make `FakeHttp` fail loudly on the exact path+body mismatch, not silently.
- **No schema/ADR conflict beyond the name:** ADR-0008's four-column decision stands; only the column label changes, recorded as a second dated amendment. The ADR title keeps its historical `DD/MM/YYYY` wording (already noted as historical).
- **Internal component name:** `SheetRow.importe` stays to avoid a wider, no-user-value refactor; the plan does not touch serialization.
- **No new gate:** the JaCoCo 80% line gate is unchanged; the rename adds no production branch.

## Required gates

- `architect_review: true` — amends a documented sheet-data contract (ADR-0008 column list) without changing architecture; the architect agent should confirm the amendment is scoped to the label and that no other ADR/invariant depends on `Importe`.
- `memory_update: true` — `docs/ai/architecture.md`, `docs/ai/README.md`, ADR-0008, and `docs/ai/logs/2026-11.md` are updated in Task 1.
