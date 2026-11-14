# Shared Task Workflow

GitHub Issues is the operational source of truth for shared repository tasks.

Use `/issue create <description>` to create shared tasks.
Use `/issue` for list/claim/assign/release/close/prclose/show operations.

## Labels

Required labels: `status:to-do`, `status:doing`, `status:in-review`, `status:blocked`,
`type:task`, `type:feature`, `type:bug`, `type:docs`, `type:chore`,
`priority:high`, `priority:medium`, `priority:low`.

## Deferred / Pending

- Salt Edge V6 is a discarded option (ADR-0005); remove its fallback code and legacy config/state paths — GitHub issue #7.

