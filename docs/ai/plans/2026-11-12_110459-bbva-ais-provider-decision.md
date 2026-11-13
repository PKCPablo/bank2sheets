---
status: completed
created: 2026-11-12
task: 3/3
required_gates:
  architect_review: true
  memory_update: true
---

# BBVA AIS provider decision implementation plan

**Goal:** Decide, with recorded evidence, how to restore real BBVA transaction access — either Salt Edge enables `bbva_es` for the existing Service key or a replacement AIS provider is chosen — then record the outcome as an ADR and create the follow-up implementation/validation task.

**Architecture:** This is a research-and-decision plan; it changes `docs/ai/**` only. No product code is touched. Any future provider must stay behind the `TransactionSource` seam, and the current composition root, env config, and runtime-state file hardcode Salt Edge, so a provider change requires a separate follow-up plan (provider-selection factory + config + state), not code in this plan.

**Tech Stack:** No new dependencies. Markdown/AI memory docs, GitHub issue workflow, read-only Salt Edge API checks, and official provider documentation/pricing pages.

**Scope classification:** `multi-task`

## Context (verified repository facts)

- `TransactionSource` is the only bank-provider boundary: `connect()`, `findConnectionId()`, `isActive(String)`, `listAccounts()`, `fetchTransactions(String)` (`src/main/java/bank2sheets/openbanking/TransactionSource.java`). A new provider implements this interface.
- Provider selection is **hardcoded** today:
  - `Main.run` wires `SaltEdgeClient::create` as the only source factory (`src/main/java/bank2sheets/Main.java:83`).
  - `AppConfig` reads `SALTEDGE_APP_ID`, `SALTEDGE_SECRET`, `SALTEDGE_CUSTOMER_ID`, `SALTEDGE_CONNECTION_ID` and rejects any `BANK2SHEETS_ACCOUNTS` provider other than `saltedge` (`src/main/java/bank2sheets/config/AppConfig.java:35-68`).
  - `AccountConfig.provider` is currently "always saltedge and never read" (`src/main/java/bank2sheets/config/AccountConfig.java:8-9`).
  - `RuntimeState` persists only `saltedge.connection_id` (`src/main/java/bank2sheets/config/RuntimeState.java:17`).
- `docs/ai/architecture.md:42` and ADR-0003 already anticipate the second-provider change: "second provider → provider-selection factory keyed by config". This plan does **not** implement that factory.
- Existing evidence is already captured in GitHub issue #3, `docs/ai/logs/2026-10.md:26-30`, and `docs/ai/backlog/README.md:18-20`: the ES provider catalog has 16 providers with no BBVA, `GET /providers/bbva_es` returns 404 `ProviderNotFound`, and all available ES providers are `web` (no `oauth`).
- ADR-0002 (2026-08-04) rejected GoCardless/Nordigen as enterprise-only after it stopped onboarding new customers in 2025, while issue #3 still lists "GoCardless Bank Account Data (ex Nordigen)" as a candidate. This discrepancy must be re-verified against current sources, not assumed.
- Documentation that currently asserts the blocker and must be reconciled by Task 3: `README.md:3,43,63,79`, `AGENTS.md:5`, `docs/ai/README.md:9`, `docs/ai/architecture.md:16,22-30,39`, `docs/ai/backlog/README.md:18-20`, `docs/ai/logs/2026-11.md:10`.
- No code change is expected in this plan, so the existing 44-test / JaCoCo 80% gate is not re-run unless a `src/**` file unexpectedly changes (it must not).

## Blocking decisions and gates

1. **Salt Edge support answer is external and asynchronous.** The owner must send the request and may wait days/weeks. Before Task 3, agree on a bounded wait (proposal: 10 business days). If no answer arrives, record "Salt Edge unresolved" and decide on an alternative rather than blocking indefinitely.
2. **Owner approval is required** to select a replacement provider, accept any paid plan/contract, or add a new runtime dependency (Gson is the only one today).
3. **Owner credentials/accounts are required** for any alternative provider (signup, API keys, SCA device). The agent must never create accounts, accept terms, or pay.
4. **New ADR vs appending ADR-0002:** recommended is a new `ADR-0005-bbva-ais-provider.md`, because ADR-0002 is accepted and already has two implementation notes; the issue allows either. Record the choice in the ADR index/status. This is a low-risk decision, not a blocker.
5. **No implementation in this plan.** If a replacement provider is chosen, Task 3 creates a separate implementation plan/issue; provider code, config, and runtime-state changes are explicitly out of scope here.
6. **Architect review gate** applies to the ADR decision (provider strategy and the future `TransactionSource` factory), not to the evidence gathering.

## Task 1: Salt Edge enablement evidence and support request

**Objective:** Establish whether Salt Edge can enable `bbva_es` for the existing Service key — under its own licence or via an owner Provider Key — and record the evidence plus the support request.

**Files:**
- Create: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Validate: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Human reference (do not modify): `.env` (read only by the owner; never commit or print values)

**Step 1: Establish expected behaviour**

The ADR's Salt Edge section must prove the current state and capture the exact questions sent to support:

