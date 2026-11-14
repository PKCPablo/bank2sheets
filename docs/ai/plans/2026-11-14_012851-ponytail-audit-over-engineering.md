# Ponytail audit - over-engineering scan

- Date: 2026-11-14
- Scope: `src/main`, `src/test`, `scripts/`, `pom.xml`, `.github/workflows`, product docs
- Mode: whole-repository audit (read-only). No product code changed.
- Baseline: 104 tests, ~1,600 main LOC, ~1,800 test LOC.

> This is an audit report, not an approved implementation plan. Items are sized so they can
> be promoted to plans or harvested as `ponytail:` debt.

## Verdict

The codebase is already lean and the architecture decisions are explicitly documented
(ADR-0003, ADR-0005). There is no premature layering, no framework, no database, no custom
HTTP/JSON/JWT stack beyond the justified minimum. The dominant over-engineering is **dead
Salt Edge code**, which is already tracked in GitHub issue #7 and should be resolved there
rather than re-planned here. Everything else is small: duplicated test fixtures, one thin
test wrapper, empty exception subclasses, and micro-duplication.

Estimated total simplification after #7 plus the medium items: **~700 fewer lines (~20%)**
and ~25 fewer tests, with no behavior change.

## Findings by severity

### High

#### H1 - Salt Edge provider path is dead code (defer to issue #7)

- Rung: 1 (does this need to exist?) and 2 (already have one provider).
- Evidence:
  - `openbanking/saltedge/SaltEdgeClient.java` (143) + `SaltEdgeException.java` (11).
  - Dual credential/state plumbing: `AppConfig` 4 Salt Edge fields, provider inference,
    `withProviderState` switch, `providerId` switch; `RuntimeState` two-key switch;
    `ProviderSources.saltEdge` branch; `Main` usage/hint branches.
  - Salt Edge tests: `SaltEdgeClientTest` (150), Salt Edge cases in `AppConfigTest`,
    `RuntimeStateTest`, `ProviderSourcesTest`, `MainCommandsTest` (~200).
  - `README.md` still advertises "Salt Edge kept as a selectable fallback"; ADR-0005 says
    discarded.
- Impact: remove ~150 main + ~450 test LOC and collapse `AppConfig`/`RuntimeState`/
  `ProviderSources`/`Main` to a single provider. The `TransactionSource` seam stays.
- Decision: **do not duplicate issue #7**; implement removal there. This audit adds only
  the post-removal cleanup targets below (some become moot automatically).

#### H2 - Provider abstraction now costs more than it returns

- Rung: 1 (speculative abstraction).
- Evidence: `AccountConfig(provider, providerAccountId)`, `ProviderSources` factory,
  `AppConfig.provider()/providerId()/withProviderState()`, `RuntimeState.key()` switch,
  mixed-provider validation in `parseAccounts`, and provider prefixes in `USAGE`.
  All exist to support two providers, and one is discarded.
- Impact: after #7 these collapse to one provider: no provider field needed in state,
  no factory switch, no inference, no mixed-provider validation. A plain
  comma-separated account-id list is the smallest correct config, though keeping the
  `provider:` prefix is acceptable if you want future-proofing.
- Decision: fold into issue #7; no separate plan.

### Medium

#### M1 - Throwaway RSA key generation duplicated in four test fixtures

- Rung: 2 (reuse) and 6 (one helper).
- Evidence: `base64Pem()` reimplemented in `ProviderSourcesTest:89`,
  `MainCommandsTest:497`, `EnableBankingClientTest:422`; `SheetsTestSupport:24-42`
  has a fourth variant. The first three generate a fresh 2048-bit key on every call
  (the suite calls them repeatedly), while only `SheetsTestSupport` caches one.
- Impact: extract one test helper (e.g. `bank2sheets.TestKeys`) exposing
  `privateKey()`, `pkcs8Pem()`, `base64Pem()`; cache the key in a static final field.
  Removes ~40 lines and noticeably speeds the suite (RSA keygen is the slowest test-only
  operation). Signature is never verified by the fake endpoints, so one shared key is safe.

