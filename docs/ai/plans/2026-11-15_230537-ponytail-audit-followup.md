# Ponytail audit follow-up - over-engineering scan

- Date: 2026-11-15
- Scope: `src/main`, `src/test`, `scripts/`, `pom.xml`, `.github/workflows`, product docs
- Mode: whole-repository audit (read-only). No product code changed.
- Baseline: `HEAD e2963a6`; ~1,600 main LOC, ~1,800 test LOC. `.\scripts\mvn.ps1 clean test-compile` currently **fails** (see H1), so the 96-test "green" claim in `docs/ai/logs/2026-11.md` does not match HEAD.

> This is an audit report, not an approved implementation plan. Items are sized so they can
> be promoted to plans or harvested as `ponytail:` debt.

## Verdict

The codebase is still lean: one runtime dependency, no framework, no database, and the
deliberate seams (`HttpExchange`, `TransactionSource`, `Rs256Jwt`) remain justified. The
previous audit already removed the large structural debt (dead Salt Edge code, empty
exception subclasses, duplicated key fixtures, thin fakes).

Two things stand out now:

1. **A duplicated test helper breaks compilation at HEAD.** This is the first thing to fix;
   it is also a textbook rung-2 (reuse) failure introduced by the ASPSP commit.
2. The remaining over-engineering is small and mostly test- or API-surface-deep: duplicated
   account-label resolution in `Main`, a dead `EnableBankingConfig.fromEnv` path, a
   duplicated `RuntimeState` file read, redundant test fixtures, and a few members with
   wider visibility or a third fallback tier than the contract needs.

Estimated total simplification after the build fix: **~150-200 fewer lines (~10%)** and
roughly 8-12 fewer tests, with no intended behavior change.

## Findings by severity

### Critical

#### H1 - Duplicate test helper breaks the build (rung 2)

