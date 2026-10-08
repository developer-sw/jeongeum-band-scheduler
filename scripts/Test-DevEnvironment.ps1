$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location $projectRoot
try {
    $engineType = & docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0 -or $engineType -ne 'linux') {
        throw 'Docker Desktop must be running its Linux engine.'
    }

    & docker compose config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'The local Compose configuration is invalid.'
    }

    $databaseHealth = & docker compose ps postgres --format '{{.Health}}'
    if ($LASTEXITCODE -ne 0 -or $databaseHealth -ne 'healthy') {
        throw 'Start PostgreSQL first: docker compose up -d --wait postgres'
    }

    # Read the password inside the container, without passing it in command arguments.
    $sqlCommand = 'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
    $smokeSql = @'
SELECT current_database(), current_user, current_setting('server_version'), current_setting('TimeZone');
BEGIN;
CREATE TEMP TABLE environment_check (id integer PRIMARY KEY, value text NOT NULL);
INSERT INTO environment_check VALUES (1, 'local PostgreSQL transaction works');
SELECT value FROM environment_check WHERE id = 1;
ROLLBACK;
'@
    $smokeSql | & docker compose exec -T postgres sh -c $sqlCommand
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL authentication or the SQL transaction check failed.'
    }

    Write-Output 'Docker Linux engine, Compose health, PostgreSQL authentication and SQL transaction checks passed.'
} finally {
    Pop-Location
}
