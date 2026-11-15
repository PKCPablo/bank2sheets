---
status: completed
created: 2026-11-15
task: 3/3
required_gates:
  architect_review: false
  memory_update: true
---

# Enable Banking ASPSP parameterization implementation plan

**Goal:** Make the Enable Banking ASPSP (bank name, country, PSU type) configurable from the environment while keeping BBVA Spain / personal as the default validated behavior.

**Architecture:** Extend the existing environment contract (`AppConfig` -> `EnableBankingConfig` -> `EnableBankingClient`) with three optional values; the client builds the `/aspsps` query and `/auth` body from them instead of compile-time constants. No new seam, dependency, or provider is introduced.

**Tech Stack:** Java 21, Maven (JUnit 5, Gson, JaCoCo 80% line gate), PowerShell wrapper `.\scripts\mvn.ps1`.

**Scope classification:** `multi-task`

## Discovery (verified in the repository, 2026-11-15)

- `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:47-49` hardcodes `ASPSP_NAME="BBVA"`, `ASPSP_COUNTRY="ES"`, `PSU_TYPE="personal"`; `:83-99` uses them in the `/aspsp` body and `psu_type`; `:312-324` `findBbva()` builds `GET /aspsps?country=ES&psu_type=personal&service=AIS` and throws a message naming the values.
- `src/main/java/bank2sheets/config/EnableBankingConfig.java:17` is a 3-component record (`appId`, `privateKeyB64`, `redirectUrl`) with credential/redirect validation; `:37-42` reads only those three env vars.
- `src/main/java/bank2sheets/config/AppConfig.java:16-21` is a 5-component record; `:27-35` `fromEnv`/`withProviderState`; `:59-62` `enableBankingConfig()` builds the client config from app fields. The `AppConfig` canonical constructor is called at 12 sites (4 in `MainCommandsTest`, 6 in `ProviderSourcesTest`, 2 in `AppConfig`).
- `src/main/java/bank2sheets/Main.java:123` prints "authorize BBVA"; `:46-48` usage text lists only the three credential env vars.
- Tests that pin the current defaults: `EnableBankingClientTest.java:43,60,88-91` (BBVA/ES/personal URL and `/auth` body), `MainCommandsTest.java:48-49,66-67` (CLI `/aspsps` stubs), `EnableBankingConfigTest.java`, `AppConfigTest.java`.
- Docs to update: `README.md` (intro line 3, requirements line 9, walkthrough steps 2/6 around lines 44 and 90, run section line 186, Enable Banking paragraph line 206, Configuration table), `.env.example`, `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`, `docs/ai/architecture.md:22-30`, `docs/ai/README.md:9`, `docs/ai/logs/2026-11.md`.
- Validation: full suite `.\scripts\mvn.ps1 test` (83 tests + JaCoCo 80% line gate). `AGENTS.md` is the source for commands; JaCoCo `check` is bound to the `test` phase (`pom.xml:86-103`), so targeted runs during development should pass `-Djacoco.skip=true` and each task ends with a full run.

## Decisions

1. **Defaults live in `EnableBankingConfig`.** The compact constructor normalizes blank/unset ASPSP values to `BBVA` / `ES` / `personal`, so every construction path (env, `AppConfig`, tests) gets the same behavior.
2. **Backward-compatible constructors.** Keep a 3-arg `EnableBankingConfig` constructor and add a 5-arg `AppConfig` convenience constructor, both delegating with blank ASPSP values. This preserves current behavior and avoids touching all 12 `AppConfig` call sites.
3. **Normalization:** trim the ASPSP name; trim + uppercase the country (ISO 3166 alpha-2); trim + lowercase the PSU type. No whitelist on PSU type (the API may add values; a wrong value still fails fast at the API and in the no-match error).
4. **Query safety:** URL-encode the name/country/PSU values with `URLEncoder` (UTF-8). Existing BBVA/ES/personal URLs are unchanged; names with spaces (e.g. `Banco Santander`) work.
5. **Generic connect prompt:** change `Main`'s "authorize BBVA" to "authorize your bank" instead of coupling `Main` to Enable Banking config access. The configured ASPSP is documented and named in the no-match error.
6. **`.env.example` is included** because it is the tracked template for the configuration table; `AGENTS.md`/`pom.xml` BBVA wording stays as-is (BBVA remains the default/validated target) to keep scope minimal.
7. **No human validation gate.** Real non-BBVA validation needs owner credentials/SCA and is explicitly out of scope for issue #14 (follow-up).

