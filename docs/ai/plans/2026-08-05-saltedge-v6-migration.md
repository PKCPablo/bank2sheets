---
status: planned
created: 2026-08-05
phase: 1/2
required_gates:
  test_verdict: false
  architect_review: true
  memory_update: true
---

# Plan: Migración Salt Edge API V5 → V6

## Goal

Migrar la integración de Salt Edge de la API V5 (obsoleta y no funcional) a la **API V6**: actualizar `SaltEdgeClient`, tests y documentación, y validar de punta a punta con las nuevas *service API keys* para restaurar `connect`/`sync` con BBVA.

## Questions

Resueltas durante Discovery (2026-08-05):

- Las claves actuales son **App API keys** → **no sirven en V6**; hay que crear *Service API keys* (acción del owner, gate de la Fase 2).
- La integración actual **no funciona** (V5 obsoleta/eliminada) → migración urgente.
- Alcance de validación real: indeterminado → el plan la incluye como fase owner-gated opcional.

Verificación técnica hecha durante Discovery:

- El request de V6 `/connections/connect` mantiene la misma forma: `data.customer_id` + `data.consent.scopes` + `data.attempt.return_to`.
- Scopes V6 (valores posibles: `holder_info`, `accounts`, `transactions`): `account_details` → `accounts`; `transactions_details` → `transactions` (`accounts` cubre accounts+balance).
- `status: active` en `/connections/{id}` y paginación `meta.next_id` se mantienen en V6.

## Assumptions

- Target: V6 (versión vigente, docs.saltedge.com/v6; saltedge-examples en master es V6).
- Se mantiene AIS-only de solo lectura; sin PIS.
- Headers `App-id`/`Secret` se mantienen; solo cambia el tipo de clave (Service) y su valor.
- Variables de entorno (`SALTEDGE_APP_ID`, `SALTEDGE_SECRET`, etc.) sin cambio de nombre.
- Sin dependencias nuevas; stdlib + Gson.
- Repository root: `C:\DEV\bank2sheets`.

## Scope Classification

- Size: Small
- Phase budget: 2
- Phase budget rationale: la Fase 1 es el deliverable completo de código+tests+docs (revisable de forma independiente); la Fase 2 es validación real owner-gated (depende de las service API keys + SCA BBVA) y puede requerir ajustes de normalización de payload.
- Quick delivery eligible: No (contrato externo cambiante + validación real con gate del owner; no es un quick task claro de bajo riesgo)
- Split required: No

## Phases

### Phase 1: Migración V6 — código, tests y documentación

- Scope:
  - `SaltEdgeClient`: `BASE_URL` → `https://www.saltedge.com/api/v6`; endpoint de conexión → `POST /connections/connect` (misma forma de body: `data.customer_id`, `data.consent.scopes`, `data.attempt.return_to`); scopes → `["accounts", "transactions"]`.
  - Verificar contra la API reference V6 (https://docs.saltedge.com/v6/api_reference — Connections/Connect) y saltedge-examples que no haya más campos de respuesta distintos para `/connections`, `/accounts`, `/transactions`.
  - Tests: `FakeHttp.BASE` → `/api/v6`; `SaltEdgeClientTest` (stub de `/connections/connect`, scopes); `MainCommandsTest` (stubs de connect_url); ajustes menores si aplican.
  - Docs: `README.md`, `AGENTS.md`, `docs/ai/architecture.md` → v5→v6 + nota de service API keys.
- Acceptance criteria:
  - `mvn test` en verde con `JAVA_HOME=C:\Program Files\Java\jdk-21.0.6`.
  - `connect()` hace POST a `/api/v6/connections/connect` con `consent.scopes = ["accounts", "transactions"]` y `attempt.return_to` intacto.
  - Grep en `src/`: cero referencias a `/api/v5`, `/connect_sessions/create` y scopes `account_details`/`transactions_details`.
  - README/AGENTS/architecture actualizados a V6 con nota de service API keys.
- Risks:
  - Variaciones de payload reales de V6 no visibles en docs (mitigadas por la Fase 2).
  - Riesgo bajo: cambio mecánico con tests fake-HTTP existentes.

### Phase 2: Service API keys + validación real (owner-gated)

- Scope:
  - Owner: crear **Service API key** en el dashboard de Salt Edge (página API Keys, tipo Service; public key en blanco salvo go-live) y reemplazar `SALTEDGE_APP_ID`/`SALTEDGE_SECRET`.
  - Owner: correr `connect` real (SCA BBVA) y `sync` real a la planilla.
  - Ajustar cualquier diferencia de normalización de payload que aparezca (campos de transacción, status, etc.), con sus tests.
  - Registrar en `docs/ai/logs/2026-08.md` y cerrar el plan.
- Acceptance criteria:
  - Owner confirma `connect` real: URL de conexión generada y connection `active`.
  - Owner confirma `sync` real: transacciones BBVA aparecen una sola vez en la planilla y exit code 0.
  - Los fixes de código derivados de la validación real quedan commiteados con tests.
- Risks:
  - Variaciones de BBVA/provider en V6; SCA requiere el dispositivo del owner.
  - La creación de la service API key puede requerir ajustes de cuenta/dashboard.

## Validation Strategy

- `mvn test` y `mvn package` con JDK 21 tras la Fase 1 (fake-HTTP; sin credenciales reales).
- Grep de referencias v5 residuales en `src/` y docs.
- Validación manual del owner en la Fase 2 (connect real + sync real con BBVA).

## Decisions To Record

- ADR o actualización de `architecture.md`: versión de API Salt Edge V6; contrato de *service API keys*; scopes de consentimiento `accounts`/`transactions`.
- Actualizar contrato de env en `architecture.md`/README (las claves son service keys).
- Entrada en `docs/ai/logs/2026-08.md`.

## Implementation Handoff

- Repository root: `C:\DEV\bank2sheets`
- Cada fase se entrega a `code`/`quickcode` con este plan como fuente de verdad; se cumplen los acceptance criteria antes de avanzar.
- La Fase 1 requiere `architect` en modo `review-only` antes de finalizar (toca categorías de borde: cambio de contrato público de API y cambio de auth/secretos app → service keys).
- Referencias externas: V6 Migration Guide (https://docs.saltedge.com/v6 — Migration Guide), V6 API Reference (https://docs.saltedge.com/v6/api_reference), saltedge-examples master.
