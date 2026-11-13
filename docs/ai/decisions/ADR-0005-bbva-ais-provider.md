# ADR-0005: BBVA AIS provider decision — Salt Edge enablement vs replacement provider

- Status: accepted
- Date: 2026-11-12
- Decision date: 2026-11-13

## Context

Real BBVA access is the blocker for phase 2 of the original plan: the Salt Edge V6 **Service** key created on 2026-10-27 cannot see `bbva_es` at all. The sandbox end-to-end validation that day used the fake provider `bbva_oauth_client_es_xf` (country `XF`) instead, and the gap was recorded in GitHub issue #3 and `docs/ai/logs/2026-10.md` (section "Salt Edge V6 sandbox validation; real BBVA provider blocked", 2026-10-27). The deferred backlog item in `docs/ai/backlog/README.md` tracks the same blocker.

This ADR decides between:

- **A** — Salt Edge enables `bbva_es` for the existing Service key (under Salt Edge's own licence, or via an owner **Provider Key**).
- **B** — a replacement AIS provider is adopted (behind the existing `TransactionSource` seam).
- **C** — still blocked; record a revisit condition.

## Salt Edge evidence (current state)

All checks were read-only catalog queries against the existing Service key, captured 2026-10-27 (source: `docs/ai/logs/2026-10.md:29`; also GitHub issue #3). The owner should re-run them to confirm the state still holds (see Validation below).

| Check | Observed result | Source / date |
|---|---|---|
| `GET https://www.saltedge.com/api/v6/providers?country_code=ES` | 16 providers, **no BBVA** | `docs/ai/logs/2026-10.md:29`, issue #3 — 2026-10-27 |
| `GET https://www.saltedge.com/api/v6/providers?country_code=ES&include_inactive=true` | same 16 providers (no hidden/inactive BBVA) | `docs/ai/logs/2026-10.md:29`, issue #3 — 2026-10-27 |
| `GET https://www.saltedge.com/api/v6/providers/bbva_es` | 404 `ProviderNotFound` | `docs/ai/logs/2026-10.md:29`, issue #3 — 2026-10-27 |
| `GET https://www.saltedge.com/api/v6/providers?country_code=ES&mode=oauth` | 0 providers (all available ES providers are `web`) | `docs/ai/logs/2026-10.md:29`, issue #3 — 2026-10-27 |

### Client application status (owner-reported, 2026-11-12)

The Salt Edge application is currently in **Pending** status (owner-provided Client Dashboard, 2026-11-12; not agent-verified via API).

| Status | Provider access | Connections | Cost |
|---|---|---|---|
| Pending | Fake providers only; non-permitted actions return `ClientPending` | up to 10 fake | — |
| Test | Real banks | up to 100 | free of charge, 90-day window |
| Live | Real banks | unlimited | billed per request |

Salt Edge remains a possible 90-day free validation fallback only if the Enable Banking path fails, but it is not the planned provider.

To upgrade from Pending to Test, Salt Edge requires: (1) Application Information in the Applications section, (2) Company Information, (3) valid Callback URLs, and (4) a Test access request on the "request test access" page; assessment takes up to two business days. Data destruction (customers, connections, accounts, transactions) is allowed while the application is not Live.

### Test duration and Live transition (documented, 2026-11-12)

- Test: 100 active connections, **90 days**, free.
- After 90 days: the Test account auto-reverts to Pending unless Salt Edge expressly agrees in writing to extend the period (ToS §9.3).
- Data is preserved on revert (destruction is optional while not Live), but real providers are not usable in Pending.
- Live: requires a written Service Provision Agreement with the legal entity owning the application, HTTPS callbacks, integration review, and billed terms per that agreement; no public price list (ToS §9.4; V6 docs Statuses).

Sources: owner-provided Salt Edge Client Dashboard, 2026-11-12; Salt Edge V6 docs, `https://docs.saltedge.com/v6/` (Dashboard → Statuses section), accessed 2026-11-12; Salt Edge Terms of Service, `https://www.saltedge.com/legal/terms_of_service` (last revised 2023-09-14), accessed 2026-11-12.

**Provisional evidence note:** the 2026-10-27 catalog was captured while the application was in Pending status, so the absence of `bbva_es` may reflect the account status rather than missing Salt Edge coverage; that Pending hides `bbva_es` cannot be ruled out. The conclusion is provisional until the same four checks are repeated in Test status.

### Documented facts vs support-only answers

**Documented (Salt Edge V6 docs, `https://docs.saltedge.com/v6/`, accessed 2026-11-12):**

- The V6 Providers List endpoint supports a `key_owner` filter (`client` or `saltedge`): providers can be served under Salt Edge's licence or under the client's own keys.
- "Client Provider Keys": the gateway supports AIS/PIS with PSD2/Open Banking compliant APIs where the API keys for the provider are provided and managed by the client. Keys are limited to one per provider; once created, the provider becomes accessible like a regular one (shown in the widget as accessed "via YOUR_CLIENT_NAME"). Integration is done from the Client Dashboard → Provider Keys page.
- eIDAS certificates (QWAC/QSEAL, soft-issued) are supported for identification towards ASPSPs; a TPP needs them to access an ASPSP's live API / dedicated interface.
- The Providers Show endpoint documents the `ProviderNotFound` 404 error class observed above.

**Support-only (not answerable from public docs — pending, awaiting response):**

- Whether Salt Edge can enable `bbva_es` for this application under **Salt Edge's own licence** (i.e. whether BBVA Spain is connected at all on their side, and if so why it is not visible to this key).
- Whether an owner **Provider Key** for BBVA is possible for a personal/solo project, and what it requires in practice (PSD2 eIDAS certificate + TPP registration with BBVA).
- Cost, timeline, and the expected SCA/consent flow for `bbva_es`.
- Whether a different supported Spanish bank is the only fallback within Salt Edge.

## Support request draft (owner sends; do not put real credentials in the repo)

**Not sent — closed by the 2026-11-13 decision.**

> Subject: Enabling `bbva_es` (BBVA Spain AIS) for our Service key
>
> Hello,
>
> We use a Salt Edge V6 **Service** key (App-id: `<APP_ID>`) for a personal, read-only account-information tool. Our application is currently in Pending status. We cannot access BBVA Spain:
>
> - `GET /api/v6/providers?country_code=ES` returns 16 providers, none of them BBVA (also with `include_inactive=true`).
> - `GET /api/v6/providers/bbva_es` returns 404 `ProviderNotFound`.
> - `GET /api/v6/providers?country_code=ES&mode=oauth` returns 0 providers.
>
> Questions:
>
> 1. Can `bbva_es` be enabled for our application under Salt Edge's own licence? If BBVA Spain is connected on your side, why is it not visible to our key?
> 2. If not, does accessing BBVA require us to create an owner **Provider Key** (PSD2 eIDAS certificate + TPP registration with BBVA)? Is that feasible for a personal/solo project?
> 3. If `bbva_es` works in Test, what are the terms after the 90-day Test window — is a written extension possible, and what would going Live require (Service Provision Agreement, legal entity, pricing)?
> 4. If BBVA Spain is not achievable, is using a different supported Spanish bank the only fallback within Salt Edge?
> 5. Does `bbva_es` availability depend on the application status (Pending vs Test vs Live) — i.e. would it become visible after upgrading to Test?
>
> Thank you.

Ticket id / date sent: _pending (owner records after sending)_
Response: _pending (appended when received, or when the agreed wait window — proposal: 10 business days — expires)_

## Validation

**Not executed — superseded by the 2026-11-13 decision (Salt Edge Test/Live not pursued).** Content kept for history.

The 2026-10-27 catalog results above are agent-captured evidence (from `docs/ai/logs/2026-10.md` and issue #3), not owner-verified. The owner must re-run the four read-only checks below against the current Service key and confirm the state still holds before Task 3.

Owner checklist — Status: pending owner re-run

`Step 0 — upgrade Pending → Test (owner):` complete the four upgrade requirements (Application Information, Company Information, valid Callback URLs, request Test access; assessment within two business days) — Status: pending owner action. Known friction: Test requires valid Callback URLs and the CLI runs locally with no public callback endpoint, so a public callback URL must be arranged first.

The four catalog checks below must be re-run **in Test status**, not Pending:

- [ ] `GET https://www.saltedge.com/api/v6/providers?country_code=ES`
- [ ] `GET https://www.saltedge.com/api/v6/providers?country_code=ES&include_inactive=true`
- [ ] `GET https://www.saltedge.com/api/v6/providers/bbva_es`
- [ ] `GET https://www.saltedge.com/api/v6/providers?country_code=ES&mode=oauth`

Owner re-run date / result: _pending (owner records after re-running the checks)_

## Alternatives matrix (Task 2)

Screened 2026-11-13 from public official documentation only (no accounts created, no sales contact). Deep-dived: **Enable Banking** and **Powens** (the only two with documented BBVA Spain AIS coverage *and* a plausible path for a personal project). The other three were shallow-screened only — reasons in the notes below the matrix. All claims are from the cited pages; anything not verifiable from a public page is labelled **assumption** or **pending owner confirmation**.

| Candidate | BBVA ES AIS | SCA flow | Pricing / personal use | API shape / adapter effort | Own PSD2 licence | Verdict | Sources (URL + date) |
|---|---|---|---|---|---|---|---|
| **Enable Banking** | Yes — BBVA listed among major Spanish ASPSPs; redirect auth, push-notification SCA in BBVA app documented | Redirect (`POST /auth` → auth URL → callback); decoupled SCA supported where the bank offers it | Free restricted-production mode with **no stated time limit**, limited to accounts you link yourself; full production needs contract + KYB (volume-based, minimum monthly invoice — amount not public) | REST, JWT app auth (RSA key, ≤24 h token); `GET /aspsps`, `POST /auth`, sessions, accounts, transactions with `continuation_key` paging; maps cleanly onto `TransactionSource` (connect → auth URL; findConnectionId/isActive → session status; listAccounts/fetchTransactions → endpoints). Moderate adapter effort | No — runs under Enable Banking's licence; own-licence TPP IaaS optional | **Most promising.** Only candidate with documented BBVA ES flow detail *and* a free, self-serve, non-expiring path usable for a personal project (own accounts only). Full production = contract + KYB (pending owner confirmation) | `https://enablebanking.com/docs/markets/es/` — 2026-11-13; `https://enablebanking.com/docs/faq/` — 2026-11-13; `https://enablebanking.com/knowledge-base` — 2026-11-13; `https://enablebanking.com/terms/` — 2026-11-13; `https://enablebanking.com/docs/api/linked-accounts/` — 2026-11-13 |
| **Powens** (ex Budget Insight) | Yes — BBVA named in official Spain coverage posts and bank list (AIS, personal + business) | Hosted connection widget (Webview); user authenticates at bank inside the widget; consent renewal every 180 days | Free **self-service sandbox** (permanent, mock data); production pricing is contact-sales only, no public price list, no stated personal/solo tier | REST + SDKs + connection widget; standardised data model; adapter effort moderate, but widget-centric flow differs from Salt Edge's `connect_url` + callback model (assumption: adaptable to a local CLI via the widget's redirect) | No — Powens is a PSD2-licensed payment institution (ACPR); clients operate under its licence | **Second.** BBVA ES coverage is documented, but production is sales-led with no public pricing and no stated personal-use path — eligibility pending owner confirmation | `https://www.powens.com/blog/products-news-coverage-italy-spain/` — 2026-11-13; `https://www.powens.com/coverage/` — 2026-11-13; `https://www.powens.com/products/transactions/` — 2026-11-13 |
| **GoCardless Bank Account Data** (ex Nordigen) | Historically wide EEA coverage (assumption for BBVA specifically — cannot verify while signups are closed) | Redirect/requisition flow (documented while it was open) | **Closed to new signups** — official page states "New signups for Bank Account Data are currently disabled"; no free tier available to new users | Nordigen-style REST (token → agreement → requisition → accounts/transactions); adapter effort would be low, but moot | No | **Screened out.** Not obtainable for a new project; ADR-0002 rejection re-verified current (see below) | `https://bankaccountdata.gocardless.com/new-signups-disabled` — 2026-11-13 |
| **Tink** | Spain covered (BBVA among connected banks — assumption: exact BBVA ES AIS scope not verifiable without sales contact) | Hosted Tink Link flow (redirect) | No pay-per-use; Standard = "Contact us", Enterprise = custom; free tier is **sandbox only** (permanent, test data); no free production tier | REST + Tink Link; OAuth2 client credentials; adapter effort moderate | No — Standard plan runs under Tink's licence | **Screened out.** Sales-led, no public pricing, no production path for a personal project | `https://tink.com/pricing/` — 2026-11-13; `https://www.tink.com/faq/` — 2026-11-13 |
| **TrueLayer** | Spain covered incl. BBVA (announced 2020) | Hosted auth link (redirect); custom flows only for regulated clients | Free tier is **sandbox only** (permanent, mock data); production explicitly **not available to personal/hobbyist projects** — registered businesses with signed agreement + KYB only | REST Data API v1/v3; JSON `results` arrays; adapter effort moderate | No | **Screened out.** Officially excludes personal projects from production | `https://truelayer.com/newsroom/announcements/spain-launch/` — 2026-11-13; `https://support.truelayer.com/hc/en-us/articles/51026317780881` — 2026-11-13 |

### Notes on shallow-screened candidates

- **GoCardless Bank Account Data**: excluded by availability, not by capability — the official portal blocks new signups, so no account, pricing, or BBVA coverage check is possible for a new project.
- **Tink**: pricing page states listed prices apply only to existing customers and prospects must contact sales; FAQ states there is no pay-per-use option and the free account is sandbox test data only. No self-serve production path exists for a personal project.
- **TrueLayer**: the official help-centre article states production is "not available for personal, hobbyist, or non-commercial projects" even for single-user, own-accounts-only use, with no upgrade path from Sandbox. This is disqualifying on eligibility alone, so pricing/API detail was not pursued.

### ADR-0002 GoCardless/Nordigen rejection — re-check

**Verified current (2026-11-13).** ADR-0002 (2026-08-04) rejected GoCardless/Nordigen because it "stopped onboarding new customers in 2025; its open banking API is now enterprise-only". The official portal page `https://bankaccountdata.gocardless.com/new-signups-disabled` (accessed 2026-11-13) confirms: "New signups for Bank Account Data are currently disabled." The rejection stands; no superseding evidence found.

### Assumptions and pending owner confirmation

- **Assumption**: Powens' hosted widget can be adapted to a local CLI flow (no public doc was read confirming a pure-redirect callback mode for AIS).
- **Assumption**: Tink's BBVA ES AIS scope and GoCardless' historical BBVA coverage — not verifiable from public pages without an account or sales contact.
- **Pending owner confirmation**: Enable Banking full-production contract terms (minimum monthly invoice amount, KYB requirement for a personal/solo project — KYB is company-based per the FAQ); Powens production pricing and whether a personal-use arrangement is possible at all. Neither provider was contacted, per task constraints. Note: restricted production does **not** require a contract/KYB (full production does), so the open KYB item does not block the chosen mode. The Powens/Tink/GoCardless assumptions are historical (those candidates were rejected).
- Enable Banking's free restricted-production tier has **no stated time limit** (the application stays active until an agreement is signed; the ToS grants no permanent vested right to continued use) per its ToS and linked-accounts docs (`https://enablebanking.com/terms/` — 2026-11-13; `https://enablebanking.com/docs/api/linked-accounts/` — 2026-11-13); Powens', Tink's, and TrueLayer's free tiers are permanent **sandboxes** (mock data only, no production access). Salt Edge's Test tier remains the only free *real-data* tier among all options, but it is **time-boxed** (90 days).

## Decision (2026-11-13)

**Outcome B — replacement provider chosen: Enable Banking**, in its free restricted-production mode (personal, non-commercial use; only the accounts the owner links).

Why Enable Banking: it is the only candidate with documented BBVA ES AIS coverage *and* a free path usable by a personal project — free restricted production with no stated time limit, no contract, only own linked accounts, and the BBVA redirect flow + push-notification SCA documented.

Rejected alternatives (one-line reasons, from the Task 2 matrix):

- **Salt Edge** — Live requires a paid written Service Provision Agreement with a legal entity; Test is time-boxed to 90 days; the owner will not pay for any provider.
- **Powens** — sales-led production, no public personal pricing.
- **Tink** — sales-led, no pay-per-use option.
- **TrueLayer** — production explicitly excludes personal/hobbyist projects.
- **GoCardless** — new signups disabled (ADR-0002 rejection re-verified 2026-11-13).

Scope of the chosen mode: **restricted production only** (personal use, own linked accounts). Full production (contract + KYB) is explicitly out of scope.

Preconditions / owner actions (pending): create the Enable Banking account and production application; link own BBVA account(s) to activate restricted mode; confirm the BBVA connect/SCA flow and transaction retrieval work. If that fails, this decision is reopened.

Invariants preserved: the `TransactionSource` seam, AIS-only read consent, no new runtime dependency without owner approval, and test-spreadsheet isolation.

Follow-up: implemented by `plans/2026-11-13_171738-enable-banking-adapter.md` (Tasks 1-2 shipped: `EnableBankingClient`, `ProviderSources` factory, env/state contract, fake-HTTP tests; sandbox `connect`/`fetch`/`sync`-twice validated 2026-11-13; production real-BBVA validation pending); tracked in GitHub issue #3.

## Risks (accepted)

- Restricted mode only exposes the specific accounts the owner links; linking is per-account and once linked, only those accounts are returned.
- ASPSP background-fetch rate limits are bank-specific (commonly about 4/day) and not guaranteed; the CLI must tolerate `ASPSP_RATE_LIMIT_EXCEEDED`.
- Consent/session renewal: session validity is ASPSP-dependent (commonly up to ~180 days), the app JWT lasts at most 24 h, and re-authorisation requires a fresh redirect/SCA flow.
- The free restricted-production terms do not grant a permanent vested right; Enable Banking can change or withdraw the allowance.
- Restricted mode is personal/non-commercial only; it cannot serve other users or public production.
- The Enable Banking app needs an RSA signing key — a new secret type that must live only in the environment, never in the repo.
- Salt Edge code remains in the tree until the adapter lands, so dead/fallback code must be removed or justified then.
- The Salt Edge Test fallback (90 days) would itself require a Pending→Test upgrade and a public callback URL, so it is not a frictionless fallback.

## Non-goals

- No provider code and no changes to `src/**` (the `TransactionSource` seam and the hardcoded Salt Edge wiring stay as they are).
- No dependency change (Gson remains the only runtime dependency).
- No real spreadsheet writes; any future real-provider validation keeps using the disposable test-spreadsheet flow.
- No account creation, terms acceptance, or payment on the owner's behalf.

## Consequences

- The decision is recorded: Enable Banking restricted production is the target provider; real BBVA access is conditionally unblocked: it is not verified until the owner completes the Enable Banking setup, links their BBVA account(s), and confirms the connect/SCA flow and transaction retrieval.
- The Enable Banking adapter and provider factory have landed (`plans/2026-11-13_171738-enable-banking-adapter.md`); Salt Edge remains selectable as a fallback.
- The provider env/state/factory changes are implemented in the follow-up plan; this decision added no dependency.
- Outcome A (Salt Edge enablement) was not chosen: no support ticket was sent, no Test upgrade was requested, and no Live contract will be signed.
- Outcome C (still blocked) did not apply: a viable free path exists via Enable Banking.
- Salt Edge's free Test tier (90 days, real data) remains a fallback only if the Enable Banking path fails.
