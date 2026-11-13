# Shared Task Workflow

GitHub Issues is the operational source of truth for shared repository tasks.

Use `/issue create <description>` to create shared tasks.
Use `/issue` for list/claim/assign/release/close/prclose/show operations.

## Labels

Required labels: `status:to-do`, `status:doing`, `status:in-review`, `status:blocked`,
`type:task`, `type:feature`, `type:bug`, `type:docs`, `type:chore`,
`priority:high`, `priority:medium`, `priority:low`.

## Deferred / Pending

- GitHub Actions cron workflow for scheduled sync — deferred from MVP plan
  `plans/2026-08-04-mvp-pipeline-local.md`; tracked in GitHub issue #4.
- Real BBVA provider access — Salt Edge dropped on 2026-11-13 (no paid Live contract; Test is
  time-boxed to 90 days); Enable Banking restricted production chosen (ADR-0005) and the adapter
  is implemented (`plans/2026-11-13_171738-enable-banking-adapter.md`, Tasks 1-2). Sandbox
  validation passed 2026-11-13 (`connect`/`fetch`; `sync` twice → 17 then 0 rows). Remaining
  production validation — create the production app, link the real BBVA account, run
  `connect`/`fetch` and `sync` twice against the disposable test spreadsheet; tracked in GitHub
  issue #3.
