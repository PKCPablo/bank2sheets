---
status: in-progress
created: 2026-11-13
task: 2/3
required_gates:
  architect_review: true
  memory_update: true
---

# Enable Banking AIS adapter implementation plan

**Goal:** Replace the Salt Edge-only runtime path with a provider-neutral factory and a working Enable Banking AIS adapter in free restricted-production mode (personal use, owner-linked accounts), keeping the `TransactionSource` seam and the Google Sheets pipeline unchanged.

**Architecture:** `EnableBankingClient` implements the existing five-method `TransactionSource` interface and talks to `https://api.enablebanking.com` through the shared `HttpExchange` seam. A provider-selection factory keyed by `BANK2SHEETS_ACCOUNTS` chooses the source; `AppConfig`/`AccountConfig`/`RuntimeState` gain provider-scoped credentials and state, and `Main` stops referencing Salt Edge directly. JWT signing is extracted from `GoogleSheetsClient` into one small JDK-only helper reused by both clients. No new runtime dependency.

**Tech Stack:** Java 21, JDK `HttpClient`/`Signature`/`KeyFactory`, Gson 2.11.0, JUnit 5, JaCoCo 80% line gate. Loopback callback capture uses only JDK classes (`com.sun.net.httpserver` or a raw `ServerSocket`; see Blocking decisions).

**Scope classification:** `multi-task`

## Context (verified repository facts)

