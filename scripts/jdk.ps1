# scripts/jdk.ps1
# Shared JDK 21+ discovery, dot-sourced by mvn.ps1 and run.ps1.
# The machine default JAVA_HOME points to JDK 8 and java may not be on PATH.

$script:JdkRoot = 'C:\Program Files\Java'

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

function Find-Jdk21Home {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHomeExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if ((Get-JavaExeMajorVersion $javaHomeExe) -ge 21) {
            return $env:JAVA_HOME
        }
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $script:JdkRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue
    )

    foreach ($candidate in $candidates) {
        $candidateExe = Join-Path $candidate.FullName 'bin\java.exe'
        if ((Get-JavaExeMajorVersion $candidateExe) -ge 21) {
            return $candidate.FullName
        }
    }

    return $null
}

function Find-Java21Exe {
    $jdkHome = Find-Jdk21Home
    if ($jdkHome) {
        return Join-Path $jdkHome 'bin\java.exe'
    }

    $pathJava = (Get-Command java -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1).Source
    if ((Get-JavaExeMajorVersion $pathJava) -ge 21) {
        return $pathJava
    }

    return $null
}
