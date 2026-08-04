# AGENTS.md

## Mission

Use the installed OpenCode workflows to onboard repositories and deliver minimal, reviewed, and documented changes. Staged delivery is the default conservative path; quick delivery handles clear, small, low-risk tasks via `/quick` or proactive planner suggestion.

## AI Memory Index

This file is the practical entrypoint for AI agents working in this repository. For durable memory (architecture, decisions, approved plans, and logs), see `docs/ai/README.md`.

Treat `docs/ai/README.md` as a tiny router. Do not load all memory files by default; use `/memory` (planner) or `memory-maintenance` for low-token lookup/freshness/update routing.

## Repository Guidance

The sections below contain repository-specific guidance. `/onboard-repo` and `research` fill these values during onboarding.

### Project Overview

bank2sheets is a personal application that uses Open Banking to fetch transactions from one or more bank accounts and sync them to a Google Sheets spreadsheet. It runs periodically via GitHub Actions, never stores bank credentials, and is designed to stay simple and extensible (future: automatic categorization, statistics, exports). Planned stack: Java, no frontend, no database. See `README.md` and `docs/ai/architecture.md`.

### Build/Test Commands

Not yet defined — no application scaffold exists. They will be added here once the Java project is scaffolded; see `docs/ai/plans/` for the first implementation plan.

### Key Conventions & Constraints

- Never store or commit bank credentials or long-lived tokens; all sensitive values must live in GitHub Actions secrets at runtime.
- Keep the architecture minimal: no frontend and no database unless a concrete requirement forces one; avoid speculative abstraction (ponytail reuse-first discipline).
- Use staged delivery for implementation; `/quick` only for small, low-risk changes.
- Keep `docs/ai/` memory routed and current; `planner` is the memory owner.

## Workflow & Engineering Rules

The `planner` (planning) and `delivery` (implementation pipeline) agents are the two primary entry gates. Planner owns discovery, phased plan creation, memory, and shared tasks. Delivery owns phase handoff, code/quickcode invocation, git-based review, and close workflow. See the installed planner and delivery agents for the canonical rulesets.

When pasting an error or log (stack trace, crash dump, exception), prefix with `/triage` so the triage skill loads before any investigation.

Planner enforces a ponytail reuse-first discipline. Use `/ponytail-help` in an installed repo to see available commands and the 7-rung ladder. To disable: `/ponytail off`.