#### M2 - `FakeSheetsHttp` is a self-declared thin wrapper

- Rung: 1/2 (no behavior added).
- Evidence: `sheets/FakeSheetsHttp.java:19` already carries
  `ponytail: this is a thin wrapper around new FakeHttp(GOOGLE_BASE); tests could use
  FakeHttp directly instead of this class.` It only forwards `stub`/`requests`/`exchange`.
- Impact: delete `FakeSheetsHttp` (36 LOC) and use `new FakeHttp("https://sheets.googleapis.com/v4/spreadsheets")`
  in ~12 call sites. Best done together with #7, which already plans to move `FakeHttp`
  out of the `openbanking.saltedge` package into a provider-neutral test package.

#### M3 - Empty exception subclasses no longer earn their keep

- Rung: 1 (no behavior) - the documented "third client" condition is now met.
- Evidence: `SheetsException`, `EnableBankingException`, `HttpExchangeException` (and
  `SaltEdgeException`, deleted by #7) are empty `Bank2SheetsException` subclasses. No
  production code distinguishes them: `Main` catches the base type; only tests assert
  the concrete types.
- Impact: collapse to `Bank2SheetsException` (or keep exactly one subtype if a concrete
  need appears), removing ~30 LOC and ~15 test-only type assertions. Low risk because the
  messages already carry the useful context (HTTP status, method, path, body).

### Low

- **L1 - Dead `RuntimeState` aliases:** `connectionId()` (`RuntimeState.java:33`) and
  `saveConnectionId()` (`:37`) are used only by `RuntimeStateTest`; production uses
  `read(provider)`/`save(provider,id)`. Remove with #7.
- **L2 - Hand-rolled SHA-256 hex:** `EnableBankingClient.sha256Hex` loops over bytes with
  `"%02x".formatted(b)` (`:419`); `HexFormat.of().formatHex(digest)` (JDK 17+) does it in
  one line. The same loop is mirrored in `EnableBankingClientTest.hashId` (`:405`).
- **L3 - Repeated command error handling:** `Main` repeats the same
  `catch (Bank2SheetsException | IllegalStateException e) -> print prefix + return 1`
  block in `connect`, `fetch`, and `sync` (`Main.java:146,150,178,247`). A tiny
  `runCommand(name, body)` helper would remove ~9 lines, but the current explicit shape is
  arguably clearer; treat as optional.
- **L4 - Repeated test fixtures:** the identical two-transaction JSON payload appears in
  `MainCommandsTest` (fetch, sync, idempotency) and `SaltEdgeClientTest`; the sheet
  token+header+append stub quartet repeats across sync tests. The first is already marked
  at `MainCommandsTest:99`. Extract constants/`stubReadySheet(sheets)` helpers.
  Also `runCli`/`runEnableBanking` overloads in `MainCommandsTest` differ only in the
  source factory and can share one runner.
- **L5 - Environment contract duplicated in seven places:** `Main.USAGE`, `AppConfig`
  Javadoc, `EnableBankingConfig` Javadoc, `SheetsConfig` Javadoc, `README.md`,
  `docs/ai/architecture.md`, `AGENTS.md`. Every env change currently needs multiple edits.
  Recommendation: keep the README table canonical and make code Javadoc link to it; keep
  `USAGE` because it is runtime user help. Do not delete architecture memory.
- **L6 - `jdk.ps1` name-version parser:** already marked at `scripts/jdk.ps1:23`. If
  "highest `jdk-21*`" is not a real requirement, drop `Get-JdkNameVersion` and the
  descending sort (~15 LOC).
- **L7 - Column order defined twice:** `GoogleSheetsClient.HEADER_VALUES` and
  `SheetRow.values()` encode the same A..E order; `hasHeader` also repeats the literal
  `"Fecha"`. Use `HEADER_VALUES.get(0)` for the check and add a comment tying the two
  orderings together (or derive the header labels from a single column definition).
- **L8 - `docs/callback.html` extra UI:** the CLI only consumes the full URL, but the page
  also renders `code`/`state` fields and a clipboard fallback. Optional trim (~15 LOC);
  the copy button is genuinely useful on mobile, so this is informational only.

## Justified - do not "simplify" these

These look like candidates but are deliberate, documented, and cheaper than the
alternatives. Re-litigating them would violate the same reuse-first discipline:

- `HttpExchange` + `RealHttpExchange` seam (tests without an HTTP-stubbing framework).
- `TransactionSource` seam and `Rs256Jwt` (no Google/Bank SDK; no JWT dependency).
- Gson as the only runtime dependency; no Google client library.
- `EnableBankingConfig` redirect validation and `LoopbackAuthorizationCodeReceiver`
  bind-before-print, timeout, and idempotent `start()`.
- `EnableBankingClient` canonical-fields hash suffix for dedupe ids.
- JaCoCo 80% gate, shade fat JAR, and the scheduled workflow running `package`.
- `docs/callback.html` existing at all (production HTTPS redirect requires it).

## Existing `ponytail:` markers (already tracked)

| File | Purpose |
| --- | --- |
| `pom.xml:18` | justifies the single Gson dependency |
| `src/main/java/bank2sheets/sheets/GoogleSheetsClient.java:26` | justifies no Google client lib |
| `src/test/java/bank2sheets/openbanking/saltedge/FakeHttp.java:15` | package location |
| `src/test/java/bank2sheets/sheets/FakeSheetsHttp.java:19` | thin wrapper (actionable - M2) |
| `src/test/java/bank2sheets/MainCommandsTest.java:99` | repeated transaction payloads (actionable - L4) |
| `scripts/jdk.ps1:23` | name-version parser + sort (actionable - L6) |

## Ready-to-apply markers (planner cannot edit source)

Apply with the delivery agent when the item is accepted, or let `/ponytail-debt` harvest
the existing ones first:

- `ProviderSourcesTest.java` / `MainCommandsTest.java` / `EnableBankingClientTest.java`
  `base64Pem()`: `// ponytail: fourth copy of this key helper; consolidate into TestKeys and cache the key (M1).`
- `FakeSheetsHttp.java` class Javadoc: extend the existing marker to
  `// ponytail: delete after #7 and use new FakeHttp(GOOGLE_BASE) directly (M2).`
- `SheetsException.java` / `EnableBankingException.java` / `HttpExchangeException.java`:
  `// ponytail: empty Bank2SheetsException subclass; collapse once tests assert the base type (M3).`
- `RuntimeState.java:33`: `// ponytail: unused outside tests; remove with #7 (L1).`
- `EnableBankingClient.java:419`: `// ponytail: JDK HexFormat.of().formatHex(digest) replaces this loop (L2).`

## Suggested sequence

1. Issue #7 (Salt Edge removal) - biggest win; also makes L1 and the Salt Edge half of
   L4/L5 obsolete.
2. M1 + M2 together in the same test-fixture cleanup (both touch the #7 move of `FakeHttp`).
3. M3 exception collapse (independent, small).
4. L2, L4, L7 opportunistically when touching those files.
5. L5, L6, L8 only if the duplication actually causes a mistake; otherwise leave.

## Implementation status (2026-11-14)

- Implemented: M1, M2, M3, L1, L2, L4, L5, L6, L7, L8.
- Deferred: H1/H2 to GitHub issue #7 (Salt Edge removal); L3 skipped as a readability trade-off.
- Resolved markers: `FakeSheetsHttp.java:19`, `MainCommandsTest.java:99`, `scripts/jdk.ps1:23`.
- Remaining markers are justifications: `pom.xml:18`, `GoogleSheetsClient.java:26`, `FakeHttp.java:15`.
- Validation: 104 tests, 0 failures/errors, JaCoCo 80% gate met; no secrets tracked or staged; nothing committed.
