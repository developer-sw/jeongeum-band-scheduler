param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$passwordFile = Join-Path $projectRoot 'secrets/postgres-password.txt'
$envFile = Join-Path $projectRoot '.env'
$databaseOptions = @{ POSTGRES_DB = 'jeongeum'; POSTGRES_USER = 'jeongeum'; POSTGRES_PORT = '5432' }

if (-not (Test-Path -LiteralPath $passwordFile)) {
    throw 'Run scripts/Initialize-DevEnvironment.ps1 and start PostgreSQL first.'
}
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        if ($line -match '^\s*(POSTGRES_DB|POSTGRES_USER|POSTGRES_PORT)\s*=\s*(.*?)\s*$') {
            $databaseOptions[$Matches[1]] = $Matches[2].Trim('"', "'")
        }
    }
}
foreach ($option in 'POSTGRES_DB', 'POSTGRES_USER') {
    if ($databaseOptions[$option] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid local $option value. Use letters, digits and underscores."
    }
}
$databasePort = 0
if (-not [int]::TryParse($databaseOptions.POSTGRES_PORT, [ref]$databasePort) -or $databasePort -lt 1 -or $databasePort -gt 65535) {
    throw 'POSTGRES_PORT must be a valid TCP port.'
}
$passwordValue = [System.IO.File]::ReadAllText($passwordFile).TrimEnd("`r", "`n")
if ([string]::IsNullOrWhiteSpace($passwordValue)) {
    throw 'The existing local DB password file is empty.'
}

$processSettings = @{
    SPRING_PROFILES_ACTIVE = 'local'
    APP_BIND_ADDRESS = '127.0.0.1'
    APP_SERVER_PORT = [string]$Port
    APP_DB_URL = "jdbc:postgresql://127.0.0.1:$databasePort/$($databaseOptions.POSTGRES_DB)"
    APP_DB_USERNAME = $databaseOptions.POSTGRES_USER
    APP_DB_PASSWORD = $passwordValue
}
$previousSettings = @{}
Push-Location (Join-Path $projectRoot 'backend')
try {
    foreach ($name in $processSettings.Keys) {
        $previousSettings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $processSettings[$name], 'Process')
    }
    & ./gradlew.bat --gradle-user-home ./.gradle/user-home --no-daemon bootRun
    if ($LASTEXITCODE -ne 0) { throw 'Backend execution failed.' }
} finally {
    foreach ($name in $previousSettings.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousSettings[$name], 'Process')
    }
    Pop-Location
}
