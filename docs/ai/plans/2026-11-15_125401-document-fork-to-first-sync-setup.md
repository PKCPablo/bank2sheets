---
status: planned
created: 2026-11-15
task: 1/2
required_gates:
  architect_review: false
  memory_update: true
---

# Fork-to-first-sync documentation + fork-friendly scheduled workflow - Implementation plan

**Goal:** Add a single, ordered, end-to-end setup walkthrough to `README.md` that takes a technical stranger from forking the repository to a working `.\scripts\run.ps1 sync` against their own BBVA account and their own spreadsheet, then hands off to the optional scheduled workflow; remove the workflow's repository-only guard so a fork that enables Actions and configures secrets can actually run it.

**Architecture:** Two small, separable deliverables. `.github/workflows/sync.yml` loses its one-line job guard (no schedule, step, permission, concurrency, or secret changes). `README.md` gains the walkthrough and links to the existing reference sections instead of duplicating them, reusing the exact variable names, script paths, and error text implemented in `config/`, `Main`, `EnableBankingClient`, `GoogleSheetsClient`, and the workflow. Upstream behavior is unchanged: GitHub disables scheduled workflows in new forks by default (`disabled_fork`), so removing the guard does not create runs anywhere until a fork owner enables Actions.

**Tech Stack:** Markdown, PowerShell examples (Windows primary path, consistent with the current README), GitHub Actions YAML, existing Maven/JaCoCo gate for regression (`.\scripts\mvn.ps1 test`).

**Scope classification:** `multi-task` (2 tasks). Rationale: the workflow change is a distinct reviewable deliverable (config) from the README walkthrough (docs), and Task 2's step 9 must describe the post-change behavior. Both are tiny; no further split is warranted.

**Decisions (proposed, confirm at approval):**

1. **Production-first, sandbox as a callout.** The walkthrough's primary path is Enable Banking restricted production (ADR-0005; the mode the owner actually uses with real BBVA). Sandbox is a short "try it with fake data" note inside the Enable Banking steps because sandbox data is not real transactions. This matches the project's only-provider decision and avoids sending a stranger down a fake-data path.
2. **Fork redirect uses the forker's own HTTPS URL (confirmed 2026-11-15).** Production apps cannot register `http://127.0.0.1` (recorded in `docs/ai/logs/2026-11.md`, 2026-11-13 entry), so the production walkthrough tells the reader to register an HTTPS URL they control and to set `ENABLE_BANKING_REDIRECT_URL` to exactly that value. GitHub Pages on their fork (`https://<user>.github.io/bank2sheets/callback.html`) is presented as the zero-cost example, not as a requirement; any HTTPS host works because the CLI only needs the full callback URL pasted back. The upstream `https://pkcpablo.github.io/bank2sheets/callback.html` is only an example. The sandbox loopback URL remains `http://127.0.0.1:8765/bank2sheets/callback`.
3. **Remove the fork guard (confirmed 2026-11-15, option b).** Delete `.github/workflows/sync.yml:18` (`if: github.repository == 'PKCPablo/bank2sheets'`). Upstream behavior is unchanged. Forks still do nothing until their owner enables Actions (GitHub state `disabled_fork`) and adds secrets; a fork that enables Actions without secrets gets a visible monthly failure in its own repo, which is informational and cannot touch upstream secrets (secrets are repository-scoped). No repository-variable opt-in: it would duplicate GitHub's own fork default (YAGNI).
4. **No `.env.example` in this plan.** `.gitignore` already allowlists `.env.example`, but the file does not exist and the issue scopes the change to `README.md`. The walkthrough shows the `.env` contents inline instead. Follow-up: issue #13 ("Add .env.example template for required environment variables"), created 2026-11-15.
5. **BBVA only for this plan.** `EnableBankingClient` hardcodes `ASPSP_NAME="BBVA"`, `ASPSP_COUNTRY="ES"`, `PSU_TYPE="personal"`, so the README must not imply other ASPSPs work. The issue text's "BBVA (or other supported ASPSP)" is corrected to BBVA. Follow-up: issue #14 ("Parameterize Enable Banking ASPSP selection"), created 2026-11-15; when it lands, the README table/walkthrough gain the `ENABLE_BANKING_ASPSP_*` variables.

