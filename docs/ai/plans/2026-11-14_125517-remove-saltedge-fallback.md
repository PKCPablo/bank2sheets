---
status: completed
created: 2026-11-14
task: 3/3
required_gates:
  architect_review: false
  memory_update: true
---

# Remove Salt Edge fallback implementation plan

**Goal:** Delete the Salt Edge provider and every runtime/config/state path that can select it, leaving Enable Banking as the only supported provider with no behaviour change to the Enable Banking flow.

**Architecture:** The `TransactionSource` seam and `ProviderSources` composition-root factory stay. `AppConfig` loses its four Salt Edge fields and becomes Enable-Banking-only; `RuntimeState` keeps only `enablebanking.session_id`; `Main` keeps the existing connect/fetch/sync flow with EB-only messages. The shared `FakeHttp` test fixture moves out of the Salt Edge package to `bank2sheets.http`.

**Tech Stack:** Java 21, Maven, JUnit 5, Gson (no dependency changes), JaCoCo 80% line gate.

**Scope classification:** `multi-task` (3 reviewable deliverables: fixture move, provider removal, docs/memory closure).

## Grounded baseline (verified 2026-11-14)

- Working tree clean on `master`; issue #7 claimed (`status:doing`).
- Salt Edge surface: 3 files under `src/main/java/bank2sheets/openbanking/saltedge/` (`SaltEdgeClient`, `SaltEdgeException`, plus its package), 1 test file `SaltEdgeClientTest` (9 tests), and 1 shared fixture `FakeHttp` used by `SheetsTestSupport`, `GoogleSheetsClientTest`, `EnableBankingClientTest`, `MainCommandsTest`, `SaltEdgeClientTest`.
- `AppConfig` is a 9-component record (4 Salt Edge fields) with a 5-arg convenience constructor, Salt Edge env reads in `fromEnv`, a Salt Edge branch in `withProviderState`/`provider`/`providerId`, and `saltedge` accepted by `parseAccounts`.
- `RuntimeState` exposes `SALTEDGE_CONNECTION_ID_KEY` and a provider switch that maps `saltedge`.
- `Main` documents Salt Edge in `USAGE`, in the accounts error, and in the missing-connection hint (`SALTEDGE_CONNECTION_ID`).
- `MainCommandsTest` has 7 Salt Edge-specific tests and 5 provider-neutral tests currently wired through Salt Edge; 7 Enable Banking command tests already exist. `sync.yml` already contains no Salt Edge references.
- Other `src/main` mentions that acceptance requires removing: `model/Account.java` Javadoc, `config/AccountConfig.java` Javadoc, `pom.xml` Gson comment.
- Baseline suite: 104 tests, JaCoCo 80% gate green (per `AGENTS.md` and `docs/ai/logs/2026-11.md`).
- The Salt Edge V6 migration plan is already marked `superseded`; historical ADR/log evidence stays unchanged.

## Decisions (recorded, not blocking)

1. **Keep the provider seam, collapse its values.** `ProviderSources` keeps a factory and `AppConfig.provider()` keeps returning `"enablebanking"` (or throwing), so a future provider still has a home. Remove only the Salt Edge branch and the now-unreachable mixed-provider branch in `parseAccounts` (with one supported provider, `"enablebanking:a, enablebanking:b"` is the only valid list; `saltedge:`/`powens:` fail as unsupported).
2. **Collapse `withProviderState` to the session id.** New signature `withProviderState(String sessionId)`; `providerId()` returns `enableBankingSessionId`. `Main` keeps the provider string only for `RuntimeState.save(provider, id)` and the account hint.
3. **Legacy state stays untouched.** `RuntimeState.save` loads and re-stores all existing properties, so a local `saltedge.connection_id` survives unchanged and is never read (provider never resolves to `saltedge`). No migration, no file rewrite, no crash — covered by regression tests.
4. **Fixture location:** `src/test/java/bank2sheets/http/FakeHttp.java`. After the Salt Edge tests are deleted, remove the Salt-Edge-rooted `BASE` constant and no-arg constructor so callers must pass their API base explicitly.
5. **No architect gate.** This is a scope reduction explicitly mandated by ADR-0005; no new boundary or dependency is introduced.

