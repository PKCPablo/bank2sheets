# scripts/run.ps1
# Local runner: load the repo-root .env (never printing values), then run the fat JAR.

param(
  [Parameter(Position = 0)]
  [string]$Command,
  [string]$JarPath = 'target\bank2sheets-0.1.0-SNAPSHOT.jar'
)

$appArgs = @()
if ($Command) { $appArgs += $Command }

if (-not (Test-Path -LiteralPath $JarPath)) {
    Write-Host "JAR not found at '$JarPath'. Build it first:" -ForegroundColor Yellow
    Write-Host '.\scripts\mvn.ps1 package'
    exit 1
}

# Load .env (KEY=VALUE, -split '=',2 keeps base64 padding); skip blanks and comments.
$envFile = Join-Path $PSScriptRoot '..\.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $parts = $line -split '=', 2
            if ($parts.Count -eq 2) {
                Set-Item -Path ("Env:" + $parts[0].Trim()) -Value $parts[1]
            }
        }
    }
}

java -jar $JarPath @appArgs
exit $LASTEXITCODE
