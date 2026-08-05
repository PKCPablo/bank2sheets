# ADR-0003: No formal hexagonal architecture; informal ports-and-adapters seams suffice

- Status: accepted
- Date: 2026-08-05

## Context

`bank2sheets` is a small personal CLI (about 15 main classes, 3 commands) with two external integrations (Salt Edge Open Banking, Google Sheets) and one planned provider addition (Powens for Trade Republic). During a review of the current implementation, the question arose whether to adopt a standardized architecture such as hexagonal (ports & adapters) to improve flexibility and testability.

The code already contains the seams hexagonal architecture mandates, without the ceremony:

- Outbound port for bank access: `TransactionSource` interface (isolates the provider; adding Powens = new implementation, no pipeline changes).
- Infrastructure seam: `HttpExchange` + `RealHttpExchange` (tests inject in-memory fakes; no HTTP-stubbing framework needed).
- Sheets adapter: `GoogleSheetsClient` created via factory, decoupled from the rest of the pipeline.
- Composition root: `Main` wires everything via constructor injection.
- Plain domain records (`Transaction`, `Account`) with no infrastructure dependencies.

A formal hexagonal refactor would add a `domain`/`application`/`infrastructure` package split, use-case/application-service classes that today would be one-line wrappers over `Main`, and extra indirection — speculative abstraction the repository explicitly avoids (ponytail/reuse-first discipline in `AGENTS.md` and `docs/ai/architecture.md`).

## Decision

- Do **not** adopt a formal hexagonal (or other standardized) architecture now.
- Keep the existing informal seams (`TransactionSource`, `HttpExchange`, factory-created `GoogleSheetsClient`, `Main` as composition root) as the deliberate isolation strategy.
- Evolve incrementally only when concrete pain appears:
  - Second provider (Powens): add a provider-selection factory keyed by the `provider:` value in `BANK2SHEETS_ACCOUNTS` (small step, not a rewrite).
  - Pipeline growth (categorization/statistics/exports): extract a `SyncTransactions`-style use case out of `Main` at that point.
- Revisit this decision if the application grows beyond a personal CLI (for example, new consumers of the same domain logic, a web UI, or multiple independent run modes).

## Consequences

- No refactor cost now; the current architecture stays readable for a 3-command CLI.
- The provider-change scenario that motivated the question remains cheap (new `TransactionSource` implementation plus a factory entry).
- `docs/ai/architecture.md` documents the seams as deliberate, so future agents do not re-litigate this.
- Testability is already achieved via fakes at the `HttpExchange` seam; no framework additions.
