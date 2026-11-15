---
status: completed
created: 2026-11-15
task: 3/3
required_gates:
  architect_review: false
  memory_update: true
---

# Fix ponytail audit findings — implementation plan

**Goal:** Resolve every actionable over-engineering finding from the 2026-11-15 audit while keeping behavior, the 89-test baseline, and the JaCoCo 80% line gate unchanged.

**Architecture:** Three independent slices: (1) make `docs/ai/README.md` a router again and close the audit-history gap in the monthly log; (2) remove redundant regression tests and dedupe the remaining `MainCommandsTest` session/details stubs; (3) extract the shared JSON field helper and apply two micro-cleanups in the HTTP clients. Each slice is validated with the existing suite.

**Tech Stack:** Java 21, Maven 3.9.9, JUnit 5, Gson 2.11.0, PowerShell wrappers.

**Scope classification:** `multi-task`

**Baseline:** `HEAD ad6a1fe`; `.\scripts\mvn.ps1 test` → 89 tests, 0 failures/errors, JaCoCo 80% line gate met.

**Note:** The planner cannot edit source. Apply this plan with the delivery agent (`/load-plan`, `/implement-task`), then run the validation steps.

## Tasks

### Task 1: Restore the AI-memory router and close the audit log gap

**Objective:** Stop `docs/ai/README.md` from duplicating feature history, and record the post-audit state so the log matches HEAD.

**Files:**
- Modify: `docs/ai/README.md` (the `## Repository Status` body, currently one very long paragraph)
- Modify: `docs/ai/logs/2026-11.md` (append a new dated section; do not rewrite historical entries)
- Validate: `docs/ai/README.md`, `docs/ai/logs/2026-11.md`

**Step 1: Establish expected behaviour**

`docs/ai/README.md` must stay a short router: one stable status sentence plus links to `architecture.md`, `decisions/`, `plans/`, and `logs/`. It must not carry per-feature history that belongs in plans/logs. The monthly log must reflect the current baseline (89 tests) and the two audit commits (`e84aad0`, `ad6a1fe`).

**Step 2: Add or update validation**

Use these checks after editing (read-only, agent-owned):

```powershell
$status = Get-Content docs/ai/README.md -Raw
if ($status.Length -gt 1800) { throw 'README router is still too large' }
Select-String -Path docs/ai/README.md -Pattern '89 tests|JaCoCo'
Select-String -Path docs/ai/logs/2026-11.md -Pattern 'e84aad0|ad6a1fe|89 tests'
```

Expected: the router contains a current status line and links; the log contains both audit entries and the 89-test baseline.

**Step 3: Write minimal implementation**

In `docs/ai/README.md`, replace the long `## Repository Status` paragraph with a 2-3 sentence summary such as:

```md
## Repository Status

v1 MVP is implemented and validated: Enable Banking restricted production is the only provider, Sheets sync is idempotent, multi-account rows carry `Cuenta`, and monthly GitHub Actions sync is verified. Current baseline: 89 tests green, JaCoCo 80% line gate met.

Details live in `architecture.md`, `decisions/`, `plans/`, and `logs/`; this status changes only when the project status changes, not for every feature.
```

In `docs/ai/logs/2026-11.md`, append a new section at the end (after the multi-account entry):

```md
## 2026-11-15 - Ponytail audit follow-up and third pass

- Follow-up audit (`plans/2026-11-15_230537-ponytail-audit-followup.md`) applied in `e84aad0`: fixed the duplicate test helper that broke the build, deduped account-label resolution in `Main`, removed the dead `EnableBankingConfig.fromEnv` path and redundant tests, simplified `RuntimeState.read`, the Sheets header check, and `ProviderSources`, and removed the undocumented product label fallback.
- Third-pass cleanups in `ad6a1fe`: removed the unreachable session check and stale task references, shared the ASPSP/auth and CLI fixtures, extracted `GoogleSheetsClient.requireOk`, reused the loopback test harness, switched to `assertThrows`, and trimmed duplicated status prose.
- Test baseline moved from 96 to 89 after seven redundant tests were removed; `HEAD` is green (89 tests, 0 failures/errors, JaCoCo 80% line gate met).
```

Do not delete historical entries or rewrite the earlier "96 tests" line; the new section explains the drop.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`
Expected: 89 tests, 0 failures/errors, `BUILD SUCCESS`, JaCoCo gate met.

**Step 5: Commit**

Do not commit unless the user explicitly asks. If asked:

```powershell
git add docs/ai/README.md docs/ai/logs/2026-11.md
git commit -m "docs: refresh AI memory after ponytail audits"
```

### Task 2: Dedupe the remaining test fixtures and Salt Edge regression assertions

**Objective:** Keep the same behavior coverage with fewer duplicated stubs and fewer redundant removed-provider assertions.

**Files:**
- Modify: `src/test/java/bank2sheets/config/AppConfigTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Validate: `src/test/java/bank2sheets/config/AppConfigTest.java`, `src/test/java/bank2sheets/MainCommandsTest.java`, `src/test/java/bank2sheets/config/RuntimeStateTest.java`

