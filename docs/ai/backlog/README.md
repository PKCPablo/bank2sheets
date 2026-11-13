# Shared Task Workflow

GitHub Issues is the operational source of truth for shared repository tasks.

Use `/issue create <description>` to create shared tasks.
Use `/issue` for list/claim/assign/release/close/prclose/show operations.

## Labels

Required labels: `status:to-do`, `status:doing`, `status:in-review`, `status:blocked`,
`type:task`, `type:feature`, `type:bug`, `type:docs`, `type:chore`,
`priority:high`, `priority:medium`, `priority:low`.

## Deferred / Pending

- ~~GitHub Actions cron workflow for scheduled sync~~ — implemented as `.github/workflows/sync.yml` (daily 06:00 UTC + `workflow_dispatch`); verification pending the owner adding the Actions secrets and running `workflow_dispatch` (GitHub issue #4).