- `TransactionSource` is the only bank-provider boundary: `connect()`, `findConnectionId()`, `isActive(String)`, `listAccounts()`, `fetchTransactions(String)` (`src/main/java/bank2sheets/openbanking/TransactionSource.java`).
- `SaltEdgeClient` implements it against Salt Edge V6 through `HttpExchange`; tests inject `FakeHttp` (`src/test/java/bank2sheets/openbanking/saltedge/FakeHttp.java`), which already supports a custom base URL.
- `Main.run` hardcodes `SaltEdgeClient::create` (`Main.java:83`). `connect` checks `cfg.saltEdgeAppId()`/`saltEdgeSecret()` (`Main.java:115`), `requireFetchConfig` repeats the check and reads `saltEdgeConnectionId` (`Main.java:186-194`), and `withConnectionId` falls back to `RuntimeState.connectionId()` (`Main.java:171-177`). USAGE text hardcodes Salt Edge variable names.
- `AppConfig` reads four `SALTEDGE_*` variables; `parseAccounts` rejects every provider except `saltedge` (`AppConfig.java:62-64`). `AccountConfig` documents the same and carries a `ponytail:` note that `provider` is never read.
- `RuntimeState` persists exactly one key, `saltedge.connection_id` (`RuntimeState.java:17`).
- `GoogleSheetsClient` already signs RS256 JWTs with the JDK: private `sign(byte[], String)` (PKCS#8 PEM) and `base64Url(byte[])` (`GoogleSheetsClient.java:192-208,239-241`). This is the code to reuse, not duplicate.
- Tests: 104 JUnit 5 tests after Tasks 1-2, the first-run connect fix, and the HTTPS paste fallback (44 at planning time), `.\scripts\mvn.ps1 test`, JaCoCo 80% line gate. `MainCommandsTest` drives `connect`/`fetch`/`sync` end to end with `FakeHttp` + `FakeSheetsHttp`; `AppConfigTest` and `RuntimeStateTest` cover config/state.
- ADR-0005 (accepted 2026-11-13) selects Enable Banking restricted production and rejects Salt Edge for BBVA (Live = paid legal-entity contract; Test = 90 days). Its recorded risks: only owner-linked accounts, ASPSP rate limits, consent/session renewal, no vested right to the free tier, personal/non-commercial only, RSA key is a new secret, Salt Edge code lingers until removed, and the Test fallback needs a public callback URL.
- Enable Banking API facts verified 2026-11-13 (`https://enablebanking.com/docs/api/reference/`, `https://enablebanking.com/docs/faq/`, `https://enablebanking.com/docs/api/linked-accounts/`, `https://enablebanking.com/terms/`):
  - Base URL `https://api.enablebanking.com`; every request carries `Authorization: Bearer <JWT>`.
  - JWT: `RS256`; header `typ=JWT`, `alg=RS256`, `kid=<application id>`; claims `iss=enablebanking.com`, `aud=api.enablebanking.com`, `iat`, `exp` (max TTL 86400 s).
  - Flow: `GET /aspsps` (find BBVA, country ES, personal; read `maximum_consent_validity`) → `POST /auth` (returns `url` + `authorization_id`) → browser SCA → redirect with `code` + `state` → `POST /sessions` (`{"code": ...}`) returns `session_id` + accounts → `GET /sessions/{id}` → `GET /accounts/{uuid}/details` → `GET /accounts/{uuid}/transactions` with `continuation_key` paging.
  - `entry_reference` is not guaranteed present or unique; `status` uses `BOOK`/`PDNG`; amounts carry `credit_debit_indicator` (`CRDT`/`DBIT`); descriptions live in `remittance_information`, creditor/debtor names, and `note`.
  - Restricted production needs no contract/KYB; apps are activated by whitelisting/linking the owner's own accounts.

## Blocking decisions and gates

1. **Authorization-code capture (resolved 2026-11-13).** Sandbox keeps the `http://127.0.0.1:8765/bank2sheets/callback` loopback listener (bound before the URL; `code`/`state` captured locally). Enable Banking production apps reject `http` redirects, so production uses the static GitHub Pages callback `https://pkcpablo.github.io/bank2sheets/callback.html` (served from `/docs`) plus `PasteAuthorizationCodeReceiver`, which prompts the owner to paste the full callback URL. The owner enables Pages and registers that URL in the production app.
2. **Private-key format and secret transport (blocking, recommend PKCS#8 base64).** Use `ENABLE_BANKING_PRIVATE_KEY_B64` holding a base64 PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`), mirroring `GOOGLE_SERVICE_ACCOUNT_B64`. If the owner's key is PKCS#1 (`BEGIN RSA PRIVATE KEY`), document the `openssl pkcs8 -topk8 -nocrypt` conversion rather than adding a parser.
3. **Salt Edge retention (recommend keep selectable).** Keep `saltedge` accepted by the factory for one release as a fallback; removing `SaltEdgeClient` and its tests is a separate cleanup once Enable Banking has been validated. Reject this if the owner prefers deletion now.
4. **Transaction id prefix (recommend `eb:`).** Prefix every Enable Banking `Transaction.id` with `eb:` so it cannot collide with existing Salt Edge numeric ids in the same sheet. The Sheets dedupe key remains the TransactionId column.
5. **One provider per invocation (recommend fail fast).** All `BANK2SHEETS_ACCOUNTS` entries must share the same provider; mixed providers fail with a clear message. Supporting mixed providers in one run is out of scope.
6. **Gates:** `architect_review: true` for the provider factory/boundary and the `TransactionSource` fit; `memory_update: true` for `architecture.md`, `README.md`/`AGENTS.md`, ADR-0005 link, backlog, and the monthly log.

## Task 1: Shared RS256 JWT signer and Enable Banking client

**Objective:** Extract the existing RS256 JWT code and implement an `EnableBankingClient` that satisfies `TransactionSource` for the restricted-production flow, covered by fake-HTTP tests. No `Main` wiring yet.

**Files:**
- Create: `src/main/java/bank2sheets/http/Rs256Jwt.java`
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Create: `src/main/java/bank2sheets/config/EnableBankingConfig.java`
- Create: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java`
- Create: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingException.java`
- Create: `src/main/java/bank2sheets/openbanking/enablebanking/AuthorizationCodeReceiver.java`
- Create: `src/main/java/bank2sheets/openbanking/enablebanking/LoopbackAuthorizationCodeReceiver.java`
- Create: `src/main/java/bank2sheets/openbanking/enablebanking/PasteAuthorizationCodeReceiver.java`
- Create: `docs/callback.html`
- Create: `src/test/java/bank2sheets/http/Rs256JwtTest.java`
- Create: `src/test/java/bank2sheets/openbanking/enablebanking/EnableBankingClientTest.java`
- Create: `src/test/java/bank2sheets/openbanking/enablebanking/LoopbackAuthorizationCodeReceiverTest.java`
- Create: `src/test/java/bank2sheets/openbanking/enablebanking/PasteAuthorizationCodeReceiverTest.java`
- Validate: the files above; `.\scripts\mvn.ps1 test`

**Step 1: Establish expected behaviour**

- `Rs256Jwt.encode(header, claims, pkcs8Pem)` produces `base64url(header).base64url(claims).base64url(signature)` with `SHA256withRSA`, raw base64url without padding; `GoogleSheetsClient` keeps its current JWT request/response behaviour using it.
- `EnableBankingConfig` exposes `appId`, `privateKeyB64` (PKCS#8 PEM), and `redirectUrl`; a blank field fails fast with a message naming the missing `ENABLE_BANKING_*` variable. The redirect accepts `http` loopback (explicit port/path) or `https` (non-blank host/path).
- Redirect mode: `http` loopback uses `LoopbackAuthorizationCodeReceiver`; `https` (production) uses `PasteAuthorizationCodeReceiver` and the Pages callback page `docs/callback.html`, which displays the `code`/`state` for the owner to paste.
- `connect()`: `GET /aspsps?country=ES&psu_type=personal&service=AIS`, locate BBVA by name, read `maximum_consent_validity` (fallback 90 days if absent), generate a UUID `state`, `POST /auth` with `access.valid_until`, `aspsp {name: "BBVA", country: "ES"}`, `redirect_url`, `state`, `psu_type: "personal"`, and return the response `url`.
- `findConnectionId()`: receive the callback URL (or code), reject a `state` mismatch, `POST /sessions` with `{"code": ...}`, and return `session_id`.
- `isActive(id)`: `GET /sessions/{id}`; true only when `status == "AUTHORIZED"`.
- `listAccounts()`: `GET /sessions/{sessionId}` to list account UUIDs, then `GET /accounts/{uuid}/details` for the display name; fall back to `product`/empty rather than failing the whole run for one missing name.
- `fetchTransactions(uuid)`: `GET /accounts/{uuid}/transactions?transaction_status=BOOK`, follow `continuation_key` until absent, and map:
  - `id`: `eb:` + `entry_reference` when non-blank, else `eb:` + `transaction_id`, else `eb:sha256:<hex>` over the deterministic fields below.
  - `amount`: `transaction_amount.amount` as `BigDecimal`, negated when `credit_debit_indicator == "DBIT"`.
  - `currency`: `transaction_amount.currency`.
  - `description`: `remittance_information` joined with spaces; else creditor/debtor name by direction; else `note`; else empty.
  - `madeOn`: `booking_date`, else `value_date`, else `transaction_date`; a transaction with none fails with a message including its id.
- Non-2xx responses throw `EnableBankingException` with status, method, path, and body.
- The loopback receiver binds `127.0.0.1` on the redirect URL's port/path, returns a small HTML confirmation, and times out (default 5 minutes) with a clear error.

**Step 2: Add or update validation**

- `Rs256JwtTest`: generate an RSA key pair in the test, encode claims, verify the signature with the public key, assert raw base64url (no `=` padding), and assert the header keeps `kid`/`alg`/`typ`.
- `EnableBankingClientTest` using `FakeHttp("https://api.enablebanking.com")`:
  - `connect` sends a Bearer JWT with the expected `kid`/claims, selects BBVA ES, sends the expected body, and returns the auth URL.
  - `findConnectionId` rejects a mismatched `state` and exchanges a valid code for `session_id`.
  - `isActive` is true for `AUTHORIZED` and false otherwise.
  - `listAccounts` maps UUID + details name and tolerates a details failure.
  - `fetchTransactions` paginates via `continuation_key`, flips the sign on `DBIT`, joins remittance lines, falls back to `note`, prefers `booking_date`, and builds `eb:` ids both from `entry_reference` and from the hash fallback.
  - A 401/500 response throws `EnableBankingException` with the status.
- `LoopbackAuthorizationCodeReceiverTest`: bind on an ephemeral port (`0`), perform a request to `http://127.0.0.1:<port>/bank2sheets/callback?code=abc&state=s1`, assert the returned URL, and assert a timeout path with a short timeout.

**Step 3: Write minimal implementation**

- `Rs256Jwt`: move the PEM normalization, `KeyFactory`/`PKCS8EncodedKeySpec`, `SHA256withRSA`, and `Base64.getUrlEncoder().withoutPadding()` logic out of `GoogleSheetsClient`; keep the Google claims/headers unchanged and replace its private helpers with calls to the new class.
- `EnableBankingConfig`: record plus `fromEnv(Map<String,String>)` and `fromEnv()`; no `AppConfig` changes in this task.
- `EnableBankingClient`: constructor takes `EnableBankingConfig`, `HttpExchange`, `AuthorizationCodeReceiver`, and a `LongSupplier epochSeconds` (default `Instant.now().getEpochSecond()`) so token claims are testable. JWT TTL 3600 s. Keep all HTTP on `HttpExchange`, never `HttpClient` directly.
- `AuthorizationCodeReceiver`: `String awaitCallbackUrl()`; `LoopbackAuthorizationCodeReceiver` implements it with the JDK HTTP server and a timeout. Use `com.sun.net.httpserver.HttpServer`; if the build cannot resolve `jdk.httpserver`, use a raw `ServerSocket` instead and note the choice.
- `PasteAuthorizationCodeReceiver`: prompts for the full callback URL (testable `Supplier<String>`), trims it, and rejects blank input; used for `https` production redirects. `EnableBankingConfig` accepts `http` loopback or `https` redirects.
- Do not touch `Main`, `AppConfig`, or `RuntimeState` in this task.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`
Expected: all tests green (existing 44 plus the new ones), JaCoCo 80% line gate met.

**Step 5: Commit**

Only if the user explicitly asks. `git add` the new/modified files and use a `feat:` commit. Do not commit unprompted.

## Task 2: Provider-neutral config, state, factory, and Main wiring

**Objective:** Select the provider from `BANK2SHEETS_ACCOUNTS`, wire `EnableBankingClient` into `Main`, persist its session id, and keep Salt Edge working as a selectable fallback.

**Files:**
- Modify: `src/main/java/bank2sheets/config/AppConfig.java`
- Modify: `src/main/java/bank2sheets/config/AccountConfig.java`
- Modify: `src/main/java/bank2sheets/config/RuntimeState.java`
- Create: `src/main/java/bank2sheets/openbanking/ProviderSources.java`
- Modify: `src/main/java/bank2sheets/Main.java`
- Modify: `src/test/java/bank2sheets/config/AppConfigTest.java`
- Modify: `src/test/java/bank2sheets/config/RuntimeStateTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Modify: `README.md`
- Validate: the files above; `.\scripts\mvn.ps1 test`

**Step 1: Establish expected behaviour**

- `BANK2SHEETS_ACCOUNTS` accepts `saltedge:<id>` and `enablebanking:<uuid>`; unknown providers still fail fast; the list must contain exactly one provider per invocation or fail with a message naming the mixed entries.
- `AppConfig` adds `enableBankingAppId`, `enableBankingPrivateKeyB64`, `enableBankingRedirectUrl`, and `enableBankingSessionId` alongside the existing Salt Edge fields; `withConnectionId` becomes a provider-neutral `withProviderState(provider, id)` (or equivalent) used by `Main`.
- `RuntimeState` keeps `saltedge.connection_id` and adds `enablebanking.session_id`; reading either key never changes the other and the file remains the only persisted state.
- `ProviderSources.create(AppConfig)` returns a `TransactionSource` for the selected provider and validates only that provider's required credentials. Enable Banking requires the app id, private key, and redirect URL for every command (the factory builds the client up front); `fetch`/`sync` additionally need a session id and accounts. Salt Edge requires app id + secret.
- `Main` no longer calls `SaltEdgeClient::create` directly; `Main.run` passes `ProviderSources::create`. USAGE text lists both providers' variables and the `enablebanking:` account form. `connect` saves the id under the provider's state key; `fetch`/`sync` merge the env id or the persisted provider state. When `BANK2SHEETS_ACCOUNTS` is empty, `connect` infers the provider from credentials and, after saving the id, prints the available accounts (id + name) so the owner can configure `BANK2SHEETS_ACCOUNTS`.
- `EnableBankingClient`'s code receiver is constructed from `ENABLE_BANKING_REDIRECT_URL` (loopback) or from the paste fallback chosen in Blocking decision 1.

**Step 2: Add or update validation**

- `AppConfigTest`: parses `enablebanking:<uuid>`, still parses `saltedge:<id>`, rejects a mixed list, rejects unknown providers, and reads all `ENABLE_BANKING_*` variables (including blank defaults).
- `RuntimeStateTest`: writes/reads `enablebanking.session_id` and keeps the Salt Edge key readable; assert both keys coexist without clobbering.
- `MainCommandsTest`: with `FakeHttp("https://api.enablebanking.com")` and a fake `AuthorizationCodeReceiver`, drive `connect` (state file gets `enablebanking.session_id`), `fetch` (prints the normalized JSON), a second `sync` (idempotent, appends nothing new), missing credentials, missing session, and a provider error (exit 1). Keep the existing Salt Edge cases green.
- `README.md`: env table and example for Enable Banking, `enablebanking:<uuid>` accounts, and the note that Salt Edge remains selectable.

**Step 3: Write minimal implementation**

- `AppConfig`: add the four Enable Banking fields and provider-scoped parsing; keep `saltEdge*()` accessors. Add a `provider()` helper returning the single configured provider, or inferring Enable Banking (primary) / Salt Edge from credentials when accounts are empty; mixed/unknown still fail. Do not add a generic credentials abstraction beyond what this needs.
- `AccountConfig`: update Javadoc to list both providers; keep the record shape (`provider`, `providerAccountId`).
- `RuntimeState`: add constants `SALTEDGE_CONNECTION_ID_KEY` and `ENABLE_BANKING_SESSION_ID_KEY`, plus provider-scoped read/write methods; keep the old methods delegating to the Salt Edge key for compatibility.
- `ProviderSources`: switch on `cfg.provider()`; `saltedge` → `SaltEdgeClient` with its existing guards; `enablebanking` → new `EnableBankingClient`. Throw a `Bank2SheetsException` with the missing variable names when credentials are absent.
- `Main`: use `ProviderSources::create`; replace `saltEdgeAppId`/`saltEdgeSecret` guards with a factory call that fails with the provider's message; make `withConnectionId` provider-aware; update USAGE. After saving the id, rebuild the source with the new state and print the account ids/names (a listing failure warns but does not fail the saved connect). Do not change the fetch/transform/sync pipeline or the Sheets client.
- Do not add dependencies.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`
Expected: all tests green (existing + new), JaCoCo 80% line gate met.

**Step 5: Commit**

Only if the user explicitly asks.

## Task 3: Documentation, memory reconciliation, and owner validation

**Objective:** Make the durable documentation match the shipped adapter and validate the real restricted-production flow with the owner's BBVA account, including a second sync for idempotency.

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/ai/architecture.md`
- Modify: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Modify: `docs/ai/README.md`
- Modify: `docs/ai/backlog/README.md`
- Modify: `docs/ai/logs/2026-11.md`
- Validate: the docs above; real CLI flow; GitHub issue #3

**Step 1: Establish expected behaviour**

- Docs describe both providers, the Enable Banking env contract, `enablebanking:` accounts, the state file keys, the restricted-mode limits (own linked accounts only, no public/commercial use, rate limits, session renewal), and the loopback/paste callback behaviour.
- ADR-0005 links this plan as its follow-up; issue #3 is updated (human) with the decision and the follow-up plan path.
- The real flow uses the existing disposable test spreadsheet, not the production sheet, and a second `sync` appends 0 rows.

**Step 2: Add or update validation**

- (agent) Secret scan on every changed doc; stale-claim grep for Salt Edge-only statements that now contradict the two-provider reality; `git diff --check`; `git status --short` (docs only).
- (agent) `.\scripts\mvn.ps1 test` if any `src/**` file changed unexpectedly (it must not in this task).
- (human) Create the Enable Banking account/application, register the redirect URL, set `ENABLE_BANKING_*` and `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>`, run `connect` (SCA in the BBVA app), then `fetch`.
- (human) Run `sync` twice against the disposable test sheet: first run appends the transactions, second run appends 0; confirm no duplicate TransactionIds and that amounts are numeric.

**Step 3: Write minimal implementation**

- Update `README.md` (requirements/run/config/sheets layout), `AGENTS.md` (project overview and testing counts), `docs/ai/architecture.md` (provider section, env contract, state invariant, factory note), `docs/ai/README.md` status, `docs/ai/backlog/README.md` deferred item, and `docs/ai/logs/2026-11.md` entry.
- Add a one-line link in ADR-0005 to this plan and to issue #3 (no decision rewrite).
- Do not change code in this task.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`
Expected: green, unchanged test count unless new tests were added in Tasks 1-2.
Human: the real `connect`/`fetch`/`sync` results above.
Status (2026-11-13): sandbox `connect`/`fetch`/`sync`-twice passed (17 then 0 rows); production real-BBVA run pending.

**Step 5: Commit**

Only if the user explicitly asks. Keep the docs/memory commit separate from the code commits if the owner prefers.

## Validation Strategy

- **Agent validations:** targeted tests during development (`.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest'`), full `.\scripts\mvn.ps1 test` per task (44+ tests, JaCoCo 80% line gate), `git diff --check`, secret scan, `git status --short`. No real network calls or credentials in tests.
- **Human validations:** confirm Enable Banking accepts the chosen redirect URL; create the application and link the owner's BBVA account; run the real `connect`/`fetch` and `sync` twice against the disposable test spreadsheet; update issue #3.
- **Owner-only external actions:** Enable Banking signup, application registration, key generation/export, linking own accounts, SCA approval, issue update. The agent never creates accounts, accepts terms, or pays.

## Decisions To Record

- Provider factory and `TransactionSource` fit for Enable Banking (ADR-0005 follow-up link; report through the architect gate).
- `enablebanking` env vars, `enablebanking.session_id` state key, and the `eb:` transaction-id prefix in `architecture.md`.
- Salt Edge retention as a selectable fallback (or its removal, if the owner chooses).
- README/AGENTS two-provider usage and `docs/ai` status lines.

## Implementation Handoff

- Repository root: `C:\DEV\bank2sheets`.
- Scope guard: Task 1 touches the new client/JWT helper and its tests plus the `GoogleSheetsClient` refactor; Task 2 touches config/state/factory/Main and `README.md`; Task 3 is docs/memory only. No `pom.xml`, `.env`, state-file, or credential commits.
- Do not add runtime dependencies: JWT, HTTP, and JSON use the JDK plus the existing Gson.
- Do not run real provider calls or paste credentials into tests; use `FakeHttp` and a fake code receiver.
- Required gates at handoff: `architect_review: true` (provider boundary/factory) and `memory_update: true` (architecture/README/ADR link/log).
- External references: `https://enablebanking.com/docs/api/reference/`, `https://enablebanking.com/docs/api/quick-start/`, `https://enablebanking.com/docs/api/linked-accounts/`, `https://enablebanking.com/docs/faq/`, `https://enablebanking.com/terms/`, and `https://github.com/enablebanking/enablebanking-api-samples`.
