# bank2sheets

bank2sheets is a personal Java 21 command-line job that uses Open Banking to fetch transactions from a bank account (BBVA via Salt Edge) and syncs them idempotently to a Google Sheets spreadsheet. It runs on demand, never stores bank credentials, and stays minimal: no frontend, no database.

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

Run from the repository root so the runtime state file lands next to `.gitignore`. Use the local runner, which loads `.env` (KEY=VALUE, never printed) when present and then runs the fat JAR with the `java` on your PATH:

```powershell
.\scripts\run.ps1 help
.\scripts\run.ps1 connect
.\scripts\run.ps1 fetch
.\scripts\run.ps1 sync
```

If the JAR is missing, the runner prints the build instruction (`.\scripts\mvn.ps1 package`) and exits non-zero.

Subcommands:

- `connect` — one-time AIS-only consent. Prints the Salt Edge authorization URL; after you complete the BBVA authorization (SCA) in the browser and press Enter, it stores the connection id in `bank2sheets-state.properties` (gitignored).
- `fetch` — lists the configured accounts and prints their transactions as one JSON object per line.
- `sync` — fetch transactions and append the new ones to Google Sheets; idempotent, so a second run appends nothing
- `help` — show usage
- no command — prints usage and exits 0; an unknown command prints usage to stderr and exits non-zero

## Configuration

Real credentials are provided at runtime via environment variables and are never committed to this repository. Development uses empty config and test doubles.

| Variable | Purpose |
| --- | --- |
| `SALTEDGE_APP_ID` | Salt Edge **Service** API key id (`App-id` header) |
| `SALTEDGE_SECRET` | Salt Edge **Service** API key secret (`Secret` header) |
| `SALTEDGE_CUSTOMER_ID` | Salt Edge customer that owns the connection (create one in the Salt Edge dashboard, or via `POST /api/v6/customers`) |
| `SALTEDGE_CONNECTION_ID` | Optional; `connect` persists it to `bank2sheets-state.properties` and `fetch` reads it from there |
| `BANK2SHEETS_ACCOUNTS` | Comma-separated `provider:accountId` entries to fetch, e.g. `saltedge:ACCT_ID` |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON (`client_email` + `private_key`) with edit access to the target spreadsheet; required for `sync` |
| `GOOGLE_SPREADSHEET_ID` | Id of the target spreadsheet (shared with the service account email); required for `sync` |

Salt Edge uses the **V6 API** (`https://www.saltedge.com/api/v6`). The credentials must be **Service API keys** created in the Salt Edge dashboard (API Keys page, type Service); App API keys don't work in V6. The `App-id`/`Secret` headers and the environment variable names are unchanged.

Example (PowerShell):

```powershell
$env:SALTEDGE_APP_ID='...'
$env:SALTEDGE_SECRET='...'
$env:SALTEDGE_CUSTOMER_ID='...'
$env:BANK2SHEETS_ACCOUNTS='saltedge:ACCT_ID'
$env:GOOGLE_SERVICE_ACCOUNT_B64='...'
$env:GOOGLE_SPREADSHEET_ID='...'
.\scripts\run.ps1 connect
.\scripts\run.ps1 fetch
.\scripts\run.ps1 sync
```

The runtime state file `bank2sheets-state.properties` holds only the Salt Edge connection id (no credentials, no bank tokens) and is listed in `.gitignore`.

## Google Sheets layout

`sync` targets the first sheet with columns `Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`. Rows are appended with `valueInputOption=RAW`, so amounts (e.g. `1200.50`) stay numeric cells instead of text or formulas. The header row is written once when missing. TransactionId is the dedupe key: running `sync` twice appends no duplicates.
