---
status: completed
created: 2026-11-15
task: 1/1
required_gates:
  architect_review: false
  memory_update: true
---

# `.env.example` template for the runtime environment contract - Implementation plan

**Goal:** Add a tracked, secret-free `.env.example` at the repository root that lists all seven runtime environment variables with empty values and short comments matching `scripts/run.ps1` parsing, so a new user can copy it to `.env` and fill it in without guessing.

**Architecture:** Docs/config only. `.gitignore` already re-includes `.env.example` (`!.env.example` after `.env.*`), so one new root file is sufficient. `README.md` step 5 gains a one-line pointer to the template; the `## Configuration` table stays canonical. No code, script, workflow, or test changes. The template mirrors the names read by `AppConfig`, `EnableBankingConfig`, `RuntimeState`, and `SheetsConfig`.

**Tech Stack:** Plain-text `.env` file (UTF-8, LF), Markdown (`README.md`, `docs/ai/**`), existing `.\scripts\mvn.ps1 test` gate for regression.

**Scope classification:** `single-task` (1 task). Rationale: one reviewable deliverable — the template file plus its README pointer and memory note. Splitting the file, the link, and the log entry into separate tasks would create artificial tasks with no independent review value.

## Decisions (proposed, confirm at approval)

1. **`BANK2SHEETS_ACCOUNTS` ships blank, not `enablebanking:` (deviation from the issue snippet).** Verified: `AppConfig.fromEnv` calls `parseAccounts` before any command, and `parseAccounts` throws `Invalid BANK2SHEETS_ACCOUNTS entry (expected provider:accountId)` when the account id is empty (`colon == trimmed.length() - 1`). The issue's proposed `BANK2SHEETS_ACCOUNTS=enablebanking:` copied verbatim into `.env` would therefore break **every** command, including `connect`. Ship `BANK2SHEETS_ACCOUNTS=` (blank is safe: empty entries are skipped, so `connect` infers the provider) and put `enablebanking:<uuid>` in the comment. Recommend confirming this change before implementation.
2. **Keep `ENABLE_BANKING_SESSION_ID=` in the template.** Empty locally is correct because `connect` persists `enablebanking.session_id`; the comment states it is required on the Actions runner (secret). This matches README `## Configuration` and `docs/ai/architecture.md`.
3. **One README pointer only.** Add a single sentence in step 5 ("`Create .env`") pointing at `.env.example`; keep the existing three-line `connect`-phase block and the `## Configuration` table unchanged. No duplicate variable table in the template beyond the commented format hint.
4. **No automated test for the template.** A test would need CWD-relative file access and would duplicate the README table; the change is static docs/config. Validation stays bounded and agent-owned (checks in Task 1). Revisit only if the env contract starts drifting repeatedly.
5. **Comments must describe `run.ps1` exactly:** repo-root `.env`, one `KEY=VALUE` per line, split on the first `=`, no quote stripping, no inline comments (everything after `=` becomes the value), base64 `=` padding fine, blanks/`#` lines skipped, values never printed.

## Verified repository facts

- `.gitignore:11-13` ignores `.env` and `.env.*` then re-includes `!.env.example`; `git check-ignore .env.example` exits 1 (not ignored) and `Test-Path .env.example` is `false` (file does not exist yet).
- `scripts/run.ps1:28-40`: loads the repo-root `.env` if present, trims each line, skips blank and `#` lines, splits on the first `=` only, and assigns the raw right-hand side. It performs no quote stripping, no inline-comment stripping, and no value trimming, and never prints values.
- Env contract names read in `src/main/java`: `AppConfig` (all `ENABLE_BANKING_*` + `BANK2SHEETS_ACCOUNTS`), `SheetsConfig`/`GoogleSheetsClient` (`GOOGLE_SERVICE_ACCOUNT_B64`, `GOOGLE_SPREADSHEET_ID`), `RuntimeState` (`enablebanking.session_id` state key, not an env var).
- Required/optional usage: `ENABLE_BANKING_APP_ID`, `ENABLE_BANKING_PRIVATE_KEY_B64`, `ENABLE_BANKING_REDIRECT_URL` required for `connect`/`fetch`/`sync`; `ENABLE_BANKING_SESSION_ID` optional locally after `connect`, required on the Actions runner; `BANK2SHEETS_ACCOUNTS` required for `fetch`/`sync`; `GOOGLE_SERVICE_ACCOUNT_B64`, `GOOGLE_SPREADSHEET_ID` required for `sync`.
- `AppConfig.parseAccounts`: blank/whitespace entries are skipped; entries with no `:` or an empty account id throw; providers other than `enablebanking` throw.
- `README.md:70-80` is step 5 where `.env` is created; `README.md:190-204` is the canonical `## Configuration` table; `README.md:222-242` documents the seven Actions secrets.
- `.editorconfig` `[*]` requires UTF-8, LF, final newline, no trailing whitespace — the new file must comply.
- Current suite: **83 tests** (sum of `target/surefire-reports/TEST-*.xml` = 92 minus the stale, already-deleted `SaltEdgeClientTest` report = 9), JaCoCo 80% line gate, `BUILD SUCCESS`.
- `docs/ai/README.md:9` is the status paragraph that records recent deliverable additions; `docs/ai/logs/2026-11.md` is the monthly log.