- Evidence: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java:411-413`
  and `:425-427` both declare
  `private static EnableBankingClient client(FakeHttp, AuthorizationCodeReceiver)`.
  Commit `87d6590` ("parameterize Enable Banking ASPSP lookup") added the second overload
  without noticing the existing one.
- Verified failure:

  ```text
  EnableBankingClientTest.java:[425,40] method client(FakeHttp,AuthorizationCodeReceiver)
  is already defined in class EnableBankingClientTest
  ```

- Impact: `mvn test`, `mvn package`, and `.github/workflows/sync.yml` cannot run. Delete one
  overload (keep `:425-427`, which delegates to the 3-arg helper) and rerun the full suite.
- Note: this is not just simplification; it is a release blocker caused by duplicated code.

### Medium

#### M1 - `fetch` and `sync` duplicate account-label resolution (rung 2/6)

- Evidence: `Main.java:163-171` builds `Map<String, String> accountNames` from
  `source.listAccounts()`, then looks up a name per configured account.
  `Main.java:230-237` builds the same map in `sync`, differing only in the blank-name
  fallback.
- Impact: extract one helper returning `id -> name` once per run; keep the blank/id fallback
  at the call site so `fetch` output does not change. Removes ~6-10 lines and prevents the
  two commands from drifting again.

#### M2 - `EnableBankingConfig.fromEnv` is dead in production and duplicates `AppConfig.fromEnv` (rung 1/2)

- Evidence: `EnableBankingConfig.java:52-64` defines `fromEnv()` and `fromEnv(Map)`, but
  production builds the client through `AppConfig.enableBankingConfig()`
  (`AppConfig.java:76-80`). The only callers are `EnableBankingConfigTest:52,69,83`.
- Impact: remove both methods (~13 main LOC) and the three `fromEnv` tests
  (`EnableBankingConfigTest.java:46-88`, ~43 test LOC). `AppConfigTest.readsAllEnableBankingValuesFromEnvironment`
  already covers env mapping and ASPSP normalization. One env contract instead of two.

#### M3 - `RuntimeState.read` reimplements `load` (rung 2/6)

- Evidence: `RuntimeState.java:31-55` opens the file, loads `Properties`, and reads a key;
  `RuntimeState.java:56-68` (`load`) does the same minus `getProperty`.
- Impact: `read` becomes `return load().getProperty(key(provider));` (~10 lines removed,
  identical behavior: missing file still yields an empty `Properties` and `null`).
- Related cleanup: `RuntimeState.ENABLE_BANKING_SESSION_ID_KEY` (`:18`) is `public` but only
  used inside the class (`:71`); make it private.

#### M4 - Redundant test-only production API and dead helper (rung 1 / visibility)

- `EnableBankingConfig(appId, key, redirectUrl)` (`:28-31`) is used only by tests; production
  always uses the 6-arg constructor through `AppConfig`.
- `AppConfig(..., accounts)` (`AppConfig.java:30-36`) is used only by tests; production uses
  `fromEnv`/the canonical constructor.
- `TestKeys.privateKey()` (`TestKeys.java:36-38`) has no callers.
- `GoogleSheetsClient.toValuesJson` (`:117`) is package-private but only called in the same
  class; tests assert raw request bodies instead.
- `LoopbackAuthorizationCodeReceiver.boundPort()` (`:48-50`) is a public test-only getter.
- Impact: the free removals (`privateKey()`, narrower visibility) are ~10 LOC; the
  convenience constructors are a small test-readability trade-off and may be kept. If kept,
  record why they are production-visible.

#### M5 - Redundant test fixtures and duplicated setup

- `EnableBankingConfigTest.fromEnvReadsTheThreeVariables` (`:46-58`) is a strict subset of
  `fromEnvReadsTheSixVariables` (`:60-75`) and `fromEnvDefaultsAspspSettingsWhenAbsent`
  (`:77-88`); it goes away with M2.
- `ProviderSourcesTest:22-43` repeats the blank-credential assertions already covered by
  `EnableBankingConfigTest:91-110`. Keep one factory success test and one failure test;
  remove ~25 LOC.
- `GoogleSheetsClientTest` repeats `fakeHttp() + stubToken() + new GoogleSheetsClient(...)`
  in eight tests; a `client(FakeHttp)` helper would remove the setup noise.
- `MainCommandsTest` repeats the same one-transaction JSON payload six times
  (`:115-121`, `:141-148`, `:183-198`, `:239-246`, `:311-318`, `:346-353`) and constructs
  `Main` inline in several tests. A `stubTransaction(...)` fixture and a `syncMain(...)`
  helper would remove ~40-60 lines. The earlier audit resolved this once; the multi-account
  and ASPSP tests reintroduced copies.
- Impact: ~60-90 test LOC; coverage is unchanged because the same behaviors stay asserted.

### Low

#### L1 - `GoogleSheetsClient.appendRows` has a redundant branch and index arithmetic (rung 3/6)

- Evidence: `GoogleSheetsClient.java:109-111`. `stringAt(headerRow, 5)` already returns `""`
  when the row is shorter than six cells, so `headerRow.size() < HEADER_VALUES.size()` is
  redundant. `HEADER_VALUES.get(HEADER_VALUES.size() - 1)` is `HEADER_VALUES.getLast()` on
  Java 21.
- Impact: ~3 LOC and one less condition; no behavior change.

#### L2 - `EnableBankingClient.listAccounts` has an undocumented third label tier (rung 1)

- Evidence: `EnableBankingClient.java:152-160` builds a `uid -> product` map from
  `accounts_data`, and `:165` uses it as the fallback before the empty string. The documented
  contract is display name, else account id (`docs/ai/architecture.md:52`).
- Impact: removing the product tier removes ~10 main LOC and the
  `listAccountsFallsBackToProductWhenDetailsFail` test, and makes the label chain match the
  documented contract. It does improve labels when `/details` fails, so either remove it or
  document the tier in the architecture invariant.

#### L3 - `ProviderSources.create` has a one-call private method (rung 1)

- Evidence: `ProviderSources.java:23-37`; `create` validates the provider and immediately
  delegates to `enableBanking`.
- Impact: inline `enableBanking` into `create`, ~6 LOC. The factory itself stays justified by
  ADR-0003/ADR-0005 as the Powens seam.

#### L4 - Single-provider ceremony still present after issue #7 (watch, do not churn)

- `AccountConfig.provider`, `AppConfig.provider()` inference, `ProviderSources` provider
  validation, and `RuntimeState.key(provider)` all support one provider today.
- The `provider:accountId` user-facing format and the factory are explicitly kept for a
  future Powens adapter (architecture boundary, ADR-0003). Leave them unless Powens is
  abandoned; then the config format and state key can collapse too.
- Already-deferred item: `Main` repeats
  `catch (Bank2SheetsException | IllegalStateException)` in `connect`/`fetch`/`sync`
  (`Main.java:149-152,177-180,251-254`). The previous audit skipped a `runCommand` helper as
  a readability trade-off; keep that decision.

## Ready-to-apply `ponytail:` markers

The planner cannot edit source. Apply these with the delivery agent when an item is accepted,
or let `/ponytail-debt` harvest them.

| File / line | Marker |
| --- | --- |
| `EnableBankingClientTest.java:425` | `// ponytail: duplicate 2-arg client helper; delete this overload (H1).` |
| `Main.java:163` | `// ponytail: fetch/sync duplicate the account-label map; extract accountLabels(source) (M1).` |
| `EnableBankingConfig.java:52` | `// ponytail: dead in production; AppConfig.fromEnv is the only env entry point (M2).` |
| `RuntimeState.java:31` | `// ponytail: duplicates load(); return load().getProperty(key(provider)) (M3).` |
| `TestKeys.java:36` | `// ponytail: unused helper; remove (M4).` |
| `GoogleSheetsClient.java:109` | `// ponytail: size check is redundant (stringAt handles missing cells); use getLast() (L1).` |
| `EnableBankingClient.java:152` | `// ponytail: product fallback is undocumented; contract is name -> id (L2).` |
| `ProviderSourcesTest.java:22` | `// ponytail: duplicates EnableBankingConfig blank-credential tests (M5).` |