## Tasks

### Task 1: Move the shared HTTP fake to a provider-neutral package

**Objective:** Relocate `FakeHttp` to `bank2sheets.http` without behavior change so the Salt Edge test package can be deleted in Task 2.

**Files:**
- Move: `src/test/java/bank2sheets/openbanking/saltedge/FakeHttp.java` → `src/test/java/bank2sheets/http/FakeHttp.java`
- Modify: `src/test/java/bank2sheets/sheets/SheetsTestSupport.java`
- Modify: `src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java`
- Modify: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Modify: `src/test/java/bank2sheets/openbanking/saltedge/SaltEdgeClientTest.java`
- Validate: the four test classes above

**Step 1: Establish expected behaviour**

`FakeHttp` is an in-memory `HttpExchange` shared by the Sheets, Enable Banking, Main command, and (until deletion) Salt Edge tests. Moving its package must not change stubbing, request recording, or the `(String base)` override behavior.

**Step 2: Move and neutralize the fixture**

`git mv` the file to `src/test/java/bank2sheets/http/FakeHttp.java`, change its package declaration to `bank2sheets.http`, and rewrite the class Javadoc to describe a shared HTTP stub. Keep `BASE` and the no-arg constructor for now (Salt Edge tests still use them; Task 2 removes them). Drop the `ponytail:` rationale comment about the historical package.

**Step 3: Update imports**

Replace `import bank2sheets.openbanking.saltedge.FakeHttp;` with `import bank2sheets.http.FakeHttp;` in the five listed test files. Keep `bank2sheets.http.HttpExchange` imports valid (FakeHttp is now in the same package and still imports `HttpExchange.Response`).

**Step 4: Run repeated validation (owner: agent)**

Run: `.\scripts\mvn.ps1 test '-Dtest=GoogleSheetsClientTest,EnableBankingClientTest,SaltEdgeClientTest,MainCommandsTest'`
Expected: all selected tests pass, no compilation errors.

**Step 5: Commit**

`git add src/test/java/bank2sheets/http/FakeHttp.java src/test/java/bank2sheets/openbanking/saltedge/FakeHttp.java src/test/java/bank2sheets/sheets/SheetsTestSupport.java src/test/java/bank2sheets/sheets/GoogleSheetsClientTest.java src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java src/test/java/bank2sheets/MainCommandsTest.java src/test/java/bank2sheets/openbanking/saltedge/SaltEdgeClientTest.java`
`git commit -m "test: move FakeHttp to provider-neutral http package"`

### Task 2: Delete the Salt Edge provider and collapse config, state, and CLI to Enable Banking

**Objective:** Remove all Salt Edge production/test code and make `enablebanking` the only accepted provider, with regressions for `saltedge:` rejection and legacy state tolerance.