**Verified repository facts the walkthrough must match:**

- `.\scripts\mvn.ps1 test|package` and the fat JAR path `target\bank2sheets-0.1.0-SNAPSHOT.jar`; `mvn test` runs 83 tests and the JaCoCo 80% line gate.
- `.\scripts\run.ps1 <command>` loads a repo-root `.env` (`KEY=VALUE`, split on the first `=`, no value printing), resolves JDK 21+, and prints the build hint when the JAR is missing.
- `.env` is gitignored; `bank2sheets-state.properties` is gitignored and holds only `enablebanking.session_id`.
- Env contract: `ENABLE_BANKING_APP_ID`, `ENABLE_BANKING_PRIVATE_KEY_B64`, `ENABLE_BANKING_REDIRECT_URL`, optional `ENABLE_BANKING_SESSION_ID`, `BANK2SHEETS_ACCOUNTS` (`enablebanking:<uuid>`), `GOOGLE_SERVICE_ACCOUNT_B64`, `GOOGLE_SPREADSHEET_ID`.
- `EnableBankingConfig` validates the redirect: `http` only for `127.0.0.1`/`localhost` with an explicit port and non-empty path; `https` for any non-blank host with a non-empty path.
- `EnableBankingClient.privateKeyPem()` base64-decodes the env value into a PKCS#8 PEM; `Rs256Jwt` strips `BEGIN/END PRIVATE KEY` and whitespace, so base64 of the whole `.pem` file text is correct.
- `connect` output: prints the auth URL, waits for Enter, persists the connection id, then prints `Available accounts:` with `id (name)` lines and `Set BANK2SHEETS_ACCOUNTS=enablebanking:<accountId> for \`fetch\`/\`sync\`.`; failure text includes `connect: connection <id> is not active yet` and `Timed out after 300 s waiting for the authorization callback`.
- `fetch`/`sync` guards: `no accounts configured; set BANK2SHEETS_ACCOUNTS (e.g. enablebanking:<uuid>)` and `no connection; run \`connect\` once or set ENABLE_BANKING_SESSION_ID`; the persisted state file is used when the env session id is blank.
- `sync` prints `sync: fetched N transaction(s), appended M new row(s), skipped K duplicate(s)`; dedupe is the `TransactionId` column; header `Fecha, Descripción, Importe, Moneda, TransactionId` is written when missing to the first sheet, range `A1:E`, `valueInputOption=RAW`.
- `GoogleSheetsClient` requires `client_email` and `private_key` in the decoded service-account JSON; the spreadsheet must be shared with that `client_email`.
- `.github/workflows/sync.yml` runs monthly (`0 6 1 * *`) plus `workflow_dispatch`, builds with `mvn -B -q package`, and passes the seven secrets listed in the current README table; it never runs `connect`. Its job-level `if: github.repository == 'PKCPablo/bank2sheets'` is removed by Task 1.
- `docs/callback.html` exists and is the static Pages callback that displays the full URL to paste.

## Tasks

### Task 1: Remove the repository-only guard from the scheduled workflow

**Objective:** Delete the job-level `if:` that limits `sync.yml` to `PKCPablo/bank2sheets`, so a fork that enables Actions and configures its own secrets can run the same workflow, while leaving the upstream schedule and manual dispatch behavior unchanged.

**Files:**
- Modify: `.github/workflows/sync.yml:18` (delete the `if: github.repository == 'PKCPablo/bank2sheets'` line; optional one-line comment above `jobs.sync`)
- Validate: `.github/workflows/sync.yml` (structure/indentation review), targeted `Select-String` checks, `git diff --check`

**Step 1: Establish expected behaviour**

