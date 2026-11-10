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
- Real BBVA provider access — `bbva_es` is absent from the Salt Edge Service key
  catalog (see `logs/2026-10.md`, 2026-10-27). Track Salt Edge support/Provider Key
  or an alternative AIS provider in GitHub issue #3.
