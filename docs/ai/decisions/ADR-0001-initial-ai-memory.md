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

## Refresh note (2026-10-27)

- Full wayforge onboarding refresh. `docs/ai/` was already complete, so `ai-memory init` was not rerun and the memory structure/ADRs were left in place.
- `docs/ai/architecture.md` refreshed: execution is on-demand local in v1 with GitHub Actions cron deferred to the backlog; the local build note was corrected to this machine's Temurin 21.0.12 at `C:\Program Files\Java\jdk-21` and the working `JAVA_HOME` override, while `scripts/mvn.ps1` still hardcodes the stale `jdk-21.0.6` path. A concise invariants section was added.
- Root `AGENTS.md` rebuilt from the current wayforge template with project-specific overview, structure, build/test, style, git, and boundary guidance; the template's repository-boundary and wayforge sections were preserved.
- Onboarding re-validated: 44 tests pass, JaCoCo 80% line gate met, `BUILD SUCCESS` (with the local `JAVA_HOME` workaround). Status remains accepted.

## Follow-up note (2026-10-27)

- The stale `scripts/mvn.ps1` JDK path issue noted in the refresh above was fixed on 2026-10-27: the wrapper now keeps an active JDK 21+ `JAVA_HOME` or auto-detects the highest `jdk-21*` under `C:\Program Files\Java` instead of hardcoding a path.