The `sync` job runs whenever the workflow is triggered in whichever repository hosts it. The upstream schedule (`0 6 1 * *`) and `workflow_dispatch` behave exactly as today. A fork does not start syncing by itself: GitHub disables scheduled workflows by default when a public repository is forked (`disabled_fork`), so nothing runs until the fork owner enables Actions/workflows and adds the seven secrets. Secrets remain repository-scoped; a fork cannot read upstream secrets. A fork that enables Actions without secrets gets a visible monthly failure in its own repository, which is acceptable and informative. The cron cadence, permissions, concurrency group, steps, and secret names are not touched.

**Step 2: Add or update validation**

No automated test covers workflow YAML. Validate with bounded read-only checks:
- `Select-String -Path .github\workflows\sync.yml -Pattern "github\.repository ==|PKCPablo/bank2sheets"` -> no matches (guard gone; no leftover repo-name condition).
- `Select-String -Path .github\workflows\sync.yml -Pattern "cron: '0 6 1 \* \*'|workflow_dispatch:|runs-on: ubuntu-latest|permissions:|concurrency:|ENABLE_BANKING_SESSION_ID|GOOGLE_SPREADSHEET_ID"` -> all expected anchors still present.
- `Get-Content .github\workflows\sync.yml` -> `jobs:` contains `sync:` directly followed by `runs-on: ubuntu-latest` (valid indentation after removing the `if:` line).
- `git diff --check` -> clean.

Owner: `agent` (bounded, non-interactive).

**Step 3: Write minimal implementation**

1. In `.github/workflows/sync.yml`, delete the line `    if: github.repository == 'PKCPablo/bank2sheets'` (line 18).
2. Optionally add one comment above the job explaining the fork behavior: `# Runs in the upstream repo; forks must enable Actions and add the secrets below.`
3. Do not change `on:`, the cron, `permissions`, `concurrency`, the checkout/setup-java/build steps, the env mapping, or the `java -jar ... sync` command.

**Step 4: Run test to verify pass**

Run: the Step 2 `Select-String` checks and `git diff --check`.

Expected: no `github.repository ==` or repo-name match; cron, `workflow_dispatch`, `runs-on`, permissions, concurrency, and secret env names intact; indentation valid; `git diff --check` silent. (The full `.\scripts\mvn.ps1 test` gate runs in Task 2, since this task does not touch Java.)

Owner: `agent`.

**Step 5: Commit**

`git add .github/workflows/sync.yml`
`git commit -m "chore: allow scheduled sync in forks (issue #11)"`

### Task 2: Add the end-to-end setup walkthrough and align the reference sections

**Objective:** Insert a `## Set up from scratch` section that walks a new user from fork to first idempotent sync, and make the existing `Requirements`, `Run`, `Configuration`, and `Scheduled sync` sections link to it instead of repeating setup prose.

**Files:**
- Modify: `README.md` (insert new section after `## Requirements`; touch `## Requirements`, `## Run`, `## Configuration`, `## Scheduled sync`; keep `## Build`, `## Google Sheets layout`, `## License`)
- Modify: `docs/ai/README.md:9` (state that README now carries the fork-to-first-sync walkthrough)
- Modify: `docs/ai/logs/2026-11.md` (append a dated completion entry after implementation)
- Validate: `README.md` (rendered read-through), `.\scripts\mvn.ps1 test`, `git diff --check`, targeted `Select-String` checks

**Step 1: Establish expected behaviour**

A reader with no prior context can follow `README.md` top-to-bottom and reach a successful `.\scripts\run.ps1 sync` without reverse-engineering ordering: create the Enable Banking app, register a redirect that the CLI accepts, base64-encode a PKCS#8 key, run `connect`, copy the printed account id into `BANK2SHEETS_ACCOUNTS`, create/share the spreadsheet, and sync twice (rows, then zero new rows). Existing accurate reference material is preserved and linked, not duplicated. No secrets, real ids, or personal identifiers are added. The walkthrough is BBVA-only and Enable Banking-only.

