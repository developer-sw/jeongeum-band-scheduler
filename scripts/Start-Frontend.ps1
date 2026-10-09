param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 3000,
    [ValidateRange(1024, 65535)]
    [int]$BackendPort = 8080
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousOrigin = [Environment]::GetEnvironmentVariable('APP_API_ORIGIN', 'Process')

Push-Location (Join-Path $projectRoot 'frontend')
try {
    [Environment]::SetEnvironmentVariable('APP_API_ORIGIN', "http://127.0.0.1:$BackendPort", 'Process')
    & npm.cmd run dev -- --hostname 127.0.0.1 --port $Port
    if ($LASTEXITCODE -ne 0) { throw 'Frontend execution failed.' }
} finally {
    [Environment]::SetEnvironmentVariable('APP_API_ORIGIN', $previousOrigin, 'Process')
    Pop-Location
}
