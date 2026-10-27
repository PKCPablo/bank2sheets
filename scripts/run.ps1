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

# Resolve a JDK 21+ (the machine default JAVA_HOME is JDK 8 and java is not on PATH).
# ponytail: JDK discovery duplicated from scripts/mvn.ps1 (issue #2 scoped run.ps1 only);
# extract scripts/jdk.ps1 if a third caller appears or mvn.ps1 is touched again.
$jdkRoot = 'C:\Program Files\Java'

function Get-JavaExeMajorVersion {
    param([string]$JavaExe)

    if ([string]::IsNullOrWhiteSpace($JavaExe) -or -not (Test-Path -LiteralPath $JavaExe)) {
        return $null
    }

    $versionLine = & $JavaExe -version 2>&1 | Select-Object -First 1
    if ($versionLine -match 'version "(\d+)') {
        return [int]$matches[1]
    }

    return $null
}

function Get-JdkNameVersion {
    param([string]$Name)

    $raw = $Name -replace '^jdk-', ''
    if ($raw -notmatch '\.') {
        $raw = "$raw.0"
    }
    if ($raw -match '^\d+(\.\d+)+$') {
        return [version]$raw
    }

    return [version]'0.0'
}

function Resolve-Java21Exe {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHomeExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if ((Get-JavaExeMajorVersion $javaHomeExe) -ge 21) {
            return $javaHomeExe
        }
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $jdkRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Sort-Object -Property @{ Expression = { Get-JdkNameVersion $_.Name }; Descending = $true }, Name -Descending
    )

    foreach ($candidate in $candidates) {
        $candidateExe = Join-Path $candidate.FullName 'bin\java.exe'
        if ((Get-JavaExeMajorVersion $candidateExe) -ge 21) {
            return $candidateExe
        }
    }

    $pathJava = (Get-Command java -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1).Source
    if ((Get-JavaExeMajorVersion $pathJava) -ge 21) {
        return $pathJava
    }

    return $null
}

$javaExe = Resolve-Java21Exe
if (-not $javaExe) {
    Write-Error "No JDK 21+ found. Install JDK 21+ or set JAVA_HOME (or PATH) to a JDK 21+ installation."
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

& $javaExe -jar $JarPath @appArgs
exit $LASTEXITCODE