**Step 2: Add or update validation**

There is no automated test for Markdown. Validate with bounded read-only checks after editing:
- `Select-String -Path README.md -Pattern 'ENABLE_BANKING_APP_ID|ENABLE_BANKING_PRIVATE_KEY_B64|ENABLE_BANKING_REDIRECT_URL|ENABLE_BANKING_SESSION_ID|BANK2SHEETS_ACCOUNTS|GOOGLE_SERVICE_ACCOUNT_B64|GOOGLE_SPREADSHEET_ID'` -> every variable in the walkthrough matches the code's env contract exactly (no typos, no renamed vars).
- `Select-String -Path README.md -Pattern '\.\\scripts\\run\.ps1|\.\\scripts\\mvn\.ps1'` -> every command uses the shipped script paths and existing subcommands (`help|connect|fetch|sync`).
- `Select-String -Path README.md -Pattern 'pkcs8 -topk8 -nocrypt|BEGIN PRIVATE KEY|callback\.html'` -> the key-encoding and redirect guidance is present.
- `Test-Path docs\callback.html`, `Test-Path .github\workflows\sync.yml` -> every linked repository path exists.
- `git diff -- README.md | Select-String -Pattern 'BEGIN [A-Z ]*PRIVATE KEY-----|client_email\s*"\s*:\s*"|@[a-z0-9.-]+\.(com|es)'` -> no real key material, service-account JSON, or personal email leaked into the diff.
- `git diff --check` -> no trailing whitespace or CRLF issues.
- `.\scripts\mvn.ps1 test` -> 83 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS` (documentation change must not disturb the gate).

Owner: `agent` (bounded, non-interactive).

**Step 3: Write minimal implementation**

README structure to produce (keep the existing intro, `## Build`, `## Google Sheets layout`, `## License`):

