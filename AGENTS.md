# AGENTS.md

## Project overview

bank2sheets is a personal Java 21 CLI that fetches BBVA transactions through the Salt Edge V6 Open Banking API and idempotently appends them to a Google Sheets spreadsheet. It runs on demand (`connect` / `fetch` / `sync`), keeps credentials out of the repository, and has no frontend or database. Start with `README.md` for usage and `docs/ai/README.md` for routed durable memory; most agent work touches `src/main/java/bank2sheets/` (Salt Edge client, Sheets client, config, CLI wiring) and its mirrored tests.

## Project structure

- `src/main/java/bank2sheets/` — `Main` CLI plus `config/` (env config, runtime state), `http/` (HTTP seam), `model/` (`Account`, `Transaction`), `openbanking/` (`TransactionSource` + `saltedge/SaltEdgeClient`), `sheets/` (`GoogleSheetsClient`, `SheetRow`).
- `src/test/java/` — mirrors the main packages; fake HTTP backends plus unit and CLI tests.
- `scripts/` — `mvn.ps1` (JDK 21 Maven wrapper) and `run.ps1` (loads the repo-root `.env`, runs the fat JAR).
- `docs/ai/` — routed AI memory: `README.md` router, `architecture.md`, `decisions/`, `plans/`, `backlog/`, `logs/`.
- `pom.xml`, `.editorconfig` — build and editor conventions.

## Setup & build

- Prerequisites: Java 21 (JDK) and Maven 3.9.9.
- `scripts/mvn.ps1` auto-detects a JDK 21+: it keeps an active JDK 21+ `JAVA_HOME` and otherwise selects the highest `jdk-21*` under `C:\Program Files\Java` (here Temurin 21.0.12 at `C:\Program Files\Java\jdk-21`). Build with:

  ```powershell
  .\scripts\mvn.ps1 test
  .\scripts\mvn.ps1 package
  ```

  The fat JAR is at `target\bank2sheets-0.1.0-SNAPSHOT.jar`.
- Run the built JAR via `.\scripts\run.ps1 help|connect|fetch|sync`; it loads `.env` from the repo root without printing values. Runtime config is env-only (see `README.md`).

## Testing

- Full suite: `.\scripts\mvn.ps1 test` — 44 JUnit 5 tests, JaCoCo report at `target/site/jacoco/index.html`, and an 80% line-coverage gate that fails the build below 80%.
- Single test: `.\scripts\mvn.ps1 test '-Dtest=SaltEdgeClientTest'` or `'-Dtest=SaltEdgeClientTest#methodName'` (Maven Surefire).
- No lint or typecheck command is configured; `.editorconfig` is the only style enforcement.

## Code style

- Follow `.editorconfig`: UTF-8, LF, final newline, no trailing whitespace; 4-space indent for Java/PowerShell, 2 for XML.
- No formatter or linter plugin (no Spotless/Checkstyle). Match neighboring code and keep Javadoc on non-obvious contracts.
- Runtime dependency: Gson 2.11.0 only; tests use JUnit 5. Prefer JDK stdlib HTTP/JWT helpers over new libraries.

## Git workflow

- Branch: `master`; remote `https://github.com/PKCPablo/bank2sheets.git`.
- Commit messages may use conventional prefixes (`feat:`, `fix:`, `docs:`, `chore:`, `build:`) or plain text; there is no required commit-message format.
- One-developer repository: commit directly to `master`; no PR, review, branch-protection, or merge-method convention is required; no CI currently.
- Do not commit unless explicitly asked.

## Boundaries

- Never commit `.env`, credentials, service-account keys, or `bank2sheets-state.properties` (it holds the Salt Edge connection id).
- Do not add runtime dependencies beyond Gson without asking; no frontend, database, or formal architecture layer.
- Do not assume scheduled CI/cron exists; execution is on-demand local and cron is deferred in `docs/ai/backlog/README.md`.
- Keep changes within the requested scope; record durable decisions in `docs/ai/` via the planner.

## Repository boundary

Stay inside the repository root (`git rev-parse --show-toplevel` when in a git repo, otherwise the working directory). Don't read or write outside it unless the user approves.

## Working with wayforge

This repository uses the wayforge workflow. Formalize and plan work with `/intent` then `/plan` (the `planner` agent). Track shared tasks with `/issue`. Implement approved plans with the `delivery` agent (`/load-plan`, `/implement-task`, `/review-task`). Load context progressively: start from this file and `README.md`, then route through `docs/ai/README.md` and load only what the task needs.