## Tasks

### Task 1: Extend the configuration contract with ASPSP settings

**Objective:** `ENABLE_BANKING_ASPSP_NAME`, `ENABLE_BANKING_ASPSP_COUNTRY`, and `ENABLE_BANKING_PSU_TYPE` are read from the environment, validated/normalized, default to BBVA/ES/personal, and flow through `AppConfig` without breaking existing constructors.

**Files:**
- Modify: `src/main/java/bank2sheets/config/EnableBankingConfig.java`
- Modify: `src/main/java/bank2sheets/config/AppConfig.java:16-21,27-35,59-62,64-71`
- Validate: `src/test/java/bank2sheets/config/EnableBankingConfigTest.java`, `src/test/java/bank2sheets/config/AppConfigTest.java`, `src/test/java/bank2sheets/openbanking/ProviderSourcesTest.java`

**Step 1: Establish expected behaviour** (owner: agent)

Acceptance conditions to encode in tests:

- Unset/blank `ENABLE_BANKING_ASPSP_NAME`/`ENABLE_BANKING_ASPSP_COUNTRY`/`ENABLE_BANKING_PSU_TYPE` yield `BBVA`/`ES`/`personal` (current behavior preserved).
- Explicit values are read and normalized: `"  Santander  "` -> `Santander`, `"es"` -> `ES`, `"Personal"` -> `personal`.
- The 3-arg `EnableBankingConfig` constructor and the existing 5-arg `AppConfig` constructor still compile and default the ASPSP values.
- `AppConfig.withProviderState(sessionId)` preserves the configured ASPSP values.

**Step 2: Add or update validation** (owner: agent)

- `EnableBankingConfigTest`: add
  - `defaultsAspspSettingsWhenUnset` (3-arg constructor -> BBVA/ES/personal),
  - `normalizesAspspSettings` (trims/uppercases/lowercases explicit values),
  - `fromEnvReadsTheSixVariables` (extend the existing env test with the three new keys),
  - `fromEnvDefaultsAspspSettingsWhenAbsent`.
- `AppConfigTest`: extend `emptyEnvironmentYieldsEmptyConfig` to assert the three new fields are blank, and `readsAllEnableBankingValuesFromEnvironment` to feed/assert the three values; add `withProviderStatePreservesAspspSettings`.
- `ProviderSourcesTest` needs no new expectations; it must keep compiling/passing through the 5-arg constructor.

**Step 3: Write minimal implementation** (owner: agent)

- `EnableBankingConfig` (record):
  - Add components `aspspName`, `aspspCountry`, `psuType` after `redirectUrl`.
  - In the compact constructor, keep the existing validations, then default/normalize: `aspspName = blank ? "BBVA" : trim()`, `aspspCountry = blank ? "ES" : trim().toUpperCase(Locale.ROOT)`, `psuType = blank ? "personal" : trim().toLowerCase(Locale.ROOT)`.
  - Add a 3-arg constructor delegating with `"", "", ""`.
  - Extend `fromEnv(Map)` with `env.getOrDefault("ENABLE_BANKING_ASPSP_NAME", "")`, `..._COUNTRY`, `..._PSU_TYPE`.
  - Update the class Javadoc to document the three optional variables and their defaults.
- `AppConfig` (record):
  - Add components `enableBankingAspspName`, `enableBankingAspspCountry`, `enableBankingPsuType` before `accounts`.
  - Add a 5-arg constructor `(enableBankingAppId, enableBankingPrivateKeyB64, enableBankingRedirectUrl, enableBankingSessionId, accounts)` delegating with `"","",""` for the ASPSP values.
  - `fromEnv(Map)` reads the three new vars (default `""`).
  - `withProviderState` passes all eight components through, preserving ASPSP values.
  - `enableBankingConfig()` passes the six values to `EnableBankingConfig`.
  - Extend the record Javadoc.