## Justified - do not "simplify" these

Re-litigating these would violate the same reuse-first discipline:

- `HttpExchange` + `RealHttpExchange` seam (tests without an HTTP-stubbing framework).
- `TransactionSource` seam and `Rs256Jwt` (no bank/Google SDK, no JWT dependency).
- Gson as the only runtime dependency; no Google client library.
- `EnableBankingConfig` redirect validation and `LoopbackAuthorizationCodeReceiver`
  bind-before-print, timeout, and idempotent `start()`.
- `EnableBankingClient` canonical-fields hash suffix for dedupe ids; the drift tests are
  intentionally exact because the id is a stored dedupe contract.
- Google Sheets header backfill for the real legacy five-column sheet.
- JaCoCo 80% gate, shade fat JAR, and the monthly scheduled workflow running `package`.
- `docs/callback.html` existing at all (HTTPS production redirect requires it); its copy
  button is a mobile convenience, not code duplication.

## Suggested sequence

1. **H1** - delete the duplicate overload, run `.\scripts\mvn.ps1 test`, and correct the stale
   "96 tests green" log entry.
2. **M1 + M3** - behavior-neutral production cleanups; smallest risk.
3. **M2 + M5** - remove the dead env path and its redundant tests, then trim the other
   duplicate fixtures.
4. **L1, L2, M4** - opportunistic; take them when touching the file.
5. **L4** - leave the provider seam; revisit only if Powens is abandoned.

## Validation notes

- `.\scripts\mvn.ps1 clean test-compile` was run for this audit and fails at
  `EnableBankingClientTest.java:425` with "method client(...) is already defined".
- After H1, the expected next command is `.\scripts\mvn.ps1 test` (JUnit suite + JaCoCo 80%
  line gate). Removing redundant tests must keep the gate green.
- No secrets, credentials, state files, or product code were changed by this audit; nothing
  was committed.

## Implementation status (2026-11-15)

- Applied: H1, M1, M2, M3, M4 (free removals only), M5, L1, L2, L3.
- Retained intentionally: the 3-arg `EnableBankingConfig` and 5-arg `AppConfig` convenience
  constructors (test-readability trade-off, as noted in M4).
- Intentional behavior change in L2: when `/accounts/{id}/details` fails, the account label
  is now the account id instead of the `accounts_data` product string. This matches the
  documented "display name, else account id" contract.
- Not applied: L4 (single-provider ceremony; watch item, kept for the Powens seam) and all
  items in "Justified - do not simplify".
- Validation: `.\scripts\mvn.ps1 test` -> 89 tests, 0 failures/errors, JaCoCo 80% line gate
  met, `BUILD SUCCESS`. Test count dropped from 96 because seven redundant tests were
  removed (M2/M5/L2).
- Files touched: `Main`, `RuntimeState`, `EnableBankingConfig`, `GoogleSheetsClient`,
  `EnableBankingClient`, `ProviderSources`, `LoopbackAuthorizationCodeReceiver`, and the
  corresponding tests. Nothing was committed.