**Files:**
- Delete: `src/main/java/bank2sheets/openbanking/saltedge/SaltEdgeClient.java`
- Delete: `src/main/java/bank2sheets/openbanking/saltedge/SaltEdgeException.java`
- Delete: `src/test/java/bank2sheets/openbanking/saltedge/SaltEdgeClientTest.java`
- Modify: `src/main/java/bank2sheets/config/AppConfig.java`
- Modify: `src/main/java/bank2sheets/config/AccountConfig.java`
- Modify: `src/main/java/bank2sheets/config/RuntimeState.java`
- Modify: `src/main/java/bank2sheets/model/Account.java`
- Modify: `src/main/java/bank2sheets/openbanking/ProviderSources.java`
- Modify: `src/main/java/bank2sheets/Main.java`
- Modify: `src/test/java/bank2sheets/http/FakeHttp.java`
- Modify: `src/test/java/bank2sheets/config/AppConfigTest.java`
- Modify: `src/test/java/bank2sheets/config/RuntimeStateTest.java`
- Modify: `src/test/java/bank2sheets/openbanking/ProviderSourcesTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Validate: full suite + JaCoCo gate

**Step 1: Establish expected behaviour**

After this task: `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>` works exactly as today; `BANK2SHEETS_ACCOUNTS=saltedge:<id>` fails fast with `Unsupported provider: saltedge` (via `parseAccounts`, i.e. an `IllegalArgumentException` surfaced by `Main` as a command error); legacy `SALTEDGE_*` env vars are ignored; a state file containing `saltedge.connection_id` neither crashes nor is rewritten. `fetch`/`sync` without a session still fail with the Enable Banking hint.

**Step 2: Delete the Salt Edge classes and tests**

Delete `SaltEdgeClient.java`, `SaltEdgeException.java`, and `SaltEdgeClientTest.java` (removes 9 tests). Leave no empty `saltedge` directory.

**Step 3: Collapse `AppConfig`**

Rewrite the record to `AppConfig(String enableBankingAppId, String enableBankingPrivateKeyB64, String enableBankingRedirectUrl, String enableBankingSessionId, List<AccountConfig> accounts)`.
- Delete the 5-arg Salt Edge convenience constructor and the four Salt Edge components.
- `fromEnv`: keep only the four `ENABLE_BANKING_*` reads plus `BANK2SHEETS_ACCOUNTS`.
- `withProviderState(String sessionId)`: return a copy with `enableBankingSessionId` replaced.
- `provider()`: accounts first, then non-blank `enableBankingAppId` → `"enablebanking"`, else throw a message containing `BANK2SHEETS_ACCOUNTS` and `ENABLE_BANKING_APP_ID` with the example `enablebanking:<uuid>`.
- `providerId()`: return `enableBankingSessionId`.
- `parseAccounts`: accept only `enablebanking`; reject every other provider with `Unsupported provider: <provider>`; delete the mixed-provider branch and its Javadoc clause.

**Step 4: Collapse state and provider selection**

`RuntimeState`: delete `SALTEDGE_CONNECTION_ID_KEY`; `key(String provider)` maps only `"enablebanking"` to `ENABLE_BANKING_SESSION_ID_KEY` and otherwise throws `IllegalArgumentException("Unknown provider: ...")`; update the class/method Javadoc to say Enable Banking only.

`ProviderSources`: remove the Salt Edge import/branch and the `saltEdge` guard method; `create(AppConfig)` throws `Bank2SheetsException("Unsupported provider: " + cfg.provider())` unless it is `"enablebanking"`, then returns the existing `enableBanking(cfg)` client.

`AccountConfig` and `Account` Javadoc: describe only Enable Banking / provider-neutral wording; no Salt Edge mention.

**Step 5: Collapse `Main`**

- Class Javadoc: persisted id is the Enable Banking session id.
- `USAGE`: one provider block (`ENABLE_BANKING_*`), accounts example `enablebanking:<uuid>`; delete the Salt Edge block.
- `connect`: call `cfg.withProviderState(connectionId)`; keep `state.save(provider, connectionId)` and the `BANK2SHEETS_ACCOUNTS=enablebanking:<accountId>` hint.
- `requireFetchConfig`: accounts error example is `enablebanking:<uuid>`; the missing-connection branch always hints `ENABLE_BANKING_SESSION_ID` (delete the ternary).

**Step 6: Finish `FakeHttp` cleanup**

Remove the Salt-Edge `BASE` constant and the no-arg constructor; every caller must pass a base (`SheetsTestSupport` passes `GOOGLE_BASE`, Enable Banking/Main tests pass `EB_BASE`). Update the Javadoc accordingly.

**Step 7: Rewrite the config/state/factory tests**

`AppConfigTest`: update `emptyEnvironmentYieldsEmptyConfig` to the four EB fields; convert account-parsing tests to `enablebanking`; keep malformed-entry tests; delete Salt Edge inference/read tests; add regressions:
- `parseAccounts("saltedge:acct-1")` throws `Unsupported provider: saltedge`.
- `AppConfig.fromEnv(Map.of("SALTEDGE_APP_ID", "app-1")).provider()` throws instead of inferring Salt Edge.
- The no-config message mentions `ENABLE_BANKING_APP_ID` and not `SALTEDGE_APP_ID`.
- Keep the EB env-read and provider-inference tests.
Delete the unreachable mixed-provider test.

`RuntimeStateTest`: use `enablebanking` for read/save; add `legacySaltEdgeKeyIsPreservedAndIgnored` — write `saltedge.connection_id=conn-1` plus `enablebanking.session_id=sess-1`, assert `read("enablebanking") == "sess-1"`, call `save("enablebanking", "sess-2")`, assert the new value and that the file still contains `saltedge.connection_id=conn-1`.

`ProviderSourcesTest`: update constructors to the 5-arg record; delete the two Salt Edge tests; keep the credential-guard, https-redirect, and unknown-provider tests.

**Step 8: Retarget `MainCommandsTest`**

Delete the 7 Salt Edge tests (`connectEndToEnd...`, `connectWithPending...`, `fetchEndToEnd...`, `connectWithoutCredentials...`, `syncEndToEnd...`, `syncIsIdempotent...`, `syncWithSaltEdgeError...`) and the `TWO_TRANSACTIONS_JSON` fixture. Retarget the 5 provider-neutral tests through `enableBankingConfig`/`runEnableBanking`:
- `fetchWithoutConnection`: assert stderr contains `ENABLE_BANKING_SESSION_ID` (and/or ``run `connect` once``).
- `fetchWithoutAccounts`: 5-arg EB config with empty accounts.
- `syncWithoutSheetsConfig`: EB client + empty Sheets config.
- `syncWithSheetsError`: EB transaction stub, Sheets read 403.
- `syncWithoutConnection`: EB config, missing state file.
Add `fetchIgnoresLegacySaltEdgeStateKey`: state file with `saltedge.connection_id=conn-1` and `enablebanking.session_id=sess-eb-1`; EB fetch succeeds and prints the transaction count. Collapse the duplicate config helper into `enableBankingConfig`.

**Step 9: Run full validation (owner: agent)**

Run: `.\scripts\mvn.ps1 test`
Expected: `BUILD SUCCESS`, zero failures/errors, JaCoCo `LINE/COVEREDRATIO >= 0.80` met. Record the new test count for Task 3.

**Step 10: Commit**

`git add -A src/main/java/bank2sheets src/test/java/bank2sheets`
`git commit -m "refactor: remove Salt Edge provider and collapse to Enable Banking (issue #7)"`

### Task 3: Close documentation and memory for the removal

**Objective:** Remove Salt Edge from the non-historical docs/config comments, record the closure, and verify the acceptance criteria.

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `pom.xml` (Gson comment only)
- Modify: `docs/ai/README.md`
- Modify: `docs/ai/architecture.md`
- Modify: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Modify: `docs/ai/backlog/README.md`
- Modify: `docs/ai/logs/2026-11.md`
- Modify: this plan (frontmatter status/task at handoff)
- Validate: scoped-reference search + full suite

**Step 1: Establish expected behaviour**

Non-historical docs describe Enable Banking as the only provider; historical plans/logs and ADR evidence keep their Salt Edge references but point to the closure.

**Step 2: Update product/config docs**

`README.md`: intro parenthetical; `connect` bullet (remove “Enable Banking or Salt Edge” and “Enable Banking first”); delete the four `SALTEDGE_*` rows; `BANK2SHEETS_ACCOUNTS` row only `enablebanking:<uuid>`; delete the Salt Edge sentences at the end of the Enable Banking paragraph and the “For the Salt Edge fallback…” paragraph; state-file line mentions only `enablebanking.session_id`.

`AGENTS.md`: overview parenthetical; remove `saltedge/SaltEdgeClient` from the structure list; change the single-test example to `'-Dtest=EnableBankingClientTest'`; state-file boundary mentions only `enablebanking.session_id`.

`pom.xml`: change “used only by SaltEdgeClient” to “used by the Enable Banking and Google Sheets clients” (keep the ponytail rationale).

**Step 3: Update AI memory**

`docs/ai/README.md`: replace the “fallback code remains … until issue #7” wording with “Salt Edge fallback code removed (issue #7 closed); Enable Banking is the only provider.” Keep the discard rationale.

`docs/ai/architecture.md`: provider bullet (remove “remains until #7”); delete the four `SALTEDGE_*` env entries; delete the Salt Edge contract paragraph; reword the `eb:` prefix note so it no longer references Salt Edge ids; boundaries and secrets lines lose Salt Edge; state invariant mentions only `enablebanking.session_id`; mixed-provider wording becomes “unsupported providers fail fast”.

`docs/ai/backlog/README.md`: remove the resolved Salt Edge item; delete the now-empty `Deferred / Pending` section.

`docs/ai/decisions/ADR-0005-bbva-ais-provider.md`: append an amendment/closure note (2026-11-14) that issue #7 removed the fallback code; keep the historical evidence, matrix, and risks unchanged.

`docs/ai/logs/2026-11.md`: append a dated entry for the removal with the exact test count from Task 2, `BUILD SUCCESS`, JaCoCo gate, and “no secrets/state files committed”. Leave prior log entries untouched.

**Step 4: Verify scoped references (owner: agent)**

Run:
```powershell
Get-ChildItem -Recurse -File src,README.md,AGENTS.md,docs\ai\architecture.md,.github\workflows |
  Select-String -Pattern 'SaltEdge|saltedge|SALTEDGE'
