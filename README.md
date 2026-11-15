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

Copy one account id into `.env` as `BANK2SHEETS_ACCOUNTS=enablebanking:<uuid>`. `connect` works with `BANK2SHEETS_ACCOUNTS` unset, and the session id is persisted locally in `bank2sheets-state.properties` for later `fetch`/`sync` runs.

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
.\scripts\run.ps1 sync
```

The first `sync` appends the new rows (writing the header row if the sheet is empty); the second reports `appended 0 new row(s)` and skips the duplicates. See [Google Sheets layout](#google-sheets-layout).

### 9. Scheduled sync (optional)

To sync automatically every month via GitHub Actions, see [Scheduled sync (GitHub Actions)](#scheduled-sync-github-actions). Fork-specific facts: enable Actions/workflows in your fork first (GitHub disables scheduled workflows in new forks by default), add the seven Actions secrets, and copy `enablebanking.session_id` from `bank2sheets-state.properties` into the `ENABLE_BANKING_SESSION_ID` secret. Run `workflow_dispatch` once against a disposable sheet, then let the monthly cron (`0 6 1 * *`) take over. Note that sessions expire (commonly up to ~180 days) and GitHub disables schedules after 60 days of no repository activity.

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
| `BANK2SHEETS_ACCOUNTS` | Comma-separated `provider:accountId` entries; `enablebanking:<uuid>`; all entries must use one provider per run; multiple accounts are allowed and each appended row is labeled in the `Cuenta` column |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON (`client_email` + `private_key`) with edit access to the target spreadsheet; required for `sync` |
| `GOOGLE_SPREADSHEET_ID` | Id of the target spreadsheet (shared with the service account email); required for `sync` |

Enable Banking uses the Open Banking Gateway API (`https://api.enablebanking.com`) with an RS256 JWT built from `ENABLE_BANKING_APP_ID` and the private key. It runs in the free restricted-production mode: personal, non-commercial use only, and only the accounts you link in the Enable Banking control panel are accessible. ASPSP selection is configurable via the three optional `ENABLE_BANKING_ASPSP_*`/`ENABLE_BANKING_PSU_TYPE` variables (defaults: BBVA / ES / personal); BBVA Spain remains the validated target, and other ASPSPs are configurable but not owner-validated. ASPSP rate limits apply (bank-specific, commonly a few background fetches per day) and sessions expire after an ASPSP-dependent validity period (commonly up to ~180 days); when that happens, run `connect` again to re-authorise with a fresh SCA. During `connect`, an `http://127.0.0.1` redirect uses a local loopback listener (bound before the URL is printed, 5-minute timeout). An `https://` redirect (production) uses the static callback page in `docs/callback.html`, served at `https://pkcpablo.github.io/bank2sheets/callback.html`; the CLI prompts you to paste the full callback URL from the browser.

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

`.github/workflows/sync.yml` builds the fat JAR and runs `sync` monthly on the 1st at 06:00 UTC (`0 6 1 * *`) and on manual dispatch (`workflow_dispatch`). The runner is ephemeral, so the workflow never runs `connect`; it uses the session id from the `ENABLE_BANKING_SESSION_ID` secret. The workflow runs in any repository that enables Actions and configures the secrets below — including forks, but a fork must enable Actions/workflows first because GitHub disables scheduled workflows in new forks by default.

Required repository secrets (Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
| --- | --- |
| `ENABLE_BANKING_APP_ID` | Enable Banking application id |
| `ENABLE_BANKING_PRIVATE_KEY_B64` | Base64-encoded PKCS#8 PEM private key |
| `ENABLE_BANKING_REDIRECT_URL` | Registered redirect URL (production HTTPS Pages URL) |
| `ENABLE_BANKING_SESSION_ID` | Session id from local `bank2sheets-state.properties` (`enablebanking.session_id`) |
| `BANK2SHEETS_ACCOUNTS` | e.g. `enablebanking:<uuid>` |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON |
| `GOOGLE_SPREADSHEET_ID` | Target spreadsheet id |

Caveats:

- The session id expires after an ASPSP-dependent validity period (commonly up to ~180 days) or when you re-run `connect` locally; when that happens, copy the fresh `enablebanking.session_id` from `bank2sheets-state.properties` into the `ENABLE_BANKING_SESSION_ID` secret.
- For the first manual run, point `GOOGLE_SPREADSHEET_ID` at the disposable test sheet; switch the secret to the real spreadsheet after verifying the appended rows.
- GitHub disables scheduled workflows on public repos after 60 days of no repository activity; a manual `workflow_dispatch` run or any repo activity re-enables the schedule. With a monthly cadence, a silently disabled workflow can go unnoticed for up to a month (see issue #6).

## Google Sheets layout

`sync` targets the first sheet with columns `Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`, `Cuenta`. Rows are appended with `valueInputOption=RAW`, so amounts (e.g. `1200.50`) stay numeric cells instead of text or formulas. The header row is written once when missing. TransactionId is the dedupe key: running `sync` twice appends no duplicates.

`Cuenta` holds the source account label: the provider account display name when non-blank, otherwise the provider account id. `BANK2SHEETS_ACCOUNTS` may list multiple accounts; all rows go to the same first sheet tab, and dedupe stays global on `TransactionId`. A spreadsheet that still has the old five-column header gets `F1 = "Cuenta"` backfilled on the next sync that appends rows; historical rows keep the column blank.

One bank account per spreadsheet is a deployment pattern, not a configuration option: run one instance (or fork) per account with its own `BANK2SHEETS_ACCOUNTS`, `GOOGLE_SPREADSHEET_ID`, and its own scheduled workflow and secrets. There is no account-to-spreadsheet mapping variable.

## License

Released under the [MIT License](LICENSE).

Copyright (c) 2026 PKCPablo.
