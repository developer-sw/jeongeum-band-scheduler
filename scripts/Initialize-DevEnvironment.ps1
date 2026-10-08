$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$exampleFile = Join-Path $projectRoot '.env.example'
$secretDirectory = Join-Path $projectRoot 'secrets'
$passwordFile = Join-Path $secretDirectory 'postgres-password.txt'

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath $exampleFile -Destination $envFile
}

if (-not (Test-Path -LiteralPath $secretDirectory)) {
    New-Item -ItemType Directory -Path $secretDirectory | Out-Null
}

if (-not (Test-Path -LiteralPath $passwordFile)) {
    $randomBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($randomBytes)
    } finally {
        $generator.Dispose()
    }
    $password = [Convert]::ToBase64String($randomBytes)
    [System.IO.File]::WriteAllText($passwordFile, $password, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'Created the local database password file. Keep it out of Git.'
} elseif ([string]::IsNullOrWhiteSpace([System.IO.File]::ReadAllText($passwordFile))) {
    throw 'The existing database password file is empty. Restore its original value before starting PostgreSQL.'
} else {
    Write-Output 'Keeping the existing local database password.'
}

Write-Output 'Local configuration is ready. Run: docker compose up -d --wait postgres'