## Tasks

### Task 1: Add the tracked `.env.example` template and point the README setup step at it

**Objective:** Create a secret-free `.env.example` at the repository root that a new user can copy to `.env`, with every supported variable present as an empty value and comments that match `scripts/run.ps1` parsing exactly; link it from the README setup step and record the change in `docs/ai`.

**Files:**
- Create: `.env.example`
- Modify: `README.md:70-80` (one sentence in "5. Create `.env` in the repository root")
- Modify: `docs/ai/README.md:9` (append a short clause about the template)
- Modify: `docs/ai/logs/2026-11.md` (append a dated completion entry)
- Validate: `.env.example` (content/format), `.\scripts\mvn.ps1 test`, `git diff --check`

**Step 1: Establish expected behaviour**

A user clones the repository and copies `.env.example` to `.env`. The file is tracked by git (not ignored), contains no secrets or real ids, and lists exactly the seven supported variables as `KEY=` lines with explanatory comments. Copying it unchanged and filling in only the Enable Banking values yields a working `connect` (blank `BANK2SHEETS_ACCOUNTS` is safe; `connect` infers the provider). Copying it unchanged and filling in the Google values plus `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>` yields a working `sync`. Comments state that values are single-line and unquoted (quotes become part of the value), that inline comments are not stripped, that base64 `=` padding is fine, and that the session id is optional locally but required on the Actions runner.

**Step 2: Add or update validation**

No automated test covers a dotenv template. Validate with bounded, non-interactive checks:
- `Test-Path .env.example` -> `True`; `git check-ignore .env.example` -> exit code 1 (file is not ignored).
- `Select-String -Path .env.example -Pattern '^(ENABLE_BANKING_APP_ID|ENABLE_BANKING_PRIVATE_KEY_B64|ENABLE_BANKING_REDIRECT_URL|ENABLE_BANKING_SESSION_ID|BANK2SHEETS_ACCOUNTS|GOOGLE_SERVICE_ACCOUNT_B64|GOOGLE_SPREADSHEET_ID)='` -> exactly 7 matches, one per variable, no duplicates and no unknown keys.
- `Select-String -Path .env.example -Pattern '^[A-Z0-9_]+=\S'` -> no matches (every live assignment is empty).
- Parser smoke test (replicates `run.ps1`): `Get-Content .env.example | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { ($_ -split '=', 2)[0].Trim() }` -> the seven known keys and nothing else; then confirm `BANK2SHEETS_ACCOUNTS` has a blank value so `parseAccounts` returns an empty list (no throw).
- Secret scan: `git diff -- .env.example | Select-String -Pattern 'BEGIN [A-Z ]*PRIVATE KEY|client_email|@[a-z0-9.-]+\.(com|es)|[A-Za-z0-9+/]{40,}={0,2}'` -> no matches.
- `git diff --check` -> no trailing whitespace / line-ending problems.
- `.\scripts\mvn.ps1 test` -> 83 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS` (the change must not disturb the gate).
- After `git add`, `git ls-files --error-unmatch .env.example` -> exits 0 (tracked).

Owner: `agent` (bounded, non-interactive).

**Step 3: Write minimal implementation**

Create `.env.example` with exactly this content (UTF-8, LF, final newline; `#` lines and blanks are skipped by `run.ps1`):

