---
status: in-progress
created: 2026-11-16
task: 2/3
required_gates:
  architect_review: true
  memory_update: true
---

# ASPSP rate-limit recovery hardening

**Goal:** Make `sync` fail with actionable guidance when BBVA's background AIS allowance is spent, let the owner avoid the extra `/details` access with configured account labels, and keep the monthly scheduled sync from losing a month to a `429`.

**Architecture:** The Enable Banking client centralizes non-2xx failure text in one helper and appends bank-quota guidance only for `429` plus `ASPSP_RATE_LIMIT_EXCEEDED`. `AccountConfig` gains an optional label parsed from `BANK2SHEETS_ACCOUNTS`; `sync`/`fetch` call `listAccounts()` only when at least one configured account lacks a label, so existing configs keep today's behaviour while labelled configs spend zero ASPSP calls on account details. The workflow adds a next-day cron, and README/ADR-0005 record the operational rule.

**Tech Stack:** Java 21, Gson, JUnit 5, Maven/JaCoCo (80% line gate), GitHub Actions.

**Scope classification:** `multi-task` (3 tasks: error handling, access-saving config, ops/docs; each independently reviewable)

## Discovery (verified 2026-11-16)

- `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java:334-341` `request()` throws the raw status/method/path/body; `:128-139` `isActive()` duplicates that throw. `:159-173` `fetchTransactions()` pages through `request()`, so a `429` on any page surfaces the raw body (the reported failure was on the first transactions page).
- `EnableBankingClient.java:285-292` `accountName()` calls `GET /accounts/{uuid}/details` and swallows failures. `:142-156` `listAccounts()` calls `/details` for every account in the session, not only the configured ones.
- `src/main/java/bank2sheets/Main.java:233` `sync()` always calls `accountNames(source)`; `:277-284` `accountNames()` calls `source.listAccounts()` once; `:237-241` resolves each configured account label from that map with an id fallback. `fetch()` at `:166` does the same.
- `src/main/java/bank2sheets/config/AccountConfig.java:8` is `record AccountConfig(String provider, String providerAccountId)`. `src/main/java/bank2sheets/config/AppConfig.java:101-122` parses `BANK2SHEETS_ACCOUNTS` with `indexOf(':')`. `src/test/java/bank2sheets/config/AppConfigTest.java:22-60` covers the current parsing.
- `src/test/java/bank2sheets/MainCommandsTest.java:143-408` sync tests stub `stubSession` + `stubAccountDetails` and assert `Cuenta Nominas`; `:519-524` `enableBankingConfig` builds one `AccountConfig("enablebanking", "uuid-1")`.
- `README.md:126-130` tells the owner to run `sync` twice; `:138-153` troubleshooting table has no `429` row; `:207` documents `BANK2SHEETS_ACCOUNTS` as `provider:accountId`; `:211` says only "ASPSP rate limits apply (bank-specific, commonly a few background fetches per day)"; `:231` documents the single `0 6 1 * *` cron; `:259` documents the `Cuenta` fallback.
- `.github/workflows/sync.yml:3-7` has only the monthly cron; `:38` runs `java -jar ... sync`.
- `.env.example:16` documents `provider:accountId`; the scheduled-secret table at `README.md:235-243` shows `enablebanking:<uuid>`.
- Enable Banking FAQ (dated 2026-11-16): background AIS is commonly limited to **4 accesses per 24 h** per consent; `ASPSP_RATE_LIMIT_EXCEEDED` means the bank allowance is spent, and Enable Banking recommends continuing after ~6 hours. `GET /sessions/{session_id}` `accounts_data` (`SessionAccount`) carries only `identification_hash`/`identification_hashes`/`uid`, not the display name, so `/details` is the only source of the bank name.
- Baseline: clean tree at `60a2de9`; validation command `.\scripts\mvn.ps1 test`.

## Decisions (recommended defaults; confirm before implementation)

- **D1 (blocking): optional labels.** Adopt `enablebanking:<uuid>[:<label>]`. When every configured account has a label, `sync`/`fetch` skip `listAccounts()` and `/details` entirely. Configs without labels keep today's bank-name lookup, so the change is opt-in and non-breaking. Alternative: keep `/details` always and drop Task 2.
- **D2 (blocking): scheduled retry.** Add a second cron `0 6 2 * *` so a `429` on the 1st is retried the next day; if the 1st succeeded, the 2nd appends `0` rows. Alternative: manual re-dispatch only; Task 3 becomes docs-only.
- **D3 (non-blocking): guidance timing.** Recommend "wait ~6 hours or until the next day": the Enable Banking FAQ says 6 hours, while a rolling 24 h allowance makes the next day the safe default.
- **D4 (non-blocking): generic 429s.** Only a body containing `ASPSP_RATE_LIMIT_EXCEEDED` gets the extra allowance/retry guidance; other 429s keep the raw message.

