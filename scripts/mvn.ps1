# scripts/mvn.ps1
# Maven wrapper: ensure a JDK 21+ is active (the machine default JAVA_HOME is JDK 8),
# then delegate to the real mvn with the same arguments.

. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdkHome = Find-Jdk21Home

if (-not $jdkHome) {
    Write-Error "No JDK 21+ found under '$script:JdkRoot'. Install JDK 21+ or set JAVA_HOME to a JDK 21+ installation."
    exit 1
}

$env:JAVA_HOME = $jdkHome

& mvn @args
exit $LASTEXITCODE
