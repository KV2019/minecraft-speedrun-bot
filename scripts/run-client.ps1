param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Write-Host "Workspace: $workspace"
Write-Host "Stopping previous dev client processes for this workspace..."

$staleClients = Get-CimInstance Win32_Process |
    Where-Object {
        ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and
        $_.CommandLine -and
        $_.CommandLine -like '*net.fabricmc.devlaunchinjector.Main*' -and
        $_.CommandLine -like "*$workspace*"
    }

if ($staleClients) {
    foreach ($proc in $staleClients) {
        Write-Host "Stopping PID $($proc.ProcessId)"
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 300
} else {
    Write-Host 'No previous workspace dev clients found.'
}

if (-not $env:JAVA_HOME) {
    $defaultJavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
    if (Test-Path $defaultJavaHome) {
        $env:JAVA_HOME = $defaultJavaHome
    }
}

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. Set JAVA_HOME to a Java 21 JDK path and retry.'
}

$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')

Push-Location $workspace
try {
    if ($SkipBuild) {
        & .\gradlew.bat runClient --no-daemon --console=plain
    } else {
        & .\gradlew.bat build --no-daemon --console=plain
        & .\gradlew.bat runClient --no-daemon --console=plain
    }
} finally {
    Pop-Location
}