**Step 1: Establish expected behaviour**

`AppConfig.parseAccounts` still rejects unsupported providers once (with the provider name in the message), and an empty environment still fails in `provider()` without mentioning legacy Salt Edge variables. `MainCommandsTest` still covers connect/fetch/sync, multi-account labels, the blank-name fallback, legacy state handling, and failure paths, but uses shared session/details stubs.

**Step 2: Add or update validation**

Run the focused tests first:

```powershell
.\scripts\mvn.ps1 test '-Dtest=AppConfigTest,MainCommandsTest,RuntimeStateTest'
```

Expected: all selected tests pass.

Then run the full suite:

```powershell
.\scripts\mvn.ps1 test
```

Expected: 87 tests (two redundant tests removed), 0 failures/errors, JaCoCo 80% line gate met.

**Step 3: Write minimal implementation**

In `AppConfigTest`:

- Delete `rejectsSaltEdgeProvider`; keep `rejectsUnsupportedProvider` and strengthen it:

```java
IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> AppConfig.parseAccounts("powens:uuid-1"));
assertEquals("Unsupported provider: powens", error.getMessage());
```

- Delete `providerDoesNotInferSaltEdgeFromLegacyCredentials`; `providerFailsWithoutAccountsOrCredentials` already covers the no-accounts/no-Enable-credentials path.
- In `providerFailsWithoutAccountsOrCredentials`, replace `assertTrue(!error.getMessage().contains("SALTEDGE_APP_ID"))` with `assertFalse(...)` and add the `assertFalse` static import.

In `MainCommandsTest`, add two helpers near `stubReadySheet`:

```java
/** Stubs the session response with the given JSON array of account ids. */
private static void stubSession(FakeHttp http, String accountsJson) {
    http.stub("/sessions/sess-eb-1",
            "{\"status\": \"AUTHORIZED\", \"accounts\": " + accountsJson + "}");
}

/** Stubs the account-details response for one account id. */
private static void stubAccountDetails(FakeHttp http, String uuid, String name) {
    http.stub("/accounts/" + uuid + "/details", "{\"name\": \"" + name + "\"}");
}
```

Replace the repeated literals:

- `http.stub("/sessions/sess-eb-1", ...)` → `stubSession(http, "[]")`, `stubSession(http, "[\"uuid-1\"]")`, `stubSession(http, "[\"uuid-1\", \"uuid-2\"]")`, etc.
- `http.stub("/accounts/uuid-1/details", "{\"name\": \"Cuenta Nominas\"}")` → `stubAccountDetails(http, "uuid-1", "Cuenta Nominas")`.
- The blank-name test uses `stubAccountDetails(http, "uuid-1", "")`.

Keep both legacy-state tests: `MainCommandsTest.fetchIgnoresLegacySaltEdgeStateKey` covers the end-to-end fetch seam, and `RuntimeStateTest.legacySaltEdgeKeyIsPreservedAndIgnored` covers the persistence primitive. They test different layers; do not remove either.

**Step 4: Run test to verify pass**

Run:

```powershell
.\scripts\mvn.ps1 test
```

Expected: 87 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`.

**Step 5: Commit**

Do not commit unless the user explicitly asks. If asked:

```powershell
git add src/test/java/bank2sheets/config/AppConfigTest.java src/test/java/bank2sheets/MainCommandsTest.java
git commit -m "test: dedupe audit fixtures and removed-provider assertions"
```

### Task 3: Share JSON field handling and finish the HTTP-client micro-cleanups

**Objective:** Remove the duplicated optional-JSON-field handling across the two clients, keep the Sheets header index consistent, and use the existing URL-encoding helper in the Enable Banking client.

**Files:**
- Create: `src/main/java/bank2sheets/http/JsonSupport.java`
- Modify: `src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java`
- Modify: `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java`
- Validate: existing `EnableBankingClientTest`, `GoogleSheetsClientTest`, `MainCommandsTest`; full suite

**Step 1: Establish expected behaviour**

Both clients parse JSON objects and read optional string members the same way. A missing or JSON-null member yields `""`; a present member yields its string value. `GoogleSheetsClient` keeps its current error messages for a missing `client_email`/`private_key`/`access_token`. The Enable Banking continuation key is encoded through the existing `encode(...)` helper. The Sheets header check uses one last-column index instead of mixing `size() - 1` and `getLast()`.

Behavior note: because `stringOrEmpty` maps an explicitly empty string to the same blank check, an empty `client_email`, `private_key`, `token_uri`, or `access_token` now follows the existing missing-field path (default for `token_uri`, descriptive `Bank2SheetsException` otherwise) instead of proceeding to a downstream signing/auth failure. This is the same failure class, reported earlier and more clearly; call it out in the implementation summary.

**Step 2: Add or update validation**

No new test class is required; the helper is exercised through both clients. Run the focused tests:

```powershell
.\scripts\mvn.ps1 test '-Dtest=EnableBankingClientTest,GoogleSheetsClientTest,MainCommandsTest'
```

Expected: all selected tests pass.

Then run the full suite:

```powershell
.\scripts\mvn.ps1 test
```

Expected: 87 tests, 0 failures/errors, JaCoCo 80% line gate met.

**Step 3: Write minimal implementation**

Create `src/main/java/bank2sheets/http/JsonSupport.java`:

```java
package bank2sheets.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Small Gson helpers shared by the HTTP-backed clients. */
public final class JsonSupport {

