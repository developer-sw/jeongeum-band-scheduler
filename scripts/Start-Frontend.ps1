param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 3000,
    [ValidateRange(1024, 65535)]
    [int]$BackendPort = 8080,
    [ValidateSet('127.0.0.1', '0.0.0.0')]
    [string]$Hostname = '127.0.0.1',
    [string[]]$AllowedDevOrigins = @()
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousOrigin = [Environment]::GetEnvironmentVariable('APP_API_ORIGIN', 'Process')
$previousDevOrigins = [Environment]::GetEnvironmentVariable('APP_DEV_ORIGINS', 'Process')

Push-Location (Join-Path $projectRoot 'frontend')
try {
    [Environment]::SetEnvironmentVariable('APP_API_ORIGIN', "http://127.0.0.1:$BackendPort", 'Process')
    [Environment]::SetEnvironmentVariable('APP_DEV_ORIGINS', ($AllowedDevOrigins -join ','), 'Process')
    & npm.cmd run dev -- --hostname $Hostname --port $Port
    if ($LASTEXITCODE -ne 0) { throw 'Frontend execution failed.' }
} finally {
    [Environment]::SetEnvironmentVariable('APP_API_ORIGIN', $previousOrigin, 'Process')
    [Environment]::SetEnvironmentVariable('APP_DEV_ORIGINS', $previousDevOrigins, 'Process')
    Pop-Location
}