- Current state (from issue #3 / `logs/2026-10.md`): `GET /api/v6/providers?country_code=ES` → 16 providers, no BBVA; `include_inactive=true` → same 16; `GET /api/v6/providers/bbva_es` → 404 `ProviderNotFound`; `GET /api/v6/providers?country_code=ES&mode=oauth` → 0.
- Questions for Salt Edge: can `bbva_es` be enabled for this application under Salt Edge's licence? If not, does it require an owner **Provider Key** (PSD2 eIDAS certificate + TPP registration with BBVA)? What is the cost, timeline, and expected SCA/consent flow? Is using a different supported Spanish bank the only fallback within Salt Edge?
- Do **not** record the Service key secret or a live App-id value in the repository; redact it as `<APP_ID>` in the ADR and put the real value only in the support message.

**Step 2: Add or update validation**

- (human) Owner re-runs the four read-only catalog checks against the current Service key and confirms the result still matches. Provide placeholders, e.g.:
  - `GET https://www.saltedge.com/api/v6/providers?country_code=ES`
  - `GET https://www.saltedge.com/api/v6/providers?country_code=ES&include_inactive=true`
  - `GET https://www.saltedge.com/api/v6/providers/bbva_es`
  - `GET https://www.saltedge.com/api/v6/providers?country_code=ES&mode=oauth`
- (agent) Check the Salt Edge V6 Provider Keys documentation and record URL + access date; distinguish documented facts from support-only answers.

**Step 3: Write minimal implementation**

Create `docs/ai/decisions/ADR-0005-bbva-ais-provider.md` with:

- `Status: proposed`, date `2026-11-12`.
- Context: real BBVA blocker, pointer to issue #3 and `logs/2026-10.md`.
- Salt Edge evidence table (check, observed result, source/date).
- Support request draft (questions above + sanitized evidence + `<APP_ID>` placeholder).
- Placeholder sections for the alternatives matrix and the final decision (filled by Tasks 2 and 3).
- Explicit non-goals: no provider code, no dependency change, no real spreadsheet writes.

**Step 4: Run check to verify pass**

- (agent) `git diff --check` → no whitespace errors.
- (agent) Search the new ADR for accidental secrets: `Select-String -Path docs/ai/decisions/ADR-0005-bbva-ais-provider.md -Pattern 'SALTEDGE_SECRET|Secret:|App-id: [A-Za-z0-9]'` → no matches beyond redacted placeholders.
- (human) Owner sends the support request and records the ticket id/date in the ADR; response is appended when received or the wait window expires.

**Step 5: Commit**

Only if the user explicitly asks. `git add docs/ai/decisions/ADR-0005-bbva-ais-provider.md` then a `docs(ai): ...` commit.

## Task 2: Alternative AIS provider evaluation (bounded shortlist)

**Objective:** Screen the candidate alternatives for BBVA Spain AIS and deep-dive the two most promising, then fill the comparison matrix.

**Files:**
- Modify: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Validate: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`

**Step 1: Establish expected behaviour**

- Candidates from issue #3: **Powens** (ex Budget Insight), **GoCardless Bank Account Data** (ex Nordigen), **Tink**, **TrueLayer**, **Enable Banking**.
- Shallow-screen every candidate for: BBVA Spain AIS coverage; personal/solo project eligibility; whether an own PSD2 licence/eIDAS certificate is required; whether a free/sandbox tier exists.
- Deep-dive the two most promising. Each matrix row must capture:
  1. BBVA Spain AIS support (accounts + transactions, read-only).
  2. SCA/consent flow compatible with a local CLI (`connect_url`/redirect + callback semantics).
  3. Pricing/plan limits for one personal user; paid plan required or not.
  4. API payload shape for accounts/transactions and auth model (how much adapter code a `TransactionSource` implementation needs).
  5. Eligibility: can a solo/personal project use it without its own PSD2 licence, and is BBVA actually connected?
- Explicitly re-check the ADR-0002 GoCardless/Nordigen rejection and mark it **verified current** or **superseded** with a source.

**Step 2: Add or update validation**

- (agent) Read official provider documentation/pricing pages; record URL + access date for every claim.
- (human) Owner confirms anything that requires an account, sales contact, or region-specific eligibility; do not sign up or accept terms on the owner's behalf.
- Treat unverified claims as assumptions, labelled as such in the matrix; do not present them as facts.

**Step 3: Write minimal implementation**

Append to ADR-0005 a comparison table with one row per candidate and columns: `BBVA ES AIS`, `SCA flow`, `Pricing / personal use`, `API shape / adapter effort`, `Own PSD2 licence`, `Verdict`, `Sources (URL + date)`. Add short notes for screened-out candidates (why only shallow-screened).

**Step 4: Run check to verify pass**

- (agent) Every row has at least one source URL with an access date; assumptions are labelled.
- (agent) `git diff --check`.
- (human) Owner reviews the shortlist and confirms no paid commitment was implied.

**Step 5: Commit**

Only if the user explicitly asks.

## Task 3: Decide, record the ADR, update memory, and create the follow-up

**Objective:** Make and record the provider decision, reconcile all `docs/ai` claims with it, and create the next actionable task (real-connect validation or implementation plan).

**Files:**
- Modify: `docs/ai/decisions/ADR-0005-bbva-ais-provider.md`
- Modify: `docs/ai/architecture.md`
- Modify: `docs/ai/README.md`
- Modify: `docs/ai/backlog/README.md`
- Modify: `docs/ai/logs/2026-11.md`
- Modify (only if provider naming/usage changes): `README.md`, `AGENTS.md`
- Validate: all modified docs; GitHub issue #3 acceptance criteria

**Step 1: Establish expected behaviour**

The decision must satisfy issue #3 and pick exactly one outcome:

- **A — Salt Edge enables `bbva_es`:** ADR records the confirmation; next task is owner-gated real `connect` (SCA) + `sync` twice to the test spreadsheet, then real spreadsheet. No provider code change.
- **B — Replacement provider chosen:** ADR records the chosen provider and rejected alternatives; next task is a separate implementation plan covering the provider adapter, provider-selection factory keyed by `BANK2SHEETS_ACCOUNTS`, provider-specific env/state, and fake-HTTP tests. No code in this plan.
- **C — Still blocked:** ADR records the blocker and a concrete revisit condition/date; issue #3 stays open with `status:blocked` if appropriate.

The decision must preserve: `TransactionSource` seam, AIS-only read consent, no new runtime dependency without owner approval, and test-spreadsheet isolation.

**Step 2: Add or update validation**

- (agent) Cross-check the ADR against every issue #3 acceptance criterion and list each as met/not met in the handoff.
- (agent) Grep the repo for stale claims that now contradict the decision (see Context list) and confirm each is updated or clearly historical.
- (human/gate) Architect review of the ADR and the provider-boundary consequence (`architect_review: true`).
- (human) Owner approves the provider choice, pricing, and any dependency; if outcome A, owner confirms Salt Edge's written answer.

**Step 3: Write minimal implementation**

- Set ADR-0005 `Status: accepted` (or `proposed` + blocker if outcome C) and fill the Decision/Consequences sections.
- Update `docs/ai/architecture.md`: provider line, env/state contract (only if the chosen provider changes them), and the "second provider → factory" note.
- Update `docs/ai/README.md` repository status line (currently says real BBVA is blocked) and `docs/ai/backlog/README.md` deferred item.
- Append a short entry to `docs/ai/logs/2026-11.md` with the decision, evidence links, gates, and follow-up.
- If outcome B: create the follow-up implementation plan/issue via the plan workflow, with owner confirmation; link it from ADR-0005 and issue #3.

**Step 4: Run check to verify pass**

- (agent) `git diff --check` and `git status --short` → only expected docs changed, no `src/**`, no `.env`/state file.
- (agent) Secret scan on changed docs.
- (agent) Stale-claim grep, e.g. `Select-String -Path docs/ai/**,README.md,AGENTS.md -Pattern 'bbva_es.*(absent|blocked)|real BBVA is blocked'` → only intentional historical references remain.
- (agent) If any `src/**` file changed (it should not), run `.\scripts\mvn.ps1 test` → 44 tests green, JaCoCo 80% gate met.

**Step 5: Commit**

Only if the user explicitly asks. Commit the ADR + memory updates together; keep the follow-up plan in its own commit if created.

## Validation Strategy

- **Agent validations:** document consistency, source URL + access date on every evidence claim, stale-claim grep, secret scan, `git diff --check`, and `git status --short`. No `mvn test` unless `src/**` changes.
- **Human validations:** re-run Salt Edge catalog checks with the Service key; send/record the Salt Edge support request; confirm provider pricing/eligibility; approve any provider or dependency change; run real `connect`/`sync` if outcome A.
- **Owner-only external actions:** provider signup, terms, payment, credentials, SCA device.

## Decisions To Record

- New ADR-0005 with the evidenced provider decision (or append to ADR-0002 if the owner prefers, documented in the ADR).
- `architecture.md`: provider/env/runtime-state boundary after the decision.
- `docs/ai/README.md` status line and `docs/ai/backlog/README.md` deferred item.
- `docs/ai/logs/2026-11.md` short entry.
- Follow-up implementation plan/task when a replacement provider is chosen.

## Implementation Handoff

- Repository root: `C:\DEV\bank2sheets`
- Scope guard: docs/`docs/ai` only; do not modify `src/**`, `pom.xml`, `.env`, or `bank2sheets-state.properties`.
- Do not implement provider code in this plan. Outcome B requires a separate plan whose acceptance criteria explicitly cover the provider factory in `Main`, `AppConfig.parseAccounts`, `RuntimeState`, env contract, and fake-HTTP tests.
- Do not contaminate the real spreadsheet; any real-provider validation uses the existing test spreadsheet flow.
- Required gates at handoff: `architect_review: true` (provider boundary/ADR) and `memory_update: true` (ADR + routed memory).
- External references: Salt Edge V6 API reference `https://docs.saltedge.com/v6/api_reference`, Salt Edge Provider Keys docs, and each candidate provider's official AIS/pricing documentation.
