# scripts/mvn.ps1
# Maven wrapper: ensure a JDK 21+ is active (the machine default JAVA_HOME is JDK 8),
# then delegate to the real mvn with the same arguments.

$jdkRoot = 'C:\Program Files\Java'

function Get-JavaHomeMajorVersion {
    param([string]$JavaHome)

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $null
    }

    $javaExe = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) {
        return $null
    }

    $versionLine = & $javaExe -version 2>&1 | Select-Object -First 1
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

$currentMajor = Get-JavaHomeMajorVersion $env:JAVA_HOME

if ($null -eq $currentMajor -or $currentMajor -lt 21) {
    $candidates = @(
        Get-ChildItem -LiteralPath $jdkRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
            Sort-Object -Property @{ Expression = { Get-JdkNameVersion $_.Name }; Descending = $true }, Name -Descending
    )

    $jdk21Home = $candidates | Select-Object -First 1 -ExpandProperty FullName

    if (-not $jdk21Home) {
        Write-Error "No JDK 21+ found under '$jdkRoot'. Install JDK 21+ or set JAVA_HOME to a JDK 21+ installation."
        exit 1
    }

    $env:JAVA_HOME = $jdk21Home
}

& mvn @args
exit $LASTEXITCODE
