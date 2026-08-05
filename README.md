# bank2sheets

bank2sheets is a personal Java 21 command-line job that uses Open Banking to fetch transactions from a bank account (BBVA via Salt Edge) and syncs them idempotently to a Google Sheets spreadsheet. It runs on demand, never stores bank credentials, and stays minimal: no frontend, no database.

## Requirements

- Java 21 (JDK 21.0.6 is installed at `C:\Program Files\Java\jdk-21.0.6`)
- Maven 3.9.9

## Build

The default `JAVA_HOME` on this machine points to JDK 8, which cannot compile with `maven.compiler.release=21`. Run every Maven command with the JDK 21 home set per command (this does not change your global environment):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.6'; mvn test
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.6'; mvn package
```

`mvn package` produces a runnable fat JAR at `target\bank2sheets-0.1.0-SNAPSHOT.jar`.

## Run

Run from the repository root so the runtime state file lands next to `.gitignore`:

```powershell
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar help
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar connect
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar fetch
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar sync
```

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
| `SALTEDGE_APP_ID` | Salt Edge API key id (`App-id` header) |
| `SALTEDGE_SECRET` | Salt Edge API key secret (`Secret` header) |
| `SALTEDGE_CUSTOMER_ID` | Salt Edge customer that owns the connection (create one in the Salt Edge dashboard, or via `POST /api/v5/customers`) |
| `SALTEDGE_CONNECTION_ID` | Optional; `connect` persists it to `bank2sheets-state.properties` and `fetch` reads it from there |
| `BANK2SHEETS_ACCOUNTS` | Comma-separated `provider:accountId` entries to fetch, e.g. `saltedge:ACCT_ID` |
| `GOOGLE_SERVICE_ACCOUNT_B64` | Base64-encoded Google service account JSON (`client_email` + `private_key`) with edit access to the target spreadsheet; required for `sync` |
| `GOOGLE_SPREADSHEET_ID` | Id of the target spreadsheet (shared with the service account email); required for `sync` |

Example (PowerShell):

```powershell
$env:SALTEDGE_APP_ID='...'
$env:SALTEDGE_SECRET='...'
$env:SALTEDGE_CUSTOMER_ID='...'
$env:BANK2SHEETS_ACCOUNTS='saltedge:ACCT_ID'
$env:GOOGLE_SERVICE_ACCOUNT_B64='...'
$env:GOOGLE_SPREADSHEET_ID='...'
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar connect
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar fetch
java -jar target\bank2sheets-0.1.0-SNAPSHOT.jar sync
```

The runtime state file `bank2sheets-state.properties` holds only the Salt Edge connection id (no credentials, no bank tokens) and is listed in `.gitignore`.

## Google Sheets layout

`sync` targets the first sheet with columns `Fecha`, `Descripción`, `Importe`, `Moneda`, `TransactionId`. Rows are appended with `valueInputOption=RAW`, so amounts (e.g. `1200.50`) stay numeric cells instead of text or formulas. The header row is written once when missing. TransactionId is the dedupe key: running `sync` twice appends no duplicates.
