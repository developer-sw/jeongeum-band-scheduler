param(
    [string]$LoginId,
    [string]$DisplayName
)

$ErrorActionPreference = 'Stop'
$adminProjectRoot = Split-Path -Parent $PSScriptRoot
$adminPasswordFile = Join-Path $adminProjectRoot 'secrets/postgres-password.txt'
$adminEnvFile = Join-Path $adminProjectRoot '.env'
$adminJar = Join-Path $adminProjectRoot 'backend/build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $adminPasswordFile)) {
    throw 'Prepare the local PostgreSQL environment first.'
}
if (-not (Test-Path -LiteralPath $adminJar)) {
    throw 'Build backend bootJar first. See docs/AUTHENTICATION.md.'
}
if ([string]::IsNullOrWhiteSpace($LoginId)) { $LoginId = Read-Host 'Administrator login ID' }
if ([string]::IsNullOrWhiteSpace($DisplayName)) { $DisplayName = Read-Host 'Administrator display name' }
$LoginId = $LoginId.Trim().ToLowerInvariant()
$DisplayName = $DisplayName.Trim()
function Get-AdminCodePointCount([string]$Text) {
    ([regex]::Matches($Text, '[\uD800-\uDBFF][\uDC00-\uDFFF]|[\s\S]')).Count
}
if ($LoginId -notmatch '^[a-z0-9][a-z0-9._-]{2,31}$' -or (Get-AdminCodePointCount $DisplayName) -lt 1 -or (Get-AdminCodePointCount $DisplayName) -gt 40 -or $DisplayName -match '[\x00-\x1F\x7F-\x9F]') {
    throw 'Login ID must be 3-32 ASCII letters/digits/._-; display name must be 1-40 characters.'
}

$adminDatabaseOptions = @{ POSTGRES_DB = 'jeongeum'; POSTGRES_USER = 'jeongeum'; POSTGRES_PORT = '5432' }
if (Test-Path -LiteralPath $adminEnvFile) {
    foreach ($adminEnvLine in Get-Content -LiteralPath $adminEnvFile -Encoding UTF8) {
        if ($adminEnvLine -match '^\s*(POSTGRES_DB|POSTGRES_USER|POSTGRES_PORT)\s*=\s*(.*?)\s*$') {
            $adminDatabaseOptions[$Matches[1]] = $Matches[2].Trim('"', "'")
        }
    }
}
foreach ($adminDatabaseOption in 'POSTGRES_DB', 'POSTGRES_USER') {
    if ($adminDatabaseOptions[$adminDatabaseOption] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid local $adminDatabaseOption value."
    }
}
$adminDatabasePort = 0
if (-not [int]::TryParse($adminDatabaseOptions.POSTGRES_PORT, [ref]$adminDatabasePort) -or $adminDatabasePort -lt 1 -or $adminDatabasePort -gt 65535) {
    throw 'POSTGRES_PORT must be a valid TCP port.'
}

function Read-AdminSecretText([System.Security.SecureString]$Secret) {
    $adminSecretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($adminSecretPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($adminSecretPointer) }
}

$adminSecurePassword = $null
$adminSecureConfirmation = $null
$adminPasswordValue = $null
$adminConfirmationValue = $null
$adminDatabasePassword = $null
$adminProcessSettings = @{}
$adminPreviousSettings = @{}
try {
    Write-Output 'Creates the first global administrator only when the member table is empty.'
    $adminSecurePassword = Read-Host 'New administrator password (hidden)' -AsSecureString
    $adminSecureConfirmation = Read-Host 'Confirm administrator password (hidden)' -AsSecureString
    $adminPasswordValue = Read-AdminSecretText $adminSecurePassword
    $adminConfirmationValue = Read-AdminSecretText $adminSecureConfirmation
    if ($adminPasswordValue -cne $adminConfirmationValue) { throw 'Password confirmation does not match.' }
    if ((Get-AdminCodePointCount $adminPasswordValue) -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($adminPasswordValue) -gt 72 -or $adminPasswordValue.Contains([char]0)) {
        throw 'Use at least 12 characters and no more than 72 UTF-8 bytes.'
    }
    $adminDatabasePassword = [IO.File]::ReadAllText($adminPasswordFile).TrimEnd("`r", "`n")
    if ([string]::IsNullOrWhiteSpace($adminDatabasePassword)) { throw 'The local DB password file is empty.' }
    $adminProcessSettings = @{
        APP_DB_URL = "jdbc:postgresql://127.0.0.1:$adminDatabasePort/$($adminDatabaseOptions.POSTGRES_DB)"
        APP_DB_USERNAME = $adminDatabaseOptions.POSTGRES_USER
        APP_DB_PASSWORD = $adminDatabasePassword
        APP_BOOTSTRAP_LOGIN_ID = $LoginId
        APP_BOOTSTRAP_DISPLAY_NAME = $DisplayName
        APP_BOOTSTRAP_PASSWORD = $adminPasswordValue
    }
    foreach ($adminSettingName in $adminProcessSettings.Keys) {
        $adminPreviousSettings[$adminSettingName] = [Environment]::GetEnvironmentVariable($adminSettingName, 'Process')
        [Environment]::SetEnvironmentVariable($adminSettingName, $adminProcessSettings[$adminSettingName], 'Process')
    }
    & java -jar $adminJar --spring.profiles.active=bootstrap-admin --spring.main.web-application-type=none
    if ($LASTEXITCODE -ne 0) { throw 'Administrator initialization failed. Existing members are not changed.' }
} finally {
    foreach ($adminSettingName in $adminPreviousSettings.Keys) {
        [Environment]::SetEnvironmentVariable($adminSettingName, $adminPreviousSettings[$adminSettingName], 'Process')
    }
    if ($adminSecurePassword) { $adminSecurePassword.Dispose() }
    if ($adminSecureConfirmation) { $adminSecureConfirmation.Dispose() }
    $adminPasswordValue = $null
    $adminConfirmationValue = $null
    $adminDatabasePassword = $null
    $adminProcessSettings.Clear()
}