## Tasks

### Task 1: Actionable ASPSP rate-limit failure

**Objective:** Turn a `429` / `ASPSP_RATE_LIMIT_EXCEEDED` into a message that says the bank's background allowance is spent and what to do next.

**Files:**
- Modify: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java`
- Validate: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java`
- Validate: `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour**

A `429` whose body contains `ASPSP_RATE_LIMIT_EXCEEDED` (the BBVA `[HUB046]` case) keeps the existing `Enable Banking request failed (HTTP <status>): <METHOD> <path> -> <body>` prefix and appends one sentence: the bank's background AIS allowance for this consent is used up (commonly ~4 accesses per 24 h); retrying now will not help; wait ~6 hours or until the next day, or run `connect` for a fresh consent. Non-429 and non-ASPSP failures keep the current message. `fetch`/`sync` still exit `1` and keep their `fetch:`/`sync:` prefixes.

**Step 2: Add or update validation**

- `EnableBankingClientTest`: add `rateLimitFailureExplainsSpentBackgroundAllowance`, stubbing the exact reported body on `/accounts/uuid-1/transactions?transaction_status=BOOK`:
  ```json
  {"code":429,"message":"[HUB046] Allowed number of accesses exceeded for consent.","detail":{"message":"[HUB046] Allowed number of accesses exceeded for consent.","error_name":"RateLimitException","error_data":{}},"error":"ASPSP_RATE_LIMIT_EXCEEDED"}
  ```
  Call `fetchTransactions("uuid-1")` and assert the exception message contains `429`, `ASPSP_RATE_LIMIT_EXCEEDED`, `background AIS access allowance`, and `next day`.
- Strengthen `nonSuccessStatusThrowsWithStatusMethodPathAndBody` (`:363-375`) with `assertFalse(error.getMessage().contains("allowance"))` so a 401 never gets quota guidance.
- `MainCommandsTest`: add `enableBankingSyncWithRateLimitExitsNonZeroAndExplains`, stubbing the session (`["uuid-1"]`), account details (`uuid-1`), and the transactions `429`; assert exit `1` and stderr contains `sync:` plus `ASPSP_RATE_LIMIT_EXCEEDED` (or `background AIS access allowance`).

**Step 3: Write minimal implementation**

Extract `private Bank2SheetsException failure(String method, String path, Response response)` that builds the existing message and, when `response.status() == 429` and the body contains `ASPSP_RATE_LIMIT_EXCEEDED` case-insensitively (`toUpperCase(Locale.ROOT)`), appends:
`" The bank's background AIS access allowance for this consent is used up (ASPSP_RATE_LIMIT_EXCEEDED; commonly ~4 accesses per 24 h). Retrying now will not help: wait ~6 hours or until the next day, or run `connect` for a fresh consent."`
Use the helper from `request()` and from the non-`404` branch of `isActive()`. Keep the raw body for diagnostics.

**Step 4: Run test to verify pass**

Owner: agent.
Run: `.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest,MainCommandsTest'`
Expected: all selected tests pass, including the new 429 assertions.
Run: `.\scripts\mvn.ps1 test`
Expected: full suite green, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`.

**Step 5: Commit**

`git add src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java src/test/java/bank2sheets/MainCommandsTest.java`
`git commit -m "fix: explain spent ASPSP rate limit in provider errors"`

### Task 2: Optional account labels to avoid `/details` accesses

**Objective:** Let the owner configure a `Cuenta` label in `BANK2SHEETS_ACCOUNTS`; when every configured account has one, `sync`/`fetch` skip `listAccounts()` and the per-account `/details` ASPSP calls.

**Files:**
- Modify: `src/main/java/bank2sheets/config/AccountConfig.java`
- Modify: `src/main/java/bank2sheets/config/AppConfig.java`
- Modify: `src/main/java/bank2sheets/Main.java:225-284`
- Modify: `README.md` (step 6, configuration table, Google Sheets layout)
- Modify: `.env.example`
- Validate: `src/test/java/bank2sheets/config/AppConfigTest.java`
- Validate: `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour**

