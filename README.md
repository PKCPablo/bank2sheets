# bank2sheets

bank2sheets is a personal Java 21 command-line job that uses Open Banking to fetch transactions from a bank account (BBVA Spain by default, via Enable Banking) and syncs them idempotently to a Google Sheets spreadsheet. It runs on demand, never stores bank credentials, and stays minimal: no frontend, no database.

## Requirements

- Java 21 (JDK) and Maven 3.9.9
- A Google account (for the Cloud project and spreadsheet)
- Online banking access for the ASPSP you link (BBVA Spain by default; you will complete an SCA authorization in the browser)
- Optional: OpenSSL, only if your Enable Banking key is PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) instead of PKCS#8

Windows PowerShell is the documented path; all commands below are PowerShell. For a guided, ordered walkthrough from fork to first sync, see [Set up from scratch](#set-up-from-scratch).

## Set up from scratch

Follow these steps in order. Each step links to the reference section with the full details.

### 1. Fork and clone

Fork `PKCPablo/bank2sheets` on GitHub, then:

```powershell
git clone https://github.com/<your-user>/bank2sheets.git
cd bank2sheets
.\scripts\mvn.ps1 test
```

The test run confirms your toolchain (JDK 21 + Maven) and ends with `BUILD SUCCESS`. See [Build](#build) for details.

### 2. Create the Enable Banking application

1. Sign in at `enablebanking.com/sign-in` (email magic link).
2. Control Panel → API applications → Add a new application.
3. Choose environment **Production**. This project uses the free restricted-production mode: personal, non-commercial use only, and only the accounts you link are reachable.
4. For the key, choose "Generate in the browser and export private key".
5. Fill in an application name and the allowed redirect URL (step 3 below). Production also asks for a description, a data-protection email, a privacy URL, and a terms URL.
6. Register. This downloads `<app-id>.pem` (your private key) and shows the application id you will put in `ENABLE_BANKING_APP_ID`.

> **Sandbox instead?** Enable Banking's sandbox environment uses fake bank data (not real transactions) and accepts the loopback redirect `http://127.0.0.1:8765/bank2sheets/callback`. The rest of this walkthrough is identical.

> **Using another bank?** This walkthrough uses BBVA Spain, the default ASPSP. To use a different bank, link that account in the Enable Banking control panel and set `ENABLE_BANKING_ASPSP_NAME`, `ENABLE_BANKING_ASPSP_COUNTRY`, and `ENABLE_BANKING_PSU_TYPE` (see [Configuration](#configuration)); the name must match the ASPSP name shown in Enable Banking.

### 3. Register a redirect URL the CLI accepts

For a production app, register **an HTTPS URL you control** — any page on a domain you own works. The zero-cost option is GitHub Pages on your fork: Settings → Pages → Deploy from a branch → `master` / `/docs`. Your fork's `docs/callback.html` is then served at `https://<your-user>.github.io/bank2sheets/callback.html`; register that URL.

The page only helps you copy the URL: after the bank redirects you there, it displays the full callback URL, which you paste back into the CLI. The CLI does not call the page.

For sandbox, register `http://127.0.0.1:8765/bank2sheets/callback` (the CLI only accepts `http` for `127.0.0.1`/`localhost` with an explicit port and a non-empty path; `https` works for any host with a non-empty path).

Whatever you register must match `ENABLE_BANKING_REDIRECT_URL` character for character.

### 4. Base64-encode the private key

Open the downloaded `<app-id>.pem` and check the first line:

- `-----BEGIN PRIVATE KEY-----` — already PKCS#8, use it as is.
- `-----BEGIN RSA PRIVATE KEY-----` — convert with OpenSSL:

```powershell
openssl pkcs8 -topk8 -nocrypt -in eb.pem -out eb-pkcs8.pem
```

Then base64-encode the whole PEM file (the CLI strips the header/footer and whitespace itself):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\eb-pkcs8.pem'))
```

Paste the single-line result into `ENABLE_BANKING_PRIVATE_KEY_B64` in the next step.

### 5. Create `.env` in the repository root

Create a file named `.env` in the repository root with these three lines (values single-line and unquoted; base64 `=` padding is fine):

```text
ENABLE_BANKING_APP_ID=<your application id>
ENABLE_BANKING_PRIVATE_KEY_B64=<base64 PKCS#8 PEM from step 4>
ENABLE_BANKING_REDIRECT_URL=<the redirect URL you registered in step 3>
```

Or copy the tracked `.env.example` to `.env` and fill in the values.

`.env` is gitignored and `run.ps1` loads it without printing values. See [Configuration](#configuration) for the full variable table.

### 6. Run `connect` and capture the account id

```powershell
.\scripts\run.ps1 connect
```

Open the printed authorization URL in a browser and complete your bank's SCA authorization. With a loopback redirect the callback is captured automatically (the listener is bound before the URL is printed; 5-minute timeout). With an `https` redirect the CLI prints a paste prompt: copy the full callback URL from your callback page (e.g. `docs/callback.html` served from your Pages site) and paste it.

Expected output ends with something like:

```text
Connected. Connection id ... saved to bank2sheets-state.properties
Available accounts:
<account-id> (<account name>)
Set BANK2SHEETS_ACCOUNTS=enablebanking:<accountId> for `fetch`/`sync`.
```

Set one account id into `.env` as `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>`, optionally with a label: `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>:<label>` (e.g. `...:Cuenta Nóminas`). `connect` works with `BANK2SHEETS_ACCOUNTS` unset, and the session id is persisted locally in `bank2sheets-state.properties` for later `fetch`/`sync` runs.

### 7. Create the Google Sheets side

1. Create a Google Cloud project and enable the Google Sheets API.
2. Create a service account and create/download a JSON key for it.
3. Copy the `client_email` from the JSON.
4. Create or choose a spreadsheet and share it with that `client_email` as **Editor**.
5. Copy the spreadsheet id from its URL (`https://docs.google.com/spreadsheets/d/<spreadsheet-id>/...`).

Then base64-encode the JSON key and add both values to `.env`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\key.json'))
```

```text
GOOGLE_SERVICE_ACCOUNT_B64=<base64 service-account JSON>
GOOGLE_SPREADSHEET_ID=<spreadsheet id>
```

### 8. First sync

```powershell
.\scripts\run.ps1 fetch   # optional: see the transactions as JSON lines
.\scripts\run.ps1 sync
```

The `sync` appends the new rows into one tab per month, titled with the Spanish month name and year from each transaction's date (e.g. `Enero 2026`, `Febrero 2026`; the tab and its header row are created on demand). It is idempotent: a second run reports `appended 0 new row(s)` and skips the duplicates — but it still spends one of the bank's background accesses, so there is no need to run it twice in a row. See [Google Sheets layout](#google-sheets-layout).

### 9. Scheduled sync (optional)

To sync automatically every month via GitHub Actions, see [Scheduled sync (GitHub Actions)](#scheduled-sync-github-actions). Fork-specific facts: enable Actions/workflows in your fork first (GitHub disables scheduled workflows in new forks by default), add the seven Actions secrets, and copy `enablebanking.session_id` from `bank2sheets-state.properties` into the `ENABLE_BANKING_SESSION_ID` secret. Run `workflow_dispatch` once against a disposable sheet, then let the monthly crons (`0 6 1 * *` with the `0 6 2 * *` next-day retry) take over. Note that sessions expire (commonly up to ~180 days) and GitHub disables schedules after 60 days of no repository activity.

### 10. Troubleshooting

| Message | Fix |
| --- | --- |
| `No JDK 21+ found ...` | Install JDK 21+ or set `JAVA_HOME` to a JDK 21+ installation. |
| `JAR not found at ...` | Run `.\scripts\mvn.ps1 package` first. |
| `ENABLE_BANKING_PRIVATE_KEY_B64 is not valid base64` | Re-encode the PEM file (step 4); the value must be a single line. |
| `Cannot sign RS256 JWT (invalid PKCS#8 private key?)` | The key is not PKCS#8; convert it with `openssl pkcs8 -topk8 -nocrypt` (step 4). |
| `connect: connection ... is not active yet` | The bank has not activated the consent yet; wait a moment and run `connect` again. |
| `Timed out after 300 s waiting for the authorization callback` | Complete the SCA within 5 minutes, or run `connect` again. |
| `ENABLE_BANKING_REDIRECT_URL must point at 127.0.0.1 or localhost` | `http` redirects are only allowed for loopback; use an `https` URL for production (step 3). |
| `Authorization state mismatch` | The pasted callback URL belongs to a different `connect` run; run `connect` again and paste the fresh URL. |
| `no accounts configured; set BANK2SHEETS_ACCOUNTS ...` | Add `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>` to `.env` (step 6). |
| `no connection; run \`connect\` once or set ENABLE_BANKING_SESSION_ID` | Run `connect` first, or set `ENABLE_BANKING_SESSION_ID`. |
| Google `read/append failed (HTTP 403)` | Share the spreadsheet with the service account `client_email` as Editor (step 7). |
| `Enable Banking request failed (HTTP 429)` / `ASPSP_RATE_LIMIT_EXCEEDED` / `[HUB046]` | The bank's background-fetch allowance is spent (~4 accesses per 24 h). Wait ~6 hours or until the next day and run `sync` again (the scheduled workflow retries automatically on the 2nd); or re-run `connect` to re-consent. Do not loop retries — each attempt spends the allowance. Tip: configure a label for every account (`enablebanking:<uuid>:<label>`) so `sync`/`fetch` skip the per-account `/details` accesses and spend less of the allowance. Note: with labelled configs the expired-session error surfaces at the first `/accounts/{id}/transactions` call instead of an earlier session check. |
| Scheduled workflow does not run | Enable Actions/workflows in the fork and add the seven secrets (step 9). |

## Build

Use the Maven wrapper, which auto-detects a JDK 21+: it keeps an active JDK 21+ `JAVA_HOME` and otherwise selects a `jdk-21*` installation under `C:\Program Files\Java` (the default `JAVA_HOME` on this machine points to JDK 8, which cannot compile with `maven.compiler.release=21`):

```powershell
.\scripts\mvn.ps1 test
.\scripts\mvn.ps1 package
```

If you prefer to run Maven directly, set the JDK 21 home per command as a fallback (this does not change your global environment):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; mvn test
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; mvn package
```

`mvn package` produces a runnable fat JAR at `target\bank2sheets-0.1.0-SNAPSHOT.jar`. `mvn test` also generates the JaCoCo coverage report at `target/site/jacoco/index.html` and fails if line coverage drops below 80%.

## Run

Run from the repository root so the runtime state file lands next to `.gitignore`. Use the local runner, which loads `.env` (KEY=VALUE, never printed) when present, resolves a JDK 21+ (it keeps an active JDK 21+ `JAVA_HOME`, otherwise selects a `jdk-21*` installation under `C:\Program Files\Java`), and runs the fat JAR with it. For the full ordered setup, see [Set up from scratch](#set-up-from-scratch).

```powershell
.\scripts\run.ps1 help
.\scripts\run.ps1 connect
.\scripts\run.ps1 fetch
.\scripts\run.ps1 sync
```

If the JAR is missing, the runner prints the build instruction (`.\scripts\mvn.ps1 package`) and exits non-zero.

Subcommands:

- `connect` — one-time AIS-only consent. Prints the provider authorization URL; after you complete the configured bank's authorization (SCA) in the browser and press Enter, it stores the session id in `bank2sheets-state.properties` (gitignored). An `http` loopback redirect is captured automatically (the listener is bound before the URL is printed); an `https` redirect (production Pages page) prints a paste prompt after you press Enter. It then prints the available accounts (id + name); copy an id into `BANK2SHEETS_ACCOUNTS` for `fetch`/`sync`. With no `BANK2SHEETS_ACCOUNTS`, `connect` infers the provider from credentials.
- `fetch` — lists the configured accounts and prints their transactions as one JSON object per line.
- `sync` — fetch transactions and append the new ones to Google Sheets; idempotent, so a second run appends nothing
- `help` — show usage
- no command — prints usage and exits 0; an unknown command prints usage to stderr and exits non-zero

## Configuration

Real credentials are provided at runtime via environment variables and are never committed to this repository. Development uses empty config and test doubles.

| Variable | Purpose |
| --- | --- |
| `ENABLE_BANKING_APP_ID` | Enable Banking application id (JWT `kid`) |
| `ENABLE_BANKING_PRIVATE_KEY_B64` | Base64-encoded PKCS#8 PEM private key for the Enable Banking application (secret) |
| `ENABLE_BANKING_REDIRECT_URL` | Registered redirect URL: `http` loopback for sandbox (`http://127.0.0.1:8765/bank2sheets/callback`, explicit port/path) or `https` for production (e.g. `https://pkcpablo.github.io/bank2sheets/callback.html`; the CLI then prompts you to paste the callback URL) |
| `ENABLE_BANKING_SESSION_ID` | Optional; `connect` persists it to `bank2sheets-state.properties` as `enablebanking.session_id` |
| `ENABLE_BANKING_ASPSP_NAME` | Optional ASPSP selection; default `BBVA`. Must match the ASPSP name shown in Enable Banking (trimmed) |
| `ENABLE_BANKING_ASPSP_COUNTRY` | Optional ASPSP country; default `ES` (ISO 3166 alpha-2, uppercased) |
| `ENABLE_BANKING_PSU_TYPE` | Optional PSU type; default `personal` (lowercased) |
| `BANK2SHEETS_ACCOUNTS` | Comma-separated `provider:accountId[:label]` entries; `enablebanking:<uuid>` with an optional trimmed label (may contain spaces or colons, not commas); all entries must use one provider per run; multiple accounts are allowed and each appended row is labeled in the `Cuenta` column. When every entry has a label, `sync`/`fetch` skip the `listAccounts()` session/details accesses |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON (`client_email` + `private_key`) with edit access to the target spreadsheet; required for `sync` |
| `GOOGLE_SPREADSHEET_ID` | Id of the target spreadsheet (shared with the service account email); required for `sync` |

Enable Banking uses the Open Banking Gateway API (`https://api.enablebanking.com`) with an RS256 JWT built from `ENABLE_BANKING_APP_ID` and the private key. It runs in the free restricted-production mode: personal, non-commercial use only, and only the accounts you link in the Enable Banking control panel are accessible. ASPSP selection is configurable via the three optional `ENABLE_BANKING_ASPSP_*`/`ENABLE_BANKING_PSU_TYPE` variables (defaults: BBVA / ES / personal); BBVA Spain remains the validated target, and other ASPSPs are configurable but not owner-validated. ASPSP rate limits apply: the bank allows roughly 4 background accesses per 24 h, and once the allowance is spent `sync`/`fetch` fail with `HTTP 429` / `ASPSP_RATE_LIMIT_EXCEEDED` / `[HUB046]` — wait ~6 hours or until the next day and retry (do not loop retries; each attempt spends the allowance), or re-run `connect` to re-consent. Sessions expire after an ASPSP-dependent validity period (commonly up to ~180 days); when that happens, run `connect` again to re-authorise with a fresh SCA. During `connect`, an `http://127.0.0.1` redirect uses a local loopback listener (bound before the URL is printed, 5-minute timeout). An `https://` redirect (production) uses the static callback page in `docs/callback.html`, served at `https://pkcpablo.github.io/bank2sheets/callback.html`; the CLI prompts you to paste the full callback URL from the browser.

Example (PowerShell):

```powershell
$env:ENABLE_BANKING_APP_ID='...'
$env:ENABLE_BANKING_PRIVATE_KEY_B64='...'   # base64 PKCS#8 PEM
$env:ENABLE_BANKING_REDIRECT_URL='http://127.0.0.1:8765/bank2sheets/callback'   # sandbox; use the HTTPS Pages URL for production
$env:BANK2SHEETS_ACCOUNTS='enablebanking:ACCOUNT_UUID'
$env:GOOGLE_SERVICE_ACCOUNT_B64='...'
$env:GOOGLE_SPREADSHEET_ID='...'
.\scripts\run.ps1 connect
.\scripts\run.ps1 fetch
.\scripts\run.ps1 sync
```

The runtime state file `bank2sheets-state.properties` holds only the provider id (`enablebanking.session_id`; no credentials, no bank tokens) and is listed in `.gitignore`. For a step-by-step setup using a `.env` file instead of `$env:` variables, see [Set up from scratch](#set-up-from-scratch).

## Scheduled sync (GitHub Actions)

`.github/workflows/sync.yml` builds the fat JAR and runs `sync` monthly on the 1st at 06:00 UTC (`0 6 1 * *`) and again on the 2nd at 06:00 UTC (`0 6 2 * *`) as an automatic next-day retry: if the 1st run hit the bank's allowance (`HTTP 429` / `ASPSP_RATE_LIMIT_EXCEEDED`), the 2nd run succeeds once the allowance resets; when the 1st already appended everything, the 2nd appends 0 rows (`appended 0 new row(s)`) but still spends one bank access. Manual dispatch (`workflow_dispatch`) is also available. The runner is ephemeral, so the workflow never runs `connect`; it uses the session id from the `ENABLE_BANKING_SESSION_ID` secret. The workflow runs in any repository that enables Actions and configures the secrets below — including forks, but a fork must enable Actions/workflows first because GitHub disables scheduled workflows in new forks by default.

Required repository secrets (Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
| --- | --- |
| `ENABLE_BANKING_APP_ID` | Enable Banking application id |
| `ENABLE_BANKING_PRIVATE_KEY_B64` | Base64-encoded PKCS#8 PEM private key |
| `ENABLE_BANKING_REDIRECT_URL` | Registered redirect URL (production HTTPS Pages URL) |
| `ENABLE_BANKING_SESSION_ID` | Session id from local `bank2sheets-state.properties` (`enablebanking.session_id`) |
| `BANK2SHEETS_ACCOUNTS` | e.g. `enablebanking:<uuid>:Cuenta Nóminas` (the label is optional; with a label on every entry, `sync` skips the per-account `/details` accesses) |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON |
| `GOOGLE_SPREADSHEET_ID` | Target spreadsheet id |

Caveats:

- The session id expires after an ASPSP-dependent validity period (commonly up to ~180 days) or when you re-run `connect` locally; when that happens, copy the fresh `enablebanking.session_id` from `bank2sheets-state.properties` into the `ENABLE_BANKING_SESSION_ID` secret.
- For the first manual run, point `GOOGLE_SPREADSHEET_ID` at the disposable test sheet; switch the secret to the real spreadsheet after verifying the appended rows.
- GitHub disables scheduled workflows on public repos after 60 days of no repository activity; a manual `workflow_dispatch` run or any repo activity re-enables the schedule. With a monthly cadence, a silently disabled workflow can go unnoticed for up to a month (see issue #6).

## Google Sheets layout

`sync` writes one tab per month, titled with the Spanish month name and year from each transaction's date (e.g. `Enero 2026`, `Febrero 2026`), and creates the tab and its header row on demand. A missing month tab is created as a full copy of the spreadsheet's first sheet when that first sheet is a dedicated template (any tab that is neither a month tab nor the hidden `_sync` ledger): values, formulas, formats, column widths, frozen rows, and validations are duplicated, and the new tab is inserted at the end so the template stays first; the canonical header is not re-appended because the copied `A1:D1` already matches. When the first sheet is itself a month tab or the `_sync` ledger, the tab falls back to a bare tab and the canonical header row is written on demand. Every tab has the same four columns: `Fecha`, `Movimientos`, `Descripción`, `Cuenta`. `Fecha` is written as a date-typed cell: the ISO date (`yyyy-MM-dd`) is sent with `valueInputOption=USER_ENTERED`, so Sheets parses it into a real date whose display follows the spreadsheet locale (e.g. `dd/mm/aaaa`); no number format or locale is set in code. `Descripción` shows the transaction concept: when the provider text contains `//` segments (e.g. `TRANSFERENCIAS // TRANSFERENCIA REALIZADA // GYM`), only the last non-blank trimmed segment is written (`GYM`); text without `//` is unchanged; an all-blank split falls back to the full text. The `fetch` JSON and the sheet use the same value, and dedupe ids are unaffected (they hash the raw provider description). `Descripción` and `Cuenta` are sent with a leading apostrophe (the literal-text marker, not displayed), so formula-like or number-like strings stay literal text. The header row and the hidden `_sync` ledger are still appended with `valueInputOption=RAW`. Rows within each append are written in ascending date order, and rows that share the same date read chronologically (oldest movement first) — `sync` reverses the provider's newest-first list before grouping, so same-date rows are written oldest→newest; the `fetch` command keeps printing the raw provider order. Existing rows are never rewritten, so a late-arriving transaction with an older date lands at the end of the tab. Amounts (e.g. `1200.50`) stay numeric cells. Rows written by older versions keep their `DD/MM/YYYY` text dates; in a tab that already carries the `Movimientos` header, mixed tabs are accepted and no migration runs. Tabs from pre-rename versions keep the old `Importe` header, are rejected by the header guard, and require the reset described below. Dedupe is exact on the provider `TransactionId`, recorded in a hidden `_sync` helper tab (columns `Tab`, `TransactionId`) rather than in the visible rows: running `sync` twice appends no duplicates.

**One-time reset before the first sync of this version:** existing month tabs from older versions keep their legacy headers and are not migrated. Delete or archive every existing Spanish month tab **and** any `_sync` tab (or point `GOOGLE_SPREADSHEET_ID` at a fresh spreadsheet) before the first `sync`. If a legacy tab is left in place, `sync` fails fast with an error telling you to do this reset — no rows are appended to it. Note that a first sheet with a non-canonical header (e.g. the legacy flat six-column tab) becomes the template that new month tabs are copied from, and its copies are rejected by the header guard — so delete/archive it or make it match `Fecha | Movimientos | Descripción | Cuenta`. The reset is safe: the provider history is fully re-fetched and re-appended on the next sync. Do not delete only the month tabs while keeping `_sync` — the ledger would then silently skip every row as an already-recorded duplicate.

One spreadsheet per year is a manual switch, not a configuration option: before the first sync that returns new-year transactions (with the monthly cron, before the February 1 run), update `GOOGLE_SPREADSHEET_ID` in `.env` and in the Actions secret. If you switch late, the new-year tabs are created in the old spreadsheet.

`Cuenta` holds the source account label: the configured label when the entry has one, else the provider account display name when non-blank, otherwise the provider account id. `BANK2SHEETS_ACCOUNTS` may list multiple accounts; all rows go to the same spreadsheet, into the month tab derived from each transaction's date, and dedupe stays exact on `TransactionId` via the hidden `_sync` ledger.

A spreadsheet that still has the legacy flat first tab (the pre-month-tabs sheet) is left untouched: it is not renamed, migrated, or deleted. Its rows are not dedupe keys for the month tabs, so the first upgraded `sync` may re-append whatever history the provider still returns into the month tabs. If you do not want that, delete or archive the legacy tab manually before the first upgraded `sync`.

On each `sync`, after appending rows, every month tab whose month is over (tab title parses to a Spanish month + year strictly before the run month) is protected as a whole sheet with the warning-only mode — the equivalent of the Google Sheets "Permisos de edición de intervalos" dialog with "Mostrar una advertencia al editar este intervalo". The current month is never protected, and warning-only protection does not block the service account from appending late transactions to a closed month either. Warning-only protection does not block any editor's writes: to edit a closed month, confirm the warning in the UI (or remove the protection in the Sheets UI). A tab that already has any protection is skipped, so repeated syncs never add duplicate protections and owner-managed protections are respected. Each successful run reports the result in its summary line: `... skipped K duplicate(s), protected P closed month tab(s)`.

One bank account per spreadsheet is a deployment pattern, not a configuration option: run one instance (or fork) per account with its own `BANK2SHEETS_ACCOUNTS`, `GOOGLE_SPREADSHEET_ID`, and its own scheduled workflow and secrets. There is no account-to-spreadsheet mapping variable.

## License

Released under the [MIT License](LICENSE).

Copyright (c) 2026 PKCPablo.