```
Expected: no matches (historical `docs/ai/plans`, `docs/ai/logs`, and ADR evidence are intentionally outside this list and keep their history).

**Step 5: Run final validation (owner: agent)**

Run: `.\scripts\mvn.ps1 test`
Expected: `BUILD SUCCESS`, all tests pass, JaCoCo gate met, test count matches the number recorded in `AGENTS.md` and the new log entry. Then run `git status --short` and confirm only the intended docs/plan files are present (no `.env`, `bank2sheets-state.properties`, or credentials).

**Step 6: Commit**

`git add README.md AGENTS.md pom.xml docs/ai/README.md docs/ai/architecture.md docs/ai/decisions/ADR-0005-bbva-ais-provider.md docs/ai/backlog/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-14_125517-remove-saltedge-fallback.md`
`git commit -m "docs(ai): record Salt Edge removal and close issue #7"`

## Acceptance mapping

| Acceptance criterion (issue #7) | Covered by |
|---|---|
| No `SaltEdge`/`saltedge`/`SALTEDGE` in `src/**`, `README.md`, `AGENTS.md`, `docs/ai/architecture.md`, `sync.yml` | Task 2 (code/tests), Task 3 Step 4 search (`sync.yml` already clean) |
| `saltedge:<id>` fails fast; `enablebanking:<uuid>` unchanged | Task 2 Steps 3, 7, 8 |
| Legacy `saltedge.connection_id` does not break EB connect/fetch/sync | Task 2 Steps 4, 7 (`RuntimeStateTest`), 8 (`fetchIgnoresLegacySaltEdgeStateKey`) |
| Full suite green with JaCoCo 80% gate and updated count | Task 2 Step 9, Task 3 Step 5 |
| Docs/memory record discard; V6 plan superseded | Task 3 Step 3 (V6 plan already superseded, untouched) |
| No secrets/state/credentials committed | Task 3 Step 5 `git status` check |

## Blocking decisions

None. The only user-facing gate is confirming that the 3-task split is acceptable; all implementation choices above are grounded in issue #7 and ADR-0005.

## Handoff notes

- Do not delete or migrate the owner's local `bank2sheets-state.properties`; the legacy key must survive untouched.
- Do not call real providers or need credentials; all validation is fake-HTTP + local unit/CLI tests (`agent`-owned).
- After the final commit, close issue #7 with a completion summary referencing the three commits and the recorded test count (user/`/issue close` step, not this plan).