`BANK2SHEETS_ACCOUNTS` accepts `provider:accountId` or `provider:accountId:label` per entry. The label is optional, trimmed, and may contain spaces or colons (commas still separate entries, so a label cannot contain a comma). Existing entries parse exactly as today. `sync` and `fetch` resolve a row/account label as: configured label, else bank display name (only when at least one configured account lacks a label), else provider account id. `connect` is unchanged and still prints the available account names.

**Step 2: Add or update validation**

- `AppConfigTest`: add `parsesAccountLabel` (`"enablebanking:uuid-1:Cuenta Nóminas"` -> `new AccountConfig("enablebanking", "uuid-1", "Cuenta Nóminas")`), `labelIsOptional` (existing two-part entries still yield an empty label), and `rejectsMissingAccountIdWithLabel` (`"enablebanking::Label"`).
- `MainCommandsTest`: add `enableBankingSyncUsesConfiguredLabelWithoutAccountDetails` with a config built from `new AccountConfig("enablebanking", "uuid-1", "Cuenta Nóminas")`, stubbing only the transactions payload and the existing Sheets month/ledger helpers. Assert exit `0`, the appended row contains `Cuenta Nóminas`, and `http.requests` contains no URL ending in `/details` or `/sessions`.
- Existing unlabelled sync/fetch tests (`:143-408`) stay unchanged and must still pass: they exercise the bank-name fallback.

**Step 3: Write minimal implementation**

- `AccountConfig`: add `label` to the record, a compact constructor that maps `null` to `""` and trims, and a two-argument convenience constructor defaulting the label to `""` so existing call sites compile.
- `AppConfig.parseAccounts`: split each entry with `trimmed.split(":", 3)`; reject fewer than two parts or a blank provider/account id; take `parts[2].trim()` as the label when present; update the Javadoc and error text to `provider:accountId[:label]`.
- `Main.sync()`: compute `boolean needsNames = resolved.accounts().stream().anyMatch(a -> a.label().isBlank());`; call `accountNames(source)` only when true; resolve `String label = account.label();` then `label = accountLabels.getOrDefault(id, "")`, then `label = id`.
- `Main.fetch()`: apply the same `needsNames` guard and label resolution for the printed account line.
- Update the `Main.sync()` and `accountNames()` Javadocs to say labels skip the session/details calls.
- Docs: update `README.md:100-103` (`BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>[:<label>]`), `:207` (optional label and its access-saving purpose), `:259` (`Cuenta` = configured label, else bank name, else id), and `.env.example:16` (same syntax plus a note that the label avoids `/details`).

**Step 4: Run test to verify pass**

