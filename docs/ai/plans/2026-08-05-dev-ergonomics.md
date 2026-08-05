---
status: completed
created: 2026-08-05
phase: 2/2
required_gates:
  test_verdict: false
  architect_review: false
  memory_update: true
---

# Plan: Dev ergonomics — scripts JDK21/runner, .editorconfig y cobertura JaCoCo 80%

## Goal

Aplicar un lote de mejoras de desarrollo —wrapper Maven con JDK 21, runner local con `.env`, `.editorconfig` y cobertura JaCoCo con umbral 80%— sin tocar código Java ni añadir dependencias runtime.

## Questions (resueltas en Discovery)

1. `run.ps1` sin jar → fallo con instrucción (`.\scripts\mvn.ps1 package`), sin auto-build.
2. Commit de memoria → commit aparte, ejecutado antes de la Fase 1 (fuera de las fases del plan).
3. JaCoCo → umbral general 80% (LINE COVEREDRATIO) que hace fallar el build si no se alcanza.

## Assumptions

- Repository root: `C:\DEV\bank2sheets`.
- Scripts PowerShell `.ps1`; line endings del repo LF (verificado); `.editorconfig` fija `lf` sin churn.
- `run.ps1` carga `.env` (KEY=VALUE, con `-split '=',2` para tolerar padding base64, ignorando vacías/comentarios), nunca imprime valores; `.env` sigue gitignored.
- JaCoCo: solo plugin de build (no entra al fat JAR); versión estable de Maven Central confirmada en delivery (0.8.15 vista a 2026-06).
- El plan V6 se persiste en memoria en el commit aparte; no se implementa aquí.
- Sin cambios en código Java; sin nuevas dependencias runtime.

## Scope Classification

- Size: Small
- Phase budget: 2
- Phase budget rationale: la Fase 1 (scripts + `.editorconfig` + docs) es un deliverable autocontenido y de riesgo de build cero; la Fase 2 (JaCoCo + umbral 80%) toca el build e incluye un gate de decisión sobre el baseline de cobertura que merece frontera de revisión propia.
- Quick delivery eligible: No — el usuario pidió un plan explícito y la Fase 2 tiene un gate de decisión (baseline).
- Split required: No (2 fases ≤ 3, sin confirmación necesaria).

## Phases

### Phase 1: Tooling ergonomics — scripts, `.editorconfig` y docs

- Scope:
  - `scripts/mvn.ps1`: usa `C:\Program Files\Java\jdk-21.0.6` cuando `JAVA_HOME` actual no sea JDK 21+ (error claro si la ruta no existe); delega al `mvn` real con los mismos argumentos (`& $mvn @args; exit $LASTEXITCODE`).
  - `scripts/run.ps1`: parámetro `-JarPath` (default `target\bank2sheets-0.1.0-SNAPSHOT.jar`); si el jar no existe → mensaje con instrucción (`.\scripts\mvn.ps1 package`) y exit ≠ 0; si existe → carga `.env` (KEY=VALUE, `-split '=',2`, sin imprimir valores) y ejecuta `java -jar`.
  - `.editorconfig` (root): `charset=utf-8`, `end_of_line=lf`, `insert_final_newline=true`, `trim_trailing_whitespace=true`; `indent 4` para `*.java` y `*.ps1`, `indent 2` para `*.xml`.
  - Docs: README (Build: `.\scripts\mvn.ps1`; Run: `.\scripts\run.ps1 <command>`) y AGENTS.md (comandos build/test con wrapper).
  - Sin cambios en `src/`.
- Acceptance criteria:
  - `.\scripts\mvn.ps1 -v` reporta Java 21.0.6 (prueba que el wrapper cambia el JDK desde el default 8).
  - `.\scripts\mvn.ps1 test` en verde con JDK 21.
  - `.\scripts\run.ps1 help` imprime usage y sale 0 (carga `.env` sin filtrar valores).
  - Branch sin jar: invocar con `-JarPath` inexistente → mensaje con instrucción y exit ≠ 0.
  - `git status` muestra solo `scripts/`, `.editorconfig`, README, AGENTS (ningún `.java` modificado).
- Risks: bajo; scripts no afectan al build ni al runtime.

### Phase 2: JaCoCo — informe + umbral 80%

- Scope:
  - `pom.xml`: plugin `org.jacoco:jacoco-maven-plugin` (última estable de Central confirmada en delivery, 0.8.15 esperada), declarado después de surefire, con:
    - `prepare-agent` → fase `initialize`
    - `report` → fase `test` (genera `target/site/jacoco/index.html`)
    - `check` → fase `test`, regla BUNDLE, `LINE/COVEREDRATIO ≥ 0.80` (el dev loop `mvn test` aplica el gate; sin umbral en branches/packages).
  - Gate de baseline (obligatorio): primero se corre `mvn test` con el informe pero sin el `check` activo; se registra el LINE COVEREDRATIO actual. Si ≥ 0.80 → se activa el check al 80%. Si < 0.80 → STOP y escalar al usuario (no se activa un check que rompe el build en silencio; opciones: umbral = baseline o añadir tests).
  - Docs: 1 línea en README y AGENTS sobre informe y gate 80%.
- Acceptance criteria:
  - `target/site/jacoco/index.html` generado por `.\scripts\mvn.ps1 test`.
  - Baseline LINE COVEREDRATIO registrado (plan/log). Si ≥ 0.80, `mvn test` verde con el check activo; si < 0.80, escalado según plan (sin build roto silencioso).
  - `.\scripts\mvn.ps1 package` en verde (shade intacto) y `java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar help` funciona (sin dependencia runtime nueva).
  - README/AGENTS actualizados con informe + gate.
- Risks: medio — si el baseline < 80% el plan se detiene y requiere decisión del usuario (mitigado por el gate de baseline). Variación menor: versión exacta del plugin.

## Validation Strategy

- Fase 1: `mvn.ps1 -v` (JDK 21), `mvn.ps1 test`, `run.ps1 help`, branch `-JarPath` inexistente, `git status`.
- Fase 2: `mvn.ps1 test` (informe + gate), `mvn.ps1 package`, `java -jar ... help`, baseline registrado.
- Revisión final con `git diff`/`git status`/`git log` tras cada fase.

## Decisions To Record

- ADR-0004 (al aprobar): gate de cobertura JaCoCo al 80% (LINE) en el dev loop; `mvn.ps1`/`run.ps1` como entrypoints canónicos de desarrollo; `.editorconfig` con LF.
- Log mensual `docs/ai/logs/2026-08.md`: entrada del lote.
- Este plan persistido en `docs/ai/plans/2026-08-05-dev-ergonomics.md`.

## Implementation Handoff

- Repository root: `C:\DEV\bank2sheets`
- Pre-fase (commit aparte, aprobado): commit de memoria independiente — ADR-0003, edits de `architecture.md`/`logs/2026-08.md`/`docs/ai/README.md` y plan V6 (untracked) — mensaje propuesto `docs(ai): record no-hexagonal decision and persist Salt Edge V6 plan`.
- Cada fase se entrega a `code`/`quickcode` con este plan como fuente de verdad; se cumplen los acceptance criteria antes de avanzar.
- La Fase 2 toca `pom.xml` pero no categorías de borde del architect (no cambia contratos externos ni auth) → sin `architect` requerido.
