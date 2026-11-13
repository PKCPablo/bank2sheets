# bank2sheets

bank2sheets is a personal Java 21 command-line job that uses Open Banking to fetch transactions from a bank account (BBVA via Enable Banking, with Salt Edge kept as a selectable fallback) and syncs them idempotently to a Google Sheets spreadsheet. It runs on demand, never stores bank credentials, and stays minimal: no frontend, no database.

## Requirements

- Java 21 (JDK 21 is installed at `C:\Program Files\Java\jdk-21`)
- Maven 3.9.9

## Build

Use the Maven wrapper, which auto-detects a JDK 21+: it keeps an active JDK 21+ `JAVA_HOME` and otherwise selects the highest `jdk-21*` under `C:\Program Files\Java` (the default `JAVA_HOME` on this machine points to JDK 8, which cannot compile with `maven.compiler.release=21`):

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

Run from the repository root so the runtime state file lands next to `.gitignore`. Use the local runner, which loads `.env` (KEY=VALUE, never printed) when present, resolves a JDK 21+ (it keeps an active JDK 21+ `JAVA_HOME`, otherwise selects the highest `jdk-21*` under `C:\Program Files\Java`), and runs the fat JAR with it:

```powershell
.\scripts\run.ps1 help
.\scripts\run.ps1 connect
.\scripts\run.ps1 fetch
.\scripts\run.ps1 sync
```

If the JAR is missing, the runner prints the build instruction (`.\scripts\mvn.ps1 package`) and exits non-zero.

Subcommands:

- `connect` — one-time AIS-only consent. Prints the provider authorization URL (Enable Banking or Salt Edge); after you complete the BBVA authorization (SCA) in the browser and press Enter, it stores the connection/session id in `bank2sheets-state.properties` (gitignored). For Enable Banking, an `http` loopback redirect is captured automatically (the listener is bound before the URL is printed); an `https` redirect (production Pages page) prints a paste prompt after you press Enter. It then prints the available accounts (id + name); copy an id into `BANK2SHEETS_ACCOUNTS` for `fetch`/`sync`. With no `BANK2SHEETS_ACCOUNTS`, `connect` infers the provider from credentials (Enable Banking first).
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
| `SALTEDGE_APP_ID` | Salt Edge **Service** API key id (`App-id` header) — legacy/fallback provider |
| `SALTEDGE_SECRET` | Salt Edge **Service** API key secret (`Secret` header) — legacy/fallback provider |
| `SALTEDGE_CUSTOMER_ID` | Salt Edge customer that owns the connection (create one in the Salt Edge dashboard, or via `POST /api/v6/customers`) |
| `SALTEDGE_CONNECTION_ID` | Optional; `connect` persists it as `saltedge.connection_id` and `fetch` reads it from there |
| `BANK2SHEETS_ACCOUNTS` | Comma-separated `provider:accountId` entries; `enablebanking:<uuid>` or `saltedge:ACCT_ID`; all entries must use one provider per run |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON (`client_email` + `private_key`) with edit access to the target spreadsheet; required for `sync` |
| `GOOGLE_SPREADSHEET_ID` | Id of the target spreadsheet (shared with the service account email); required for `sync` |

Enable Banking uses the Open Banking Gateway API (`https://api.enablebanking.com`) with an RS256 JWT built from `ENABLE_BANKING_APP_ID` and the private key. It runs in the free restricted-production mode: personal, non-commercial use only, and only the accounts you link in the Enable Banking control panel are accessible. ASPSP rate limits apply (bank-specific, commonly a few background fetches per day) and sessions expire after an ASPSP-dependent validity period (commonly up to ~180 days); when that happens, run `connect` again to re-authorise with a fresh SCA. During `connect`, an `http://127.0.0.1` redirect uses a local loopback listener (bound before the URL is printed, 5-minute timeout). An `https://` redirect (production) uses the static callback page in `docs/callback.html`, served at `https://pkcpablo.github.io/bank2sheets/callback.html`; the CLI prompts you to paste the full callback URL from the browser. Salt Edge remains selectable as a fallback: it uses the **V6 API** (`https://www.saltedge.com/api/v6`) and its credentials must be **Service API keys** created in the Salt Edge dashboard (API Keys page, type Service); App API keys don't work in V6. The `App-id`/`Secret` headers and environment variable names are unchanged.

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

For the Salt Edge fallback, set `SALTEDGE_APP_ID`, `SALTEDGE_SECRET`, `SALTEDGE_CUSTOMER_ID` and `BANK2SHEETS_ACCOUNTS='saltedge:ACCT_ID'` instead.

The runtime state file `bank2sheets-state.properties` holds only the provider id (`enablebanking.session_id` or `saltedge.connection_id`; no credentials, no bank tokens) and is listed in `.gitignore`.

## Google Sheets layout

`sync` targets the first sheet with columns `Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`. Rows are appended with `valueInputOption=RAW`, so amounts (e.g. `1200.50`) stay numeric cells instead of text or formulas. The header row is written once when missing. TransactionId is the dedupe key: running `sync` twice appends no duplicates.
