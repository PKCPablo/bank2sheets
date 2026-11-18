# ADR-0009: Warning-only whole-sheet protection for closed month tabs

- Status: accepted
- Date: 2026-11-18

## Context

Month tabs accumulate rows over time and the owner occasionally edits the spreadsheet by hand; an accidental edit to a completed month (e.g. `Enero 2026`) would silently corrupt the ledger, since the writer is append-only and never rewrites existing rows. Strict protection (blocking writes with an editor list) would require sending the service account's email in `editors` and would block the service account's own late-transaction appends unless protections were removed and re-added around each sync. The plan `docs/ai/plans/2026-11-17_211424-protect-closed-month-tabs.md` shipped the change (Tasks 1-2: commits `a1a7bc8`, `86d3983`).

## Decision

- On each `sync`, after appends, every **closed month tab** is protected as a **whole sheet** (`range: {"sheetId": N}`, no row/column bounds) with **warning-only** mode (`warningOnly: true`, description `Mes cerrado: aviso antes de editar`). This is the API equivalent of the Google Sheets "Permisos de edición de intervalos" dialog with "Mostrar una advertencia al editar este intervalo": every editor (including the service account) can still write; the UI asks for confirmation. No editor list is sent (`editors` is ignored by the API when `warningOnly` is true).
- **Month completed** = the tab title parses as a Spanish month name + four-digit year (`GoogleSheetsClient.parseTabTitle`) and its `YearMonth` is strictly before the run month. The current and future months are never protected; non-month tabs (`_sync`, the legacy flat tab) never match the parser and are never protected.
- **Idempotency:** a sheet that already has any `protectedRanges` entry is skipped, so repeated syncs never add duplicate protections and owner-managed protections take precedence.
- **Late transactions are allowed:** appends are written before the protection pass in the same sync, and warning-only protection never blocks API writes by an editor. This does not alter ADR-0008's append-only cell rule — the protection pass writes sheet metadata (`addProtectedRange`), not cell values.
- **Failure policy:** a protection HTTP failure propagates through `requireOk` and fails the sync (exit 1), consistent with metadata/read/append failures. Accepted trade-off: the next scheduled retry spends another bank access.
- **Trigger:** only `sync`. No new CLI command, flag, env var, or per-account configuration. The `sync` summary line now prints `... skipped K duplicate(s), protected P closed month tab(s)`.

## Alternatives considered

- **Strict protection with an editor list** (blocking writes, service account whitelisted): rejected — it would block the owner's own edits entirely (Google always lets the owner edit, but other editors would be blocked) and risks locking the service account out of late appends; the issue's threat model is accidental edits, not malicious ones.
- **Unprotect → append → reprotect** around each sync: rejected — extra API round-trips, a window where the tab is unprotected, and a failure mid-sequence can leave the tab unprotected.
- **Warn-and-continue on protection failure** (log and exit 0): rejected — inconsistent with the fail-fast error handling of every other Sheets call; a silently skipped protection pass would be invisible.

## Consequences

- Closed month tabs show a confirmation dialog on manual edits instead of being silently editable; accidental edits are interrupted, not prevented.
- One extra narrowly scoped `spreadsheets.get` per `sync` (small; Sheets quota is not a concern).
- Any pre-existing protected range on a month tab makes that tab skip the whole-sheet protection; owner-managed protections win.
- A protection failure fails the whole sync after rows were appended; the scheduled retry may spend another bank access. Accepted for consistency with existing Sheets error handling.
- Spreadsheets/accounts that never run `sync` again are never retrofitted with protection.

## Validation

- Full suite green with the JaCoCo 80% line gate (docs-only task; Tasks 1-2 carried the code tests).
- Real-spreadsheet verification (owner, pending): a closed month tab shows the "Permisos de edición de intervalos" dialog with "Mostrar una advertencia al editar este intervalo", the sidebar shows the tab as protected, the current month is not protected, and a second `sync` adds no duplicate protected ranges.

## References

- Plan: `docs/ai/plans/2026-11-17_211424-protect-closed-month-tabs.md`
- Invariants: `docs/ai/architecture.md` (protection-pass bullet)
- Related: `docs/ai/decisions/ADR-0008-four-column-layout-hidden-dedupe-ledger.md` (append-only writer, unaffected)
- User docs: `README.md` "Google Sheets layout"
