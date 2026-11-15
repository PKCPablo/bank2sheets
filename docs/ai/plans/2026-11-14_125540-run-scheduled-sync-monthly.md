---
status: completed
created: 2026-11-14
task: 1/1
required_gates:
  architect_review: false
  memory_update: true
---

# Run scheduled sync monthly on the 1st - Implementation plan

**Goal:** Change the GitHub Actions sync cadence from daily to monthly on the 1st at 06:00 UTC, keep `workflow_dispatch`, and refresh the current-state documentation and AI memory so nothing still describes the schedule as daily.

**Architecture:** Configuration-only change. `.github/workflows/sync.yml` is the only executable surface; the job, secrets, JDK/Maven steps, and `sync` invocation stay untouched. Correctness is preserved because `EnableBankingClient.fetch` requests all `BOOK` transactions per account without a date window and `Main.sync()` dedupes against the `TransactionId` column, so a monthly run only delays appends until the next run instead of losing them.

**Tech Stack:** GitHub Actions YAML (cron + `workflow_dispatch`), Markdown docs, Maven/JaCoCo verification gate (no Java change).

**Scope classification:** `single-task`

**Decisions (resolved 2026-11-14):**

1. **Cadence:** strictly monthly, `- cron: '0 6 1 * *'`. No mid-month safety-net run (owner selected the option recommended in issue #9).
2. **Time of day:** keep 06:00 UTC on day 1 (smallest diff, consistent with the current schedule).
3. **Historical records:** existing log entries (`docs/ai/logs/2026-11.md` line 54) and completed plans keep their original "daily" wording because they record what was true at the time; only current-state docs are refreshed and a new dated log entry is appended.

## Tasks

### Task 1: Switch the schedule to monthly and refresh the daily-schedule docs

**Objective:** Replace the daily cron with a monthly-on-the-1st cron and align every current-state doc plus the AI log with the new cadence.

**Files:**
- Modify: `.github/workflows/sync.yml:5-7`
- Modify: `README.md:87-89`, `README.md:107`
- Modify: `AGENTS.md:51`
- Modify: `docs/ai/architecture.md:5`, `docs/ai/architecture.md:15`
- Modify: `docs/ai/README.md:9`
- Modify: `docs/ai/logs/2026-11.md` (append a new section; leave line 54 unchanged)
- Validate: `.\scripts\mvn.ps1 test`, `Select-String` checks below, `git diff --check`

**Step 1: Establish expected behaviour**

The scheduled workflow runs once a month on day 1 at 06:00 UTC (`0 6 1 * *`) and still supports manual `workflow_dispatch`. A monthly run does not lose transactions: `EnableBankingClient.fetch` pages all `BOOK` transactions with no `date_from`/`date_to`, and `Main.sync()` skips ids already in the `TransactionId` column. The only consequence is up to ~1 month of lag for late-month transactions, and a silently disabled schedule (issue #6, the public-repo 60-day inactivity rule) would take up to a month to notice.

**Step 2: Add or update validation**

No unit test covers workflow YAML. Validate with read-only checks:
- `Select-String -Path .github/workflows/sync.yml -Pattern "cron"` → the only cron line is `- cron: '0 6 1 * *'`, with a "Monthly" comment; `workflow_dispatch:` still present.
- `Select-String -Path README.md,AGENTS.md,docs/ai/architecture.md,docs/ai/README.md -Pattern "(?i)daily"` → no matches.
- `Select-String -Path README.md,AGENTS.md,docs/ai/architecture.md,docs/ai/README.md -Pattern "0 6 1 \* \*"` → each file states the monthly-on-the-1st cadence.
- `git diff --check` → clean (no trailing whitespace/CRLF issues).

Owner: `agent` (bounded, non-interactive).

**Step 3: Write minimal implementation**

1. `.github/workflows/sync.yml`: change the comment to `# Monthly on the 1st at 06:00 UTC` and the trigger to `- cron: '0 6 1 * *'`. Do not touch `workflow_dispatch`, permissions, concurrency, job steps, or secrets.
2. `README.md:89`: change "runs `sync` daily at 06:00 UTC" to "runs `sync` monthly on the 1st at 06:00 UTC" (keep the `workflow_dispatch` and ephemeral-runner/`ENABLE_BANKING_SESSION_ID` sentence).
3. `README.md:107`: keep the 60-day deactivation warning and append that with a monthly cadence a silently disabled workflow can go unnoticed for up to a month, referencing issue #6.
4. `AGENTS.md:51`: change "daily 06:00 UTC" to "monthly on the 1st, 06:00 UTC".
5. `docs/ai/architecture.md:5` and `:15`: change the `daily 06:00 UTC` schedule wording to `monthly on the 1st at 06:00 UTC`; leave the verification note (`issue #4 closed`) and the rest of the paragraph intact.
6. `docs/ai/README.md:9`: change the Operational repo sentence "daily 06:00 UTC" to "monthly on the 1st at 06:00 UTC".
7. `docs/ai/logs/2026-11.md`: append a new `## 2026-11-14 — Scheduled sync moved to monthly` section recording: the cron change, that `workflow_dispatch` is unchanged, why monthly is safe (`EnableBankingClient.fetch` has no date window; `Main.sync()` dedupes by `TransactionId`), that issue #6 (60-day deactivation) remains relevant and a monthly cadence lengthens detection lag, the validation results, and the issue/plan reference. Do not rewrite the historical 2026-11-14 scheduled-sync entry.

Do not edit `docs/ai/plans/**`, ADRs, Java sources, or `.gitignore`.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test` (also run the Step 2 `Select-String` checks and `git diff --check`).
Expected: `BUILD SUCCESS`, 83 tests with 0 failures/errors, JaCoCo 80% LINE gate met; cron check shows `'0 6 1 * *'` with `workflow_dispatch` intact; no "daily" matches in the four current-state docs; `git diff --check` silent.

Owner: `agent`.

**Step 5: Commit**

`git add .github/workflows/sync.yml README.md AGENTS.md docs/ai/architecture.md docs/ai/README.md docs/ai/logs/2026-11.md`
`git commit -m "chore: run scheduled sync monthly on the 1st (issue #9)"`

**Step 6: Reference issue #6**

Comment on issue #9 (or state in the PR body, whichever route the delivery agent uses): the cadence is now monthly and issue #6 remains relevant because a silently disabled schedule can now go unnoticed for up to a month. Command: `gh issue comment 9 --body "..."`.
Expected: command prints the comment URL; issue #6 stays open and untouched.

Owner: `agent`.

## Out of scope

- Solving the 60-day scheduled-workflow deactivation problem (issue #6).
- Adding a mid-month safety-net run.
- Changing `workflow_dispatch`, job steps, permissions, concurrency, secrets, or any Java code.
- Changing provider fetch windows or dedupe logic.
- Rewriting historical log entries or completed plans that mention the old daily cadence.

## Validation ownership

| Validation | Owner | Notes |
| --- | --- | --- |
| `.\scripts\mvn.ps1 test` | agent | Existing gate; no production code change expected |
| Cron/doc `Select-String` checks and `git diff --check` | agent | Read-only, bounded |
| `gh issue comment 9` reference to #6 | agent | Via `gh`; needs repo auth |
| Inspect rendered workflow/docs and confirm intent | human | Optional final read-through during review |

## Required gates

- `architect_review: false` - workflow cadence and docs only; no architectural boundary or dependency change.
- `memory_update: true` - `docs/ai/architecture.md`, `docs/ai/README.md`, and `docs/ai/logs/2026-11.md` are updated; `AGENTS.md`/`README.md` too.
- Test gate: `.\scripts\mvn.ps1 test` must stay green (JaCoCo 80% line coverage).

## Outcome (2026-11-15)

- Completed 1/1. `311f074` (`chore: run scheduled sync monthly on the 1st (issue #9)`) applied all file changes; `d6b61e0` corrected the appended log entry's test count from the stale 104 expectation to the actual 83.
- Actual validation: `.\scripts\mvn.ps1 test` -> 83 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`; only cron line is `'0 6 1 * *'` with `workflow_dispatch` intact; no "daily" matches in the four current-state docs; `git diff --check` clean.
- Step 6 completed: issue #9 comment posted (`gh` unavailable, so via the GitHub App token used by the `shared-task` helper); issue #6 left open and untouched.
