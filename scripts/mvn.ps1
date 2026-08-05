# scripts/mvn.ps1
# Maven wrapper: ensure a JDK 21+ is active (the machine default JAVA_HOME is JDK 8),
# then delegate to the real mvn with the same arguments.

$jdk21Home = 'C:\Program Files\Java\jdk-21.0.6'

# Detect the version of the currently configured JDK (the one Maven would use).
$currentJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
$versionLine = & $currentJava -version 2>&1 | Select-Object -First 1
$currentIsJdk21Plus = ($versionLine -match 'version "(\d+)') -and ([int]$matches[1] -ge 21)

if (-not $currentIsJdk21Plus) {
    if (-not (Test-Path -LiteralPath $jdk21Home)) {
        Write-Error "JDK 21 not found at '$jdk21Home'. Install it or set JAVA_HOME to a JDK 21+ installation."
        exit 1
    }
    $env:JAVA_HOME = $jdk21Home
}

& mvn @args
exit $LASTEXITCODE
