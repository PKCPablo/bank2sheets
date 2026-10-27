---
status: completed
created: 2026-10-27
phase: 1/1
required_gates:
  test_verdict: true
  architect_review: false
  memory_update: true
---

# Plan: Fix Maven wrapper JDK discovery and stale JDK-path docs

## Goal

Make `.\scripts\mvn.ps1` work on this machine without a manual `JAVA_HOME` override by discovering an installed JDK 21+ under `C:\Program Files\Java`, and remove stale `jdk-21.0.6` guidance from the active README, AGENTS, and `docs/ai` memory. No product behavior changes.

## Assumptions

- Repository root: `C:\DEV\bank2sheets`.
- The wrapper must keep delegating to the real `mvn` with the same arguments and preserve the existing JDK-21+ check.
- This machine has Temurin 21.0.12 at `C:\Program Files\Java\jdk-21`; `C:\Program Files\Java\jdk-21.0.6` is absent.
- `C:\Program Files\Java` is the standard JDK root on this machine. Discovery may use `jdk-21*` directories and pick the highest name.
- No commit unless the user explicitly asks.

## Scope Classification

- Size: Small
- Phase budget: 1
- Phase budget rationale: wrapper change plus documentation consistency; no Java, build, or external-contract changes.
- Quick delivery eligible: Yes, but this plan is persisted so `delivery` can review the change with a test verdict.

## Phase 1: Wrapper discovery and documentation refresh

- Scope:
  - `scripts/mvn.ps1`: replace the hardcoded `C:\Program Files\Java\jdk-21.0.6` with discovery of a JDK 21+ installation. Keep using an active JDK 21+ `JAVA_HOME` when present. When it is not 21+, find the highest `jdk-21*` directory under `C:\Program Files\Java` and set `JAVA_HOME` to it. If none exists, fail with a clear error. Handle an empty `JAVA_HOME` without throwing a path error.
  - `README.md`: update Requirements/Build/direct-Maven examples to the actual JDK 21 path (`C:\Program Files\Java\jdk-21`) and state that the wrapper auto-detects JDK 21+.
  - `AGENTS.md`: update Setup & build and Testing to use the plain wrapper commands; remove the "not fixed yet" workaround. Keep the final `## Repository boundary` and `## Working with wayforge` sections byte-for-byte verbatim.
  - `docs/ai/architecture.md`: update the local build note to describe the wrapper discovery behavior and remove the "do not edit the wrapper" caveat.
  - `docs/ai/README.md`: remove the stale wrapper-path caveat from the status line.
  - `docs/ai/logs/2026-10.md`: append a concise entry for the wrapper fix and its validation.
  - `docs/ai/decisions/ADR-0004-dev-ergonomics-jdk21-wrapper-editorconfig-jacoco-gate.md`: append an implementation note recording the switch from a hardcoded path to JDK 21+ discovery.
  - `docs/ai/decisions/ADR-0001-initial-ai-memory.md`: append a short follow-up note that the wrapper stale-path issue was fixed on 2026-10-27.
- Acceptance criteria:
  - `.\scripts\mvn.ps1 -version` reports Java 21 without setting `JAVA_HOME` first.
  - `.\scripts\mvn.ps1 test` passes: 44 tests, JaCoCo 80% line gate met, BUILD SUCCESS.
  - No stale `jdk-21.0.6` reference remains in `README.md`, `AGENTS.md`, `docs/ai/architecture.md`, `docs/ai/README.md`, `scripts/mvn.ps1`, or the new log entry. Historical 2026-08 logs/plans and ADR context may retain the old path.
  - `AGENTS.md` retains its final two sections verbatim.
  - `git diff --check` is clean.
- Risks: low; PowerShell wrapper only. The discovery must not override an already-valid JDK 21+ `JAVA_HOME`.

## Validation Strategy

1. Run `.\scripts\mvn.ps1 -version` with the machine's default `JAVA_HOME` (JDK 8) and confirm Java 21 is used.
2. Run `.\scripts\mvn.ps1 test` and confirm 44 tests pass and the JaCoCo gate is met.
3. Search the active docs for `jdk-21.0.6`; only historical memory may match.
4. Review `git diff`, `git status`, and `git diff --check`.

## Implementation Handoff

- Repository root: `C:\DEV\bank2sheets`.
- Preserve the user's unrelated uncommitted changes to `opencode.jsonc` and `.wayforge-managed`.
- Do not commit; the user has not requested a commit.
- No Java source, `pom.xml`, or runtime dependency changes.