**Step 4: Run test to verify pass** (owner: agent)

Run:

```powershell
.\scripts\mvn.ps1 test '-Dtest=EnableBankingConfigTest,AppConfigTest,ProviderSourcesTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: targeted tests pass; the full suite (83 existing + new tests) passes and the JaCoCo 80% line gate is satisfied.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/config/EnableBankingConfig.java src/main/java/bank2sheets/config/AppConfig.java src/test/java/bank2sheets/config/EnableBankingConfigTest.java src/test/java/bank2sheets/config/AppConfigTest.java
git commit -m "feat: add configurable Enable Banking ASPSP settings (issue #14)"
```

### Task 2: Parameterize the ASPSP lookup and connect flow

**Objective:** `connect` queries `/aspsps` with the configured name/country/PSU type, selects that ASPSP for `/auth`, and fails fast with a message naming the requested values when no match exists; the CLI proves the wiring end to end with fake HTTP.

**Files:**
- Modify: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:46-49,81-107,312-324`
- Modify: `src/main/java/bank2sheets/Main.java:46-48,123`
- Validate: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java`, `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour** (owner: agent)

- With defaults (existing `config()` helper), the `/aspsps` URL and `/auth` body remain exactly `BBVA`/`ES`/`personal`; all existing tests pass unchanged.
- A configured ASPSP (e.g. `Santander`/`ES`/`personal`) is the only one selected even when BBVA is also listed, and its `maximum_consent_validity` drives `access.valid_until` (90-day fallback still applies when absent).
- Country and PSU type are sent in both the query and the `/auth` body (e.g. `DE`/`business` -> `/aspsps?country=DE&psu_type=business&service=AIS`, `psu_type: "business"`).
- A name with a space is URL-encoded in the query (`Banco Santander` -> `Banco+Santander`).
- No match throws `Bank2SheetsException` naming the requested name, country, and PSU type, and no `/auth` request is sent.
- `AppConfig` ASPSP values reach the client through `Main` (CLI-level test).

**Step 2: Add or update validation** (owner: agent)

- `EnableBankingClientTest`:
  - Add a `config(name, country, psuType)` overload next to the existing `config()` helper (keep `config()` delegating with defaults).
  - `connectUsesConfiguredAspsp`: stub `/aspsps?country=ES&psu_type=personal&service=AIS` with Santander (validity 2592000) and BBVA (15552000); assert the request URL, `aspsp.name= Santander`, `aspsp.country=ES`, `psu_type=personal`, and `valid_until = NOW + 2592000`.
  - `connectUsesConfiguredCountryAndPsuType`: config `Deutsche Bank`/`de`/`Business`; stub `/aspsps?country=DE&psu_type=business&service=AIS`; assert the encoded URL (`Deutsche+Bank`) and `/auth` body values.
  - `connectFailsWhenAspspNotListed`: stub only BBVA; assert the exception message contains the configured name, country, and PSU type, and `http.requests.size() == 1` (no `/auth`).
- `MainCommandsTest`:
  - Add `enableBankingConnectUsesConfiguredAspsp`: build an 8-arg `AppConfig` with Santander/ES/personal, stub the corresponding `/aspsps` + `/auth`/`/sessions` responses, run `connect` through `Main`, and assert exit 0, the `/aspsps` URL, and the stored session id. Existing tests keep using the 5-arg constructor with defaults.

**Step 3: Write minimal implementation** (owner: agent)

- `EnableBankingClient`:
  - Delete the `ASPSP_NAME`/`ASPSP_COUNTRY`/`PSU_TYPE` constants; keep `DEFAULT_CONSENT_VALIDITY_SECONDS`.
  - Rename `findBbva()` to `findAspsp()`; build the path as
    `"/aspsps?country=" + encode(config.aspspCountry()) + "&psu_type=" + encode(config.psuType()) + "&service=AIS"`
    using `URLEncoder.encode(value, StandardCharsets.UTF_8)`; match `config.aspspName().equals(...)` and `config.aspspCountry().equals(...)`.
  - `connect()`: read the matched object from `findAspsp()`, keep the `maximum_consent_validity` fallback, use `config.aspspName()`/`config.aspspCountry()` in the `aspsp` object and `config.psuType()` for `psu_type`.
  - No-match message: `"Enable Banking lists no " + config.aspspName() + " ASPSP in " + config.aspspCountry() + " for " + config.psuType() + " AIS"`.
  - Update the class Javadoc only if it claims BBVA-specific behavior.
- `Main`:
  - Change the connect prompt to "Open this URL in your browser and authorize your bank (read-only consent):".
  - Add the three optional variables to the `USAGE` Enable Banking block with `(default BBVA/ES/personal)` in the description text.

**Step 4: Run test to verify pass** (owner: agent)

Run:

```powershell
.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest,MainCommandsTest' '-Djacoco.skip=true'
.\scripts\mvn.ps1 test
```

Expected: targeted tests pass, including the unchanged default-BBVA assertions; full suite green with the 80% line gate.

**Step 5: Commit**

```powershell
git add src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java src/test/java/bank2sheets/MainCommandsTest.java
git commit -m "feat: parameterize Enable Banking ASPSP lookup (issue #14)"
```

### Task 3: Document the configurable ASPSP and update durable memory

**Objective:** README, `.env.example`, ADR-0005, and `docs/ai` memory describe the three optional variables, keep BBVA as the validated/default target, and preserve the restricted-production constraints.

**Files:**
- Modify: `README.md` (intro, requirements, walkthrough steps 2 and 6, run/connect text, Configuration table + Enable Banking paragraph)
- Modify: `.env.example`
- Modify: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md` (new amendment section)
- Modify: `docs/ai/architecture.md:22-30`
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/logs/2026-11.md`
- Validate: `README.md`, `.env.example`, `docs/ai/**` via grep + full test suite

**Step 1: Establish expected behaviour** (owner: agent)

Docs must state, in one consistent wording:

- `ENABLE_BANKING_ASPSP_NAME` (default `BBVA`), `ENABLE_BANKING_ASPSP_COUNTRY` (default `ES`), `ENABLE_BANKING_PSU_TYPE` (default `personal`).
- The name must match the ASPSP name shown in Enable Banking; the account must still be linked in the Enable Banking control panel.
- BBVA Spain remains the validated target; other ASPSPs are configurable but not owner-validated.
- Restricted-production constraints (personal, non-commercial, linked accounts only, rate limits, session renewal) are unchanged.
- No secret values or personal ids are added.

**Step 2: Add or update validation** (owner: agent)

Run after editing:

```powershell
Select-String -Path README.md,.env.example -Pattern 'authorize BBVA|BBVA SCA|ENABLE_BANKING_ASPSP'
Get-ChildItem docs/ai -Recurse -Include *.md | Select-String -Pattern 'hardcodes BBVA|BBVA-only'
.\scripts\mvn.ps1 test
```

Expected: `README.md`/`.env.example` show the three new variables and no stale "authorize BBVA"/"BBVA SCA" walkthrough wording; the `docs/ai` grep finds no live "hardcodes BBVA"/"BBVA-only" claims (historical ADR/log entries such as ADR-0005's 2026-11-13 evidence are exempt); full suite green with the 80% gate.

**Step 3: Write minimal implementation** (owner: agent)

- `README.md`:
  - Intro: "fetch transactions from a bank account (BBVA Spain by default, via Enable Banking)".
  - Requirements: replace the BBVA-only requirement with "Online banking access for the ASPSP you link (BBVA Spain by default)".
  - Walkthrough step 2: add a short note that the guide uses BBVA Spain (the default) and that another ASPSP must be linked in the Enable Banking control panel and named via the three variables.
  - Walkthrough step 6 and the `connect` subcommand text: "complete your bank's SCA" / "the configured bank" instead of BBVA-only wording.
  - Configuration table: add the three variables with defaults and the "exact name from Enable Banking" note; extend the Enable Banking paragraph with one sentence on ASPSP selection and the unchanged restricted-mode constraints.
  - Do not rewrite unrelated sections; keep the existing BBVA production-validation notes where they are evidence of the validated default.
- `.env.example`: add a commented optional block:
  ```text
  # Optional ASPSP selection (defaults: BBVA / ES / personal).
  # The name must match the ASPSP name shown in Enable Banking; the account must be
  # linked in the Enable Banking control panel.
  ENABLE_BANKING_ASPSP_NAME=
  ENABLE_BANKING_ASPSP_COUNTRY=
  ENABLE_BANKING_PSU_TYPE=
  ```
- `ADR-0005`: append `## Amendment (2026-11-15) — ASPSP selection parameterized` recording: the client no longer hardcodes BBVA/ES/personal; the three env vars select the ASPSP; BBVA remains the validated/default target (production evidence 2026-11-14 unchanged); other ASPSPs are configurable but not validated by the owner; restricted-production constraints and the provider decision are unchanged; implemented by this plan/issue #14.
- `docs/ai/architecture.md`: add the three bullets to the Configuration contract and one clause to the Enable Banking paragraph stating the `/aspsps` lookup is parameterized with BBVA/ES/personal defaults.
- `docs/ai/README.md`: append a short repository-status sentence (issue #14, plan path, defaults preserved, non-BBVA unvalidated).
- `docs/ai/logs/2026-11.md`: add a `## 2026-11-15 — Configurable Enable Banking ASPSP` entry with the shipped behavior and the unvalidated-non-BBVA follow-up.
- Set this plan's frontmatter `status: completed` and `task: 3/3` only after the full suite and the doc grep pass.

**Step 4: Run test to verify pass** (owner: agent)

Run: `.\scripts\mvn.ps1 test`
Expected: all tests pass, JaCoCo line coverage >= 80%, no new dependency.

**Step 5: Commit**

```powershell
git add README.md .env.example docs/ai/decisions/ADR-0005-bbva-ais-provider.md docs/ai/architecture.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-15_183741-enable-banking-aspsp-parameterization.md
git commit -m "docs: document configurable Enable Banking ASPSP (issue #14)"
```

## Validation summary

| Validation | Owner | Command / procedure | Expected |
| --- | --- | --- | --- |
| Config unit tests | agent | `.\scripts\mvn.ps1 test '-Dtest=EnableBankingConfigTest,AppConfigTest,ProviderSourcesTest' '-Djacoco.skip=true'` | pass |
| Client/CLI tests | agent | `.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest,MainCommandsTest' '-Djacoco.skip=true'` | pass, defaults unchanged |
| Full suite + gate | agent | `.\scripts\mvn.ps1 test` at the end of every task | BUILD SUCCESS, JaCoCo >= 80% |
| Docs consistency | agent | `Select-String`/grep commands in Task 3 Step 2 | no stale BBVA-only walkthrough wording |
| Real non-BBVA connect | human (owner) | Deferred follow-up: link a non-BBVA account, set the three variables, run `connect` + `fetch` | out of scope for issue #14 |

## Out of scope / follow-ups

- Validating a real non-BBVA ASPSP (needs owner credentials/SCA); keep the existing BBVA production evidence as the validated path.
- Updating `AGENTS.md` and `pom.xml` descriptive text (BBVA remains the default).
- Any new provider, dependency, session/rate-limit, or date-window change.

## Risks

- A wrong ASPSP name now fails at connect time with a clear message instead of silently using BBVA; docs must tell users to copy the exact Enable Banking name.
- Adding record components touches a central config type; the compatibility constructors and the full test suite are the guard.
- The 80% line gate can obscure targeted-run feedback; use `-Djacoco.skip=true` only for intermediate runs and rely on the full run per task.
