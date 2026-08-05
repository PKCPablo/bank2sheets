# ADR-0004: Dev ergonomics — JDK21 Maven wrapper, `.env` runner, `.editorconfig`, JaCoCo 80% line-coverage gate

- Status: accepted
- Date: 2026-08-05

## Context

The default `JAVA_HOME` on the development machine points to JDK 8, so every Maven command required a per-command `JAVA_HOME` override (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.6'; mvn test`). Running the fat JAR required a separate `java -jar` invocation and manually exporting environment variables from the local `.env` file. The repository also lacked editor-convention configuration and had no automated coverage signal in the dev loop.

## Decision

Adopt `scripts/mvn.ps1` and `scripts/run.ps1` as the canonical development entrypoints, add a root `.editorconfig`, and enforce a coverage gate:

- `scripts/mvn.ps1`: switches to `C:\Program Files\Java\jdk-21.0.6` when the active `JAVA_HOME` is not JDK 21+ (clear error if the path is missing), then delegates to the real `mvn` with the same arguments.
- `scripts/run.ps1`: loads the repo-root `.env` (KEY=VALUE, `-split '=',2` to tolerate base64 padding, blanks/comments ignored, values never printed) and runs `java -jar` against the fat JAR (default `target\bank2sheets-0.1.0-SNAPSHOT.jar`, overridable via `-JarPath`); if the JAR is missing it prints the build instruction and exits non-zero.
- `.editorconfig` (repo root): `charset=utf-8`, `end_of_line=lf`, `insert_final_newline=true`, `trim_trailing_whitespace=true`; indent 4 for `*.java`/`*.ps1`, indent 2 for `*.xml`.
- JaCoCo (`org.jacoco:jacoco-maven-plugin:0.8.15`, build-time only, never in the fat JAR): `prepare-agent` at `initialize`, `report` at `test` (writes `target/site/jacoco/index.html`), and `check` at `test` with a BUNDLE rule `LINE/COVEREDRATIO ≥ 0.80`. The gate runs on the dev loop (`mvn test`); no branch/package thresholds.

## Consequences

- All Maven commands can now be run as `.\scripts\mvn.ps1 test|package` without JDK juggling; the fat JAR runs as `.\scripts\run.ps1 <command>` with `.env` loaded automatically.
- `mvn test` now fails the build if line coverage drops below 80%. Baseline at adoption: 86.0% LINE COVEREDRATIO (332/386 lines), so there is ~6% headroom before new code threatens the gate.
- `.editorconfig` establishes LF as the repo convention going forward (some existing files such as `AGENTS.md` are still committed CRLF; normalizing them is a separate, explicit change).
- Future coverage regressions surface in the dev loop rather than CI.
