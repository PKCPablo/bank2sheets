# ADR-0007: Spanish month tab titles are the Google Sheets persistence key

- Status: accepted
- Date: 2026-11-16

## Context

`sync` previously appended transactions to tabs titled `YYYY-MM` (e.g. `2026-11`). The owner asked for Spanish month names (`Enero 2026`, `Febrero 2026`, ...) and ascending `Fecha` order. The tab title is the user-visible persistence key used to locate a month tab and to dedupe its rows, so changing it is a durable compatibility decision, not only a formatting change.

The plan `docs/ai/plans/2026-11-16_010003-spanish-month-tabs-ascending-dates.md` shipped the change. Existing `YYYY-MM` tabs may already contain history, and each spreadsheet is intended to cover one calendar year.

## Decision

- Canonical month-tab identity is `GoogleSheetsClient.tabTitle(YearMonth)` -> `"<Spanish month> <YYYY>"` (e.g. `Agosto 2026`), from an explicit locale-independent Spanish month list. `YearMonth` remains the internal routing/dedupe key.
- A1 ranges percent-encode the space (`'Agosto%202026'!A1:F`); `'`, `!`, `:` stay literal; no `URLEncoder`.
- Existing `YYYY-MM` tabs are not auto-migrated. The owner renames them manually (e.g. `2026-11` -> `Noviembre 2026`) to preserve history and per-tab dedupe; otherwise a new Spanish-named tab is created and provider history may be re-appended.
- One spreadsheet per calendar year is a manual operational switch: the owner updates `GOOGLE_SPREADSHEET_ID` in `.env` and the Actions secret before the first sync that returns new-year transactions (with the monthly cron, before the February 1 run). There is no automatic multi-spreadsheet routing or new environment variable.
- Each append sorts only the incoming batch by `Fecha` ascending (stable; same-day rows keep provider order). Existing rows are never rewritten, so a late-arriving transaction with an older date lands at the end of the tab (accepted).

## Amendment (2026-11-19): same-day ordering

Enable Banking's API reference documents no ordering guarantee for `GET /accounts/{account_id}/transactions`; the owner observed the list is newest-first (Gate H1, 2026-11-19: `fetch` printed 171 transactions with `madeOn` descending). To make same-date rows read chronologically (oldest movement first), `Main.sync` now reverses each account's fetched list (`List.reversed()`) before month grouping; `GoogleSheetsClient.appendRows` keeps its stable `Fecha`-ascending sort, so same-date rows keep the caller's (reversed) input order. Batch-ascending and append-only behavior are unchanged, and `fetch` still prints the raw provider order.

This amendment supersedes Decision bullet 5's parenthetical "(stable; same-day rows keep provider order)" — same-day rows now keep the caller's input order, which is reverse provider order. Consequences bullet 4 ("Ordering is batch-ascending only") is now incomplete: intra-day order is also defined, but only per batch. Risk: the provider direction is observed, not contractual — if Enable Banking changes ordering direction, same-day order flips and the reversal in `Main.sync` must be revisited. The chronological guarantee remains per-batch only: a late-arriving same-day row still lands at the end of the tab, after existing same-day rows. Original text below is kept as history (ADR-0008 amendment precedent).

## Alternatives considered

- **Keep `YYYY-MM` titles:** rejected by the owner's requested naming.
- **Auto-rename existing tabs:** rejected to keep the change inside the Sheets writer and follow the existing no-auto-migration precedent for the legacy flat tab; manual rename is documented.
- **Read both old and new titles for dedupe:** rejected as extra complexity; manual rename is the migration path.
- **Automatically select a spreadsheet per year:** rejected; the owner chose a manual switch, so no new configuration or routing logic.

## Consequences

- Old `YYYY-MM` tabs are invisible to the new dedupe until renamed; an un-renamed tab can cause provider history to be re-appended into a new Spanish-named tab.
- If the owner misses the year-end spreadsheet switch, new-year tabs are created in the old spreadsheet.
- `tabTitle` is the single point of change for the title contract; future work must not add auto-rename, title fallbacks, or multi-spreadsheet routing without superseding this ADR.
- Ordering is batch-ascending only; globally re-sorting an existing tab is out of scope.

## Validation

- Full suite: 94 tests, 0 failures/errors; JaCoCo 80% line gate met.
- Docs checks: Spanish naming/order and the manual per-year switch are present; no live `YYYY-MM` naming claim in README/architecture.
- The real-spreadsheet smoke test (rename an old `2026-08` tab, confirm dedupe; verify rows 1 -> 30 and `appended 0` on rerun) remains a recommended human validation, not a code gate.

## References

- Plan: `docs/ai/plans/2026-11-16_010003-spanish-month-tabs-ascending-dates.md`
- Invariants: `docs/ai/architecture.md` (month-tab title/order/rename/per-year switch)
- User docs: `README.md` "Google Sheets layout"
