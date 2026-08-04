# ADR-0001: Bootstrap AI memory for greenfield bank2sheets repository

- Status: accepted
- Date: 2026-08-04

## Context

The `bank2sheets` repository was created as an empty GitHub repository with only a placeholder `README.md` plus the OpenCode workflow bootstrap. No application code, build system, tests, or CI existed. The root `AGENTS.md` was still the generic placeholder template. Onboarding research confirmed the project purpose with the owner: a personal Open Banking → Google Sheets transaction sync application scheduled via GitHub Actions, implemented in Java, with no frontend and no database.

## Decision

- Create the `docs/ai/` memory skeleton (README router, architecture, backlog, decisions, plans, logs) to give future agents durable, routed context.
- Record the confirmed project direction in `docs/ai/architecture.md`.
- Complete the root `AGENTS.md` repository guidance sections now that purpose and stack are known.
- Defer all application-stack details (build tool, Open Banking provider, Google Sheets auth approach) until the first scaffold planning phase.

## Consequences

- Future agents can route to relevant memory instead of re-scanning an empty repository.
- `docs/ai/architecture.md` will need updates once the Java project is scaffolded and stack decisions are finalized.
- `AGENTS.md` is an ai-config-managed path; direct edits made during onboarding are recorded here and should be re-managed by ai-config on next run.