```text
# Copy to .env and fill in. .env is gitignored; scripts/run.ps1 loads it at the repo root.
# One KEY=VALUE per line, values single-line and unquoted (quotes become part of the value),
# no inline comments (everything after the first '=' is the value). Base64 '=' padding is fine.

ENABLE_BANKING_APP_ID=
ENABLE_BANKING_PRIVATE_KEY_B64=
ENABLE_BANKING_REDIRECT_URL=
# Optional locally: `connect` persists enablebanking.session_id to bank2sheets-state.properties.
# Required on the GitHub Actions runner (ENABLE_BANKING_SESSION_ID secret).
ENABLE_BANKING_SESSION_ID=
# Comma-separated provider:accountId entries, e.g. enablebanking:<uuid>. Leave empty for `connect`.
BANK2SHEETS_ACCOUNTS=
GOOGLE_SERVICE_ACCOUNT_B64=
GOOGLE_SPREADSHEET_ID=
```

Then, in `README.md` step 5 (after the `.env` fenced block, before the "See [Configuration]" sentence), add one sentence: "Or copy the tracked `.env.example` to `.env` and fill in the values." Do not restructure the walkthrough, do not change the three-line block, and do not edit the `## Configuration` table.

Then append a `## 2026-11-15 - .env.example template` entry to `docs/ai/logs/2026-11.md` recording: the added template, the blank-`BANK2SHEETS_ACCOUNTS` decision (and why `enablebanking:` would throw), the README pointer, the validation results, and the issue/plan reference. Update `docs/ai/README.md:9` by appending a short clause that the repo now ships a tracked `.env.example` (keep the paragraph structure intact).

Do not add real credentials, app ids, account ids, spreadsheet ids, personal emails, or `.env`/`.env.sandbox` contents.

**Step 4: Run test to verify pass**

Run: the Step 2 checks, then `.\scripts\mvn.ps1 test`, then `git diff --check`.

Expected: `.env.example` exists and is not ignored; exactly seven known empty assignments; parser smoke test yields only those seven keys with blank values; no key material or personal identifiers in the diff; `BUILD SUCCESS` with 83 tests and the JaCoCo gate met; `git diff --check` silent.

Owner: `agent`.

**Step 5: Commit**

`git add .env.example README.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-15_182158-add-env-example-template.md`
`git commit -m "docs: add .env.example template for required environment variables (issue #13)"`

**Step 6: Reference issue #13**

Comment on issue #13 with the commit hash, the validation evidence, and the blank-`BANK2SHEETS_ACCOUNTS` decision. `gh` is not installed on this machine; use the `shared-task` helper's GitHub App token path for comments (a short `temp/` tsx script importing `addComment`/`apiPost`, as done previously) or `GITHUB_TOKEN` with `npx tsx .opencode/skills/shared-task/scripts/gh-issue.ts ...`. Do not close the issue until the owner confirms.

Owner: `agent` for the comment; `human` for the close decision.

## Out of scope

- Changing `scripts/run.ps1` parsing behavior or adding env/config automation.
- Adding an automated test that asserts `.env.example` contents.
- Real credentials, application/account/spreadsheet ids, service-account JSON, or personal emails.
- `.env`, `.env.sandbox`, or any other local secret file contents.
- GitHub Actions workflow or secret changes.
- Parameterizing the Enable Banking ASPSP (follow-up issue #14).
- Rewriting historical AI logs, completed plans, or ADRs.

## Validation ownership

| Validation | Owner | Notes |
| --- | --- | --- |
| `.env.example` existence / not-ignored / tracked checks | agent | Read-only, bounded |
| Key-set, empty-value, and parser smoke checks | agent | Read-only, bounded PowerShell |
| Diff secret/identifier scan and `git diff --check` | agent | Read-only, bounded |
| `.\scripts\mvn.ps1 test` | agent | Existing gate; unchanged code |
| Copy `.env.example` to `.env`, fill real values, run `connect`/`sync` | human | Requires EB/Google credentials and interactive SCA; optional sanity check, not required to close |

## Required gates

- `architect_review: false` - docs/config template only; no boundary, dependency, or provider change.
- `memory_update: true` - `docs/ai/README.md` status line and `docs/ai/logs/2026-11.md` completion entry.
- Test gate: `.\scripts\mvn.ps1 test` must stay green (83 tests, JaCoCo 80% line coverage).
- Blocking decision at approval: confirm decision 1 (blank `BANK2SHEETS_ACCOUNTS=` instead of the issue's `enablebanking:`).
- Issue #13 stays open until the owner confirms; comment with the commit and validation evidence.