    private JsonSupport() {
    }

    /** Parses a response body as a JSON object. */
    public static JsonObject parseObject(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /** Member text, or "" when the member is missing or JSON null. */
    public static String stringOrEmpty(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
```

In `EnableBankingClient`:

- Import `bank2sheets.http.JsonSupport`.
- Delete the private `parse(String)` and `stringOrEmpty(JsonObject, String)` methods.
- Replace `parse(...)` with `JsonSupport.parseObject(...)` and `stringOrEmpty(...)` with `JsonSupport.stringOrEmpty(...)`.
- Remove the now-unused `JsonParser` import.
- In `fetchTransactions`, replace `URLEncoder.encode(continuationKey, StandardCharsets.UTF_8)` with `encode(continuationKey)`.

In `GoogleSheetsClient`:

- Import `bank2sheets.http.JsonSupport`.
- In `accessToken()`, use `JsonSupport.stringOrEmpty(serviceAccount, "client_email")`, `...("private_key")`, `...("token_uri")`, and `...("result", "access_token")`; keep the same blank-field error messages and the `TOKEN_URI` default.
- Use `JsonSupport.parseObject(...)` in `readValues`, `parseServiceAccount`, and the token response.
- Remove the now-unused `JsonParser` import if nothing else uses it.
- In `appendRows`, compute `int lastHeaderIndex = HEADER_VALUES.size() - 1;` and use it for both `stringAt(headerRow, lastHeaderIndex)` and `HEADER_VALUES.get(lastHeaderIndex)`.

Do not add constructor injection for `EnableBankingClient.sessionId`, a `Main` label helper, or a `Main` catch wrapper in this task; those were optional/low-value and would add API surface or churn.

**Step 4: Run test to verify pass**

Run:

```powershell
.\scripts\mvn.ps1 test
git diff --check
```

Expected: 87 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`, and no whitespace errors.

**Step 5: Commit**

Do not commit unless the user explicitly asks. If asked:

```powershell
git add src/main/java/bank2sheets/http/JsonSupport.java \
        src/main/java/bank2sheets/openbanking/enablebanking/EnableBankingClient.java \
        src/main/java/bank2sheets/sheets/GoogleSheetsClient.java
git commit -m "refactor: share JSON field handling across HTTP clients"
```

## Deliberately out of scope

These were audited and are kept; do not "fix" them in this plan:

- **Single-provider seam** (`AccountConfig.provider`, `AppConfig.provider()` inference, `RuntimeState.key(provider)`, `ProviderSources.create` validation). Documented as the future Powens seam in ADR-0003/ADR-0005 and retained by the two earlier audits. Revisit only if Powens is abandoned.
- **Environment contract duplication** between `README.md` and `docs/ai/architecture.md`. The README table is canonical and the architecture section is durable memory; the earlier audit explicitly decided to keep it.
- **`EnableBankingClient.setSessionId` constructor injection**, the one-call `now()` helper, `Main` label helpers, and the repeated `Main` catch blocks (prior readability decision).
- **`docs/callback.html`**, the JaCoCo gate, the shaded JAR, the monthly workflow, and the `HttpExchange`/`TransactionSource`/`Rs256Jwt` seams (audit "Justified" list).
- **`.opencode/**`** is wayforge-managed and outside the product audit scope.

## Final validation

After all three tasks:

```powershell
.\scripts\mvn.ps1 test
git diff --check
git status --short
```

Expected: 87 tests, 0 failures/errors, JaCoCo 80% line gate met, `BUILD SUCCESS`; no whitespace errors; only the intended files changed; no secrets or state files staged.