Owner: agent.
Run: `.\scripts\mvn.ps1 test '-Dtest=AppConfigTest,MainCommandsTest'`
Expected: the new label parsing and no-details sync tests pass; existing unlabelled sync tests still pass.
Run: `.\scripts\mvn.ps1 test`
Expected: full suite green, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`.

**Step 5: Commit**

`git add src/main/java/bank2sheets/config/AccountConfig.java src/main/java/bank2sheets/config/AppConfig.java src/main/java/bank2sheets/Main.java src/test/java/bank2sheets/config/AppConfigTest.java src/test/java/bank2sheets/MainCommandsTest.java README.md .env.example`
`git commit -m "feat: allow optional account labels to skip ASPSP details calls"`

### Task 3: Document the limit and retry the scheduled sync the next day

**Objective:** Make the `429` recovery rule discoverable and give the monthly workflow one automatic retry without changing project scope.

**Files:**
- Modify: `.github/workflows/sync.yml:3-7`
- Modify: `README.md` (step 8, troubleshooting, configuration paragraph, scheduled sync)
- Modify: `AGENTS.md` (scheduled sync bullet)
- Modify: `docs/ai/architecture.md:33`
- Modify: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Modify: `docs/ai/logs/2026-11.md`
- Modify: `docs/ai/README.md` (status line)

**Step 1: Establish expected behaviour**

The workflow runs on the 1st and the 2nd at 06:00 UTC (`0 6 1 * *` and `0 6 2 * *`); the 2nd is a no-op when the 1st already appended everything. README explains the bank's ~4 accesses/24 h background limit, the `429`/`HUB046` recovery (wait ~6 h or next day, or re-consent; do not loop), the optional label syntax, and stops recommending two immediate `sync` runs. AGENTS/architecture/ADR-0005 record the durable rule.

**Step 2: Add or update validation**

Owner: agent.
- After the edits, run `Select-String` checks:
  - `README.md` contains `ASPSP_RATE_LIMIT_EXCEEDED`, `0 6 2 * *`, and `provider:accountId[:label]`.
  - `AGENTS.md` contains `0 6 2 * *`.
  - `.env.example` contains `[:label]`.
  - `docs/ai/architecture.md` and `docs/ai/decisions/ADR-0005-bbva-ais-provider.md` contain the `4 accesses per 24 h` / next-day retry note.
- Run `.\scripts\mvn.ps1 test` (docs-only change; expected full suite green and gate met).

Owner: human.
- After merge, trigger `workflow_dispatch` once against a disposable sheet and confirm the run succeeds; the cron schedule itself cannot be validated locally. Optionally confirm both schedules are listed with `gh workflow view sync.yml`.

**Step 3: Write minimal implementation**

- `.github/workflows/sync.yml`: add `- cron: '0 6 2 * *'` under the existing schedule with a comment explaining the next-day retry after a bank allowance `429`.
- `README.md`:
  - Step 8: keep one `.\scripts\run.ps1 sync` and state that a second immediate run appends nothing but still spends the bank's allowance.
  - Troubleshooting: add a row for `Enable Banking request failed (HTTP 429)` / `ASPSP_RATE_LIMIT_EXCEEDED` / `[HUB046]`, with the wait/re-consent/no-loop fix and the label tip.
  - Configuration paragraph (`:211`): quantify the ~4 accesses/24 h background limit, the `429` recovery rule, and the optional label.
  - Scheduled sync (`:231`): list both crons and explain the retry.
  - Secrets table (`:241`): show `enablebanking:<uuid>:Cuenta Nóminas` (label optional).
- `AGENTS.md`: update the scheduled-sync bullet from `0 6 1 * *` to both crons and note the retry.
- `docs/ai/architecture.md:33`: append the ~4/24 h background limit, the actionable `429` guidance, the next-day retry, and the label opt-in.
- `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`: append a dated update recording the same durable operational decision (no new ADR).
- `docs/ai/logs/2026-11.md`: add an entry with the plan path, what shipped, validation, and commits.
- `docs/ai/README.md`: update the repository-status "Latest change" sentence after the code tasks ship.

**Step 4: Run test to verify pass**

Owner: agent.
Run: `.\scripts\mvn.ps1 test`
Expected: `BUILD SUCCESS`, 0 failures/errors, JaCoCo 80% line gate met.
Run the `Select-String` checks listed in Step 2.
Expected: every phrase is found; no stale single-cron or two-immediate-sync instruction remains.

Owner: human.
Run: `gh workflow view sync.yml` (optional) and `workflow_dispatch` once against a disposable sheet.
Expected: the run succeeds; the Actions tab lists the 1st and 2nd schedules.

**Step 5: Commit**

`git add .github/workflows/sync.yml README.md AGENTS.md docs/ai/architecture.md docs/ai/decisions/ADR-0005-bbva-ais-provider.md docs/ai/logs/2026-11.md docs/ai/README.md`
`git commit -m "docs: document ASPSP rate limits and retry the monthly sync"`

## Validation ownership

- Task 1: agent (unit + CLI tests, full suite).
- Task 2: agent (unit + CLI tests, full suite).
- Task 3: agent (doc phrase checks, full suite) and human (`workflow_dispatch` / Actions schedule check).

## Required gates

- `architect_review`: confirm the optional `BANK2SHEETS_ACCOUNTS` label contract and the two-cron retry do not conflict with ADR-0005's restricted-production/rate-limit assumptions. If D1 is rejected, Task 2 is dropped and the gate narrows to the workflow/cadence change (or can be waived).
- `memory_update`: README, AGENTS, `docs/ai/architecture.md`, ADR-0005 update, `docs/ai/logs/2026-11.md`, and the `docs/ai/README.md` status line.

## Out of scope

- Automatic retry/backoff inside a single CLI run (a 429 is a metered daily allowance; sleeping or looping in the process is not useful).
- PSU headers to bypass background limits (Enable Banking exposes them, but the CLI does not know a trustworthy PSU IP/User-Agent and the scheduled run is intentionally background).
- Caching account names in the runtime state file (labels in `BANK2SHEETS_ACCOUNTS` are simpler and work in the ephemeral Actions runner).
- Changing the monthly cadence, adding a database/queue, or adding dependencies.