1. `## Requirements` — keep Java 21 / Maven 3.9.9; add a Google account, BBVA online banking with SCA access, and (optional) OpenSSL for converting a PKCS#1 key. Note that Windows PowerShell is the documented path.
2. New `## Set up from scratch` immediately after `## Requirements`, with these ordered subsections. Keep commands copy-pasteable PowerShell and link down to reference sections.
   - **1. Fork and clone** — fork `PKCPablo/bank2sheets`, `git clone https://github.com/<your-user>/bank2sheets.git`, `cd bank2sheets`; run `.\scripts\mvn.ps1 test` to confirm the toolchain (link to [Build](#build)).
   - **2. Create the Enable Banking application** — sign in at `enablebanking.com/sign-in` (email magic link); Control Panel → API applications → Add a new application; environment **Production** (restricted mode: free, personal/non-commercial, only accounts you link are reachable); key option "Generate in the browser and export private key"; application name; allowed redirect URL (see step 3); production also asks for a description, data-protection email, privacy URL, and terms URL; Register downloads `<app-id>.pem` and shows the application id for `ENABLE_BANKING_APP_ID`. Add a short sandbox callout: sandbox uses fake bank data and the loopback redirect.
   - **3. Register a redirect URL the CLI accepts** — production: register **an HTTPS URL you control** (for example any page on a domain you own); the zero-cost option is enabling GitHub Pages on your fork (Settings → Pages → Deploy from a branch → `master` / `/docs`) so `https://<your-user>.github.io/bank2sheets/callback.html` serves `docs/callback.html`, which displays the full callback URL to paste. Explain that the page only helps copy the URL; the CLI needs the full callback URL pasted back. Sandbox: register `http://127.0.0.1:8765/bank2sheets/callback` (http is only allowed for `127.0.0.1`/`localhost`, explicit port, non-empty path). The registered value must match `ENABLE_BANKING_REDIRECT_URL` character for character.
   - **4. Base64-encode the private key** — check the file starts with `-----BEGIN PRIVATE KEY-----`; if it starts with `-----BEGIN RSA PRIVATE KEY-----`, convert with `openssl pkcs8 -topk8 -nocrypt -in eb.pem -out eb-pkcs8.pem`; then `[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\eb-pkcs8.pem'))` and paste the single-line result.
   - **5. Create `.env` in the repository root** — show a fenced block with `ENABLE_BANKING_APP_ID=...`, `ENABLE_BANKING_PRIVATE_KEY_B64=...`, `ENABLE_BANKING_REDIRECT_URL=...` and nothing else yet. State that `.env` is gitignored, `run.ps1` loads it without printing values, values must be single-line and unquoted, and base64 `=` padding is fine. Link to [Configuration](#configuration) for the full table.
   - **6. Run `connect` and capture the account id** — `.\scripts\run.ps1 connect`; open the printed URL, complete BBVA SCA; loopback redirect is captured automatically (listener bound before the URL is printed, 5-minute timeout); an https redirect prints a paste prompt — copy the full callback URL from your callback page (e.g. `docs/callback.html` served from your Pages site) and paste it. Expected output: `Connected. Connection id ... saved to bank2sheets-state.properties` and `Available accounts:` lines. Copy one id into `.env` as `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>`. Note that `connect` works with `BANK2SHEETS_ACCOUNTS` unset and that the session id is persisted locally for `fetch`/`sync`.
   - **7. Create the Google Sheets side** — Google Cloud project; enable the Google Sheets API; create a service account; create/download a JSON key; copy `client_email`; create or choose a spreadsheet, share it with that email as Editor; copy the spreadsheet id from the URL. Then base64 the JSON (`[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\key.json'))`) and add `GOOGLE_SERVICE_ACCOUNT_B64=...` and `GOOGLE_SPREADSHEET_ID=...` to `.env`.
   - **8. First sync** — `.\scripts\run.ps1 fetch` to see transactions; then `.\scripts\run.ps1 sync` twice. Expected: first run appends new rows (header written if missing), second run reports `appended 0 new row(s)` and `skipped N duplicate(s)`. Link to [Google Sheets layout](#google-sheets-layout).
   - **9. Scheduled sync (optional)** — link to [Scheduled sync](#scheduled-sync-github-actions); restate only the fork-specific facts: enable Actions/workflows in the fork (GitHub disables scheduled workflows in new forks by default), add the seven Actions secrets, copy `enablebanking.session_id` from `bank2sheets-state.properties` into `ENABLE_BANKING_SESSION_ID`, run `workflow_dispatch` once against a disposable sheet, then let the monthly cron (`0 6 1 * *`) take over; note session expiry plus the 60-day schedule deactivation.
   - **10. Troubleshooting** — compact table mapping observed messages to fixes (do not invent new messages). Include at least: `No JDK 21+ found`; `JAR not found at ... build it first`; `ENABLE_BANKING_PRIVATE_KEY_B64 is not valid base64`; `Cannot sign RS256 JWT (invalid PKCS#8 private key?)`; `connect: connection ... is not active yet`; `Timed out after 300 s waiting for the authorization callback`; `ENABLE_BANKING_REDIRECT_URL must point at 127.0.0.1 or localhost`; `Authorization state mismatch`; `no accounts configured`; `no connection; run connect once or set ENABLE_BANKING_SESSION_ID`; Google `read/append failed (HTTP 403)` -> share with `client_email`; scheduled workflow does not run -> enable Actions/workflows in the fork and add the secrets.
3. `## Run` — keep the runner description and subcommand list; replace the duplicated setup wording with a one-line pointer to `## Set up from scratch`, keeping the accurate `connect` behavior notes (loopback vs paste).
4. `## Configuration` — keep the table as the canonical variable reference and the existing Enable Banking operational notes (restricted mode, rate limits, ~180-day session expiry, callback modes). Remove only wording that duplicates the walkthrough.
5. `## Scheduled sync (GitHub Actions)` — keep the workflow description, secrets table, and caveats; state that the workflow runs in any repository that enables Actions and configures the secrets (forks must enable Actions first because GitHub disables schedules in new forks), and keep the `ENABLE_BANKING_SESSION_ID`-from-state-file explanation.
6. Keep `## Google Sheets layout` and `## License` unchanged.

Do not add screenshots, real ids, credentials, service-account JSON, or Salt Edge onboarding. Do not edit code, scripts, `.gitignore`, or `.env*` in this task (the workflow edit is Task 1).

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test` and the Step 2 `Select-String`/`Test-Path`/`git diff --check` checks.

Expected: `BUILD SUCCESS`, 83 tests with 0 failures/errors, JaCoCo 80% line gate met; every README env var name matches the code; every command uses `.\scripts\`; all linked paths exist; the diff contains no key material, service-account JSON, or personal email; `git diff --check` silent.

Owner: `agent`.

**Step 5: Record memory and commit**

1. Append a `## 2026-11-15 - README fork-to-first-sync walkthrough` entry to `docs/ai/logs/2026-11.md` recording: the added walkthrough sections, the production-first/sandbox-callout decision, the own-HTTPS-redirect requirement (Pages as the zero-cost example), the workflow guard removal (Task 1) and its fork/upstream implications, the validation results, and the issue/plan reference.
2. Update `docs/ai/README.md:9` to mention that `README.md` now contains the end-to-end fork-to-first-sync setup walkthrough (keep the paragraph structure and the rest of the status sentence intact).
3. `git add README.md docs/ai/README.md docs/ai/logs/2026-11.md docs/ai/plans/2026-11-15_125401-document-fork-to-first-sync-setup.md`
4. `git commit -m "docs: add fork-to-first-sync setup walkthrough to README (issue #11)"`

**Step 6: Reference issue #11**

Comment on issue #11 with both commit hashes, the validation results, and the final decisions (BBVA-only, production-first, own HTTPS redirect, workflow guard removed in Task 1). Use the `shared-task` helper; `gh` is not installed on this machine, so use the GitHub App token path the helper already uses for comments (a short `temp/` tsx script importing `addComment`/`apiPost`, or the same mechanism used previously for issue comments). Do not close the issue until the owner confirms the walkthrough.

Owner: `agent` for the comment; `human` for the close decision.

## Out of scope

- Changing the cron cadence, permissions, concurrency, job steps, or secret names (only the one-line guard is removed).
- Code, script, or dependency changes; new bootstrap automation.
- Adding `.env.example` (follow-up issue #13).
- Parameterizing the ASPSP (follow-up issue #14).
- Screenshots or any real credentials, account ids, spreadsheet ids, or personal identifiers.
- Other banks/ASPSPs and any Salt Edge onboarding.
- Rewriting historical AI logs, completed plans, or ADRs.

## Validation ownership

| Validation | Owner | Notes |
| --- | --- | --- |
| Workflow `Select-String` checks (no repo guard; anchors intact) and YAML review | agent | Read-only, bounded |
| `.\scripts\mvn.ps1 test` | agent | Existing gate; run in Task 2 after the docs change |
| Env-var/command/link `Select-String` and `Test-Path` checks | agent | Read-only, bounded |
| `git diff` secret/identifier scan and `git diff --check` | agent | Read-only, bounded |
| Fork behavior (fork, enable Actions, add secrets, dispatch) | human | Requires a GitHub fork and a real EB/Google setup; not runnable from this repo |
| Follow the walkthrough end-to-end with a real EB app and disposable spreadsheet | human | Requires EB/Google accounts and interactive SCA; owner already validated the underlying flow, this validates the doc |
| Read-through for tone/ordering/consolidation | human | Optional review step |

## Required gates

- `architect_review: false` - docs plus a one-line workflow guard removal; no boundary, dependency, or provider change.
- `memory_update: true` - `docs/ai/README.md` status line and `docs/ai/logs/2026-11.md` completion entry.
- Test gate: `.\scripts\mvn.ps1 test` must stay green (83 tests, JaCoCo 80% line coverage).
- Issue #11 stays open until the owner confirms; comment with both commits + validation evidence.
