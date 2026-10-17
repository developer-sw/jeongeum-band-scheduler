#requires -Version 7.2
<#
.SYNOPSIS
Build and verify the production stack using new, synthetic QA resources only.
.DESCRIPTION
Never reads the development .env, DB secrets, containers, or data volumes.
Credentials, trusted test CA, synthetic sessions and dump remain in the ignored
.local/production-qa-* folder. Only project-labelled QA resources may be removed.
The restore check uses another database in the same isolated QA PostgreSQL
cluster; it is not an external-backup or independent-engine disaster drill.
#>
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)] [int]$HttpPort = 18080,
    [ValidateRange(1024, 65535)] [int]$HttpsPort = 18443,
    [string]$NodeCommand = 'node',
    [switch]$SkipBuild,
    [switch]$KeepQaResources,
    [string]$ResultPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$qaRepoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$qaLocalRoot = Join-Path $qaRepoRoot '.local'
$qaSuffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$qaProject = "jeongeum-prod-qa-$qaSuffix"
$qaDirectory = Join-Path $qaLocalRoot "production-qa-$qaSuffix"
$qaEnvironmentFile = Join-Path $qaDirectory 'qa.env'
$qaLogFile = Join-Path $qaDirectory 'docker.private.log'
$qaProductionCompose = Join-Path $qaRepoRoot 'deploy/compose.production.yaml'
$qaOverrideCompose = Join-Path $qaRepoRoot 'deploy/compose.qa.yaml'
$qaHelper = Join-Path $PSScriptRoot 'production-container-qa.mjs'
$qaDatabase = "qa_$qaSuffix"
$qaDatabaseUser = "qa_$qaSuffix"
$qaRestoreDatabase = "${qaDatabase}_restore"
$qaResourcesStarted = $false
$qaPassed = $false
$qaFailureCode = $null
$qaChecks = [Collections.Generic.List[string]]::new()
$qaResultPath = $null

function Assert-QaResultPath {
    param([string]$Path, [Parameter(Mandatory)] [string]$RepositoryRoot)
    if ([string]::IsNullOrEmpty($Path)) { return $null }
    if (-not [IO.Path]::IsPathFullyQualified($Path)) { throw 'QA_RESULT_PATH_REJECTED' }
    $fullRoot = [IO.Path]::GetFullPath($RepositoryRoot)
    $localRoot = Join-Path $fullRoot '.local'
    $fullPath = [IO.Path]::GetFullPath($Path)
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($fullPath), $localRoot, $comparison) -or
        [IO.Path]::GetFileName($fullPath) -cnotmatch '^production-image-release-qa-(?:[a-f0-9]{32}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\.json$') {
        throw 'QA_RESULT_PATH_REJECTED'
    }
    foreach ($parent in @($fullRoot, $localRoot)) {
        $item = Get-Item -Force -LiteralPath $parent -ErrorAction SilentlyContinue
        if ($null -ne $item -and ((-not $item.PSIsContainer) -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint))) {
            throw 'QA_RESULT_PARENT_REJECTED'
        }
    }
    # Get-Item also detects dangling links, unlike File.Exists/Test-Path.
    if ($null -ne (Get-Item -Force -LiteralPath $fullPath -ErrorAction SilentlyContinue)) { throw 'QA_RESULT_ALREADY_EXISTS' }
    return $fullPath
}

function Write-QaResultExport {
    param([Parameter(Mandatory)] [string]$Path, [Parameter(Mandatory)] [string]$RepositoryRoot,
        [Parameter(Mandatory)] [Collections.IDictionary]$Summary)
    $checkedPath = Assert-QaResultPath -Path $Path -RepositoryRoot $RepositoryRoot
    $safeSummary = [ordered]@{}
    foreach ($name in @('passed', 'checks', 'cleanupVerified', 'resourcesKept', 'developmentDatabaseAccessed', 'actualPushSent', 'restoreScope')) {
        $safeSummary[$name] = $Summary[$name]
    }
    # CreateNew is essential: a destination that appeared since validation
    # must never overwrite a previous result or follow a target symlink.
    $stream = [IO.FileStream]::new($checkedPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($safeSummary | ConvertTo-Json -Depth 4))
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}

function Assert-QaScope {
    if ($qaProject -cne "jeongeum-prod-qa-$qaSuffix" -or $qaProject -notmatch '^jeongeum-prod-qa-[a-f0-9]{12}$') {
        throw 'QA_PROJECT_GUARD_REJECTED'
    }
    $fullDirectory = [IO.Path]::GetFullPath($qaDirectory)
    $expected = [IO.Path]::GetFullPath((Join-Path $qaRepoRoot ".local/production-qa-$qaSuffix"))
    if ($fullDirectory -cne $expected -or $qaDatabase -notmatch '^qa_[a-f0-9]{12}$' -or $qaDatabaseUser -notmatch '^qa_[a-f0-9]{12}$' -or
        $qaRestoreDatabase -cne "${qaDatabase}_restore") {
        throw 'QA_PATH_OR_DATABASE_GUARD_REJECTED'
    }
    foreach ($candidate in @($qaLocalRoot, $qaDirectory)) {
        if (Test-Path -LiteralPath $candidate) {
            # .local is hidden on Unix; inspect it without skipping hidden items.
            if ((Get-Item -Force -LiteralPath $candidate).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'QA_REPARSE_POINT_REJECTED'
            }
        }
    }
}

function Invoke-QaProcess {
    param(
        [Parameter(Mandatory)] [string]$File,
        [Parameter(Mandatory)] [string[]]$Arguments,
        [string]$InputText,
        [switch]$Capture,
        [int]$TimeoutSeconds = 1200
    )
    Assert-QaScope
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $File
    $start.WorkingDirectory = $qaRepoRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.RedirectStandardInput = $true
    foreach ($argument in $Arguments) { [void]$start.ArgumentList.Add($argument) }
    # Shell variables outrank --env-file in Compose. Remove inherited app/DB
    # settings so an existing development shell cannot redirect this QA stack.
    $removeNames = @($start.Environment.Keys | Where-Object {
        $_ -match '^(APP_|JEONGEUM_|POSTGRES_|VAPID_|BOOTSTRAP_|COMPOSE_|QA_|CADDY_)' -or $_ -in @('BACKEND_IMAGE', 'FRONTEND_IMAGE', 'ACME_EMAIL', 'ECE_KEYLOG')
    })
    foreach ($name in $removeNames) { [void]$start.Environment.Remove($name) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'QA_PROCESS_START_FAILED' }
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if ($PSBoundParameters.ContainsKey('InputText')) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            throw 'QA_PROCESS_TIMEOUT'
        }
        $stdout = $outputTask.GetAwaiter().GetResult()
        $stderr = $errorTask.GetAwaiter().GetResult()
        [IO.File]::AppendAllText($qaLogFile, "`n--- private process output ---`n$stdout`n$stderr`n", [Text.UTF8Encoding]::new($false))
        if ($process.ExitCode -ne 0) { throw 'QA_PROCESS_EXIT_FAILED' }
        if ($Capture) { return $stdout.Trim() }
    } finally { $process.Dispose() }
}

function Invoke-QaDocker {
    param([Parameter(Mandatory)] [string[]]$Arguments, [string]$InputText, [switch]$Capture, [int]$TimeoutSeconds = 1200)
    $parameters = @{ File = 'docker'; Arguments = $Arguments; Capture = $Capture; TimeoutSeconds = $TimeoutSeconds }
    if ($PSBoundParameters.ContainsKey('InputText')) { $parameters.InputText = $InputText }
    Invoke-QaProcess @parameters
}

function Invoke-QaCompose {
    param([Parameter(Mandatory)] [string[]]$Arguments, [switch]$Capture, [int]$TimeoutSeconds = 1200)
    Assert-QaScope
    if (-not (Test-Path -LiteralPath $qaEnvironmentFile -PathType Leaf)) { throw 'QA_ENV_FILE_MISSING' }
    $prefix = @('compose', '--project-directory', (Split-Path -Parent $qaProductionCompose), '--env-file', $qaEnvironmentFile,
        '--project-name', $qaProject, '-f', $qaProductionCompose, '-f', $qaOverrideCompose)
    Invoke-QaDocker -Arguments ($prefix + $Arguments) -Capture:$Capture -TimeoutSeconds $TimeoutSeconds
}

function Assert-QaContainer {
    param([Parameter(Mandatory)] [string]$Container)
    if ($Container -notmatch '^[a-f0-9]{12,64}$') { throw 'QA_CONTAINER_ID_REJECTED' }
    $label = Invoke-QaDocker -Arguments @('inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', $Container) -Capture
    if ($label -cne $qaProject) { throw 'QA_CONTAINER_PROJECT_REJECTED' }
}

function Get-QaContainer {
    param([Parameter(Mandatory)] [ValidateSet('postgres', 'backend', 'frontend', 'caddy')] [string]$Service)
    $ids = @(Invoke-QaCompose -Arguments @('ps', '--all', '--quiet', $Service) -Capture | Where-Object { $_ } | ForEach-Object { $_ -split '\r?\n' })
    if ($ids.Count -ne 1) { throw 'QA_SERVICE_CONTAINER_COUNT_INVALID' }
    Assert-QaContainer -Container $ids[0]
    return $ids[0]
}

function Assert-QaStoppedServices {
    param([Parameter(Mandatory)] [ValidateSet('before_restart', 'before_dump')] [string]$Stage)
    foreach ($service in @('backend', 'frontend', 'caddy')) {
        $container = Get-QaContainer -Service $service
        $state = Invoke-QaDocker -Arguments @('inspect', '--format', '{"running":{{.State.Running}},"oomKilled":{{.State.OOMKilled}},"exitCode":{{.State.ExitCode}}}', $container) -Capture | ConvertFrom-Json
        if ($state.running -or $state.oomKilled -or [int]$state.exitCode -notin @(0, 143)) { throw 'QA_GRACEFUL_STOP_NOT_VERIFIED' }
        $qaChecks.Add("${service}_graceful_stop_$Stage")
    }
}

function Assert-QaResourceOwnership {
    $idsText = Invoke-QaDocker -Arguments @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject") -Capture
    foreach ($container in @($idsText -split '\r?\n' | Where-Object { $_ })) {
        Assert-QaContainer -Container $container
        $volumes = Invoke-QaDocker -Arguments @('inspect', '--format', '{{range .Mounts}}{{if eq .Type "volume"}}{{println .Name}}{{end}}{{end}}', $container) -Capture
        foreach ($volume in @($volumes -split '\r?\n' | Where-Object { $_ })) {
            $owner = Invoke-QaDocker -Arguments @('volume', 'inspect', '--format', '{{index .Labels "com.docker.compose.project"}}', $volume) -Capture
            if ($owner -cne $qaProject) { throw 'QA_VOLUME_OWNER_REJECTED' }
        }
    }
    foreach ($kind in @('volume', 'network')) {
        $format = if ($kind -eq 'volume') { '{{.Name}}' } else { '{{.ID}}' }
        $objects = Invoke-QaDocker -Arguments @($kind, 'ls', '--filter', "label=com.docker.compose.project=$qaProject", '--format', $format) -Capture
        foreach ($item in @($objects -split '\r?\n' | Where-Object { $_ })) {
            $owner = Invoke-QaDocker -Arguments @($kind, 'inspect', '--format', '{{index .Labels "com.docker.compose.project"}}', $item) -Capture
            if ($owner -cne $qaProject) { throw 'QA_RESOURCE_OWNER_REJECTED' }
        }
    }
}

function Assert-QaResolvedConfiguration {
    # Capture to private process output only. Never print a resolved Compose
    # document: in another setting it could contain literal environment secrets.
    $configuration = Invoke-QaCompose -Arguments @('config', '--format', 'json') -Capture | ConvertFrom-Json
    if ($configuration.name -cne $qaProject) { throw 'QA_RESOLVED_PROJECT_REJECTED' }
    $serviceNames = @($configuration.services.PSObject.Properties.Name | Sort-Object)
    if (($serviceNames -join ',') -cne 'backend,caddy,frontend,postgres') { throw 'QA_RESOLVED_SERVICES_REJECTED' }
    if ($configuration.services.postgres.environment.POSTGRES_DB -cne $qaDatabase -or
        $configuration.services.postgres.environment.POSTGRES_USER -cne 'postgres' -or
        $configuration.services.postgres.environment.APP_DB_USERNAME -cne $qaDatabaseUser -or
        $configuration.services.backend.environment.APP_DB_USERNAME -cne $qaDatabaseUser -or
        $configuration.services.backend.environment.APP_DB_URL -cne "jdbc:postgresql://postgres:5432/$qaDatabase") {
        throw 'QA_RESOLVED_DATABASE_REJECTED'
    }
    foreach ($flag in @('JEONGEUM_RECURRING_WORKER_ENABLED', 'JEONGEUM_EXCHANGE_WORKER_ENABLED', 'JEONGEUM_REMINDER_WORKER_ENABLED', 'JEONGEUM_PUSH_WORKER_ENABLED', 'JEONGEUM_PUSH_ENABLED')) {
        if ([string]$configuration.services.backend.environment.PSObject.Properties[$flag].Value -cne 'false') { throw 'QA_RESOLVED_WORKER_REJECTED' }
    }
    if ($configuration.services.backend.environment.SPRING_PROFILES_ACTIVE -cne 'production' -or
        $configuration.services.caddy.environment.APP_DOMAIN -cne 'localhost') { throw 'QA_RESOLVED_PROFILE_OR_ORIGIN_REJECTED' }
    foreach ($kind in @('volumes', 'networks')) {
        foreach ($entry in $configuration.PSObject.Properties[$kind].Value.PSObject.Properties) {
            $properties = @($entry.Value.PSObject.Properties.Name)
            if ($entry.Value.name -notlike "${qaProject}_*" -or
                ($properties -contains 'external' -and $entry.Value.external)) { throw 'QA_EXTERNAL_OR_FOREIGN_RESOURCE_REJECTED' }
        }
    }
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    $secretPrefix = [IO.Path]::GetFullPath($qaDirectory) + [IO.Path]::DirectorySeparatorChar
    foreach ($secret in $configuration.secrets.PSObject.Properties) {
        if (-not ([IO.Path]::GetFullPath([string]$secret.Value.file)).StartsWith($secretPrefix, $comparison)) { throw 'QA_SECRET_SOURCE_REJECTED' }
    }
    $configPrefix = [IO.Path]::GetFullPath((Join-Path $qaRepoRoot 'deploy')) + [IO.Path]::DirectorySeparatorChar
    foreach ($service in $configuration.services.PSObject.Properties) {
        $properties = @($service.Value.PSObject.Properties.Name)
        if ($properties -contains 'volumes') {
            foreach ($mount in $service.Value.volumes) {
                if ($mount.type -ceq 'bind') {
                    if (-not ([IO.Path]::GetFullPath([string]$mount.source)).StartsWith($configPrefix, $comparison) -or -not $mount.read_only) {
                        throw 'QA_BIND_MOUNT_SOURCE_REJECTED'
                    }
                } elseif ($mount.type -ceq 'volume') {
                    $volume = $configuration.volumes.PSObject.Properties[[string]$mount.source]
                    if ($null -eq $volume -or $volume.Value.name -notlike "${qaProject}_*") { throw 'QA_VOLUME_MOUNT_REJECTED' }
                } else { throw 'QA_VOLUME_TYPE_REJECTED' }
            }
        }
        if ($service.Name -cne 'caddy' -and $properties -contains 'ports' -and @($service.Value.ports).Count -gt 0) { throw 'QA_PRESTART_INTERNAL_PORT_REJECTED' }
    }
    $ports = @($configuration.services.caddy.ports)
    if ($ports.Count -ne 2) { throw 'QA_PRESTART_PORT_COUNT_REJECTED' }
    foreach ($port in $ports) {
        $expected = if ([int]$port.target -eq 80) { $HttpPort } elseif ([int]$port.target -eq 443) { $HttpsPort } else { 0 }
        if ($port.host_ip -cne '127.0.0.1' -or [int]$port.published -ne $expected -or $expected -eq 0) { throw 'QA_PRESTART_LOOPBACK_PORT_REJECTED' }
    }
    $qaChecks.Add('resolved_configuration_cannot_mount_or_connect_development_data')
}

function Get-QaNetworkRange {
    $subnets = [Collections.Generic.List[string]]::new()
    $networkIds = Invoke-QaDocker -Arguments @('network', 'ls', '--quiet') -Capture
    foreach ($network in @($networkIds -split '\r?\n' | Where-Object { $_ })) {
        $config = Invoke-QaDocker -Arguments @('network', 'inspect', '--format', '{{json .IPAM.Config}}', $network) -Capture | ConvertFrom-Json
        foreach ($entry in @($config)) {
            if ($null -ne $entry -and $entry.PSObject.Properties.Name -contains 'Subnet' -and
                $entry.Subnet -match '^\d+\.\d+\.\d+\.\d+/\d+$') { $subnets.Add($entry.Subnet) }
        }
    }
    function Range-Of([string]$Cidr) {
        $parts = $Cidr.Split('/')
        $bytes = [Net.IPAddress]::Parse($parts[0]).GetAddressBytes()
        $value = [uint64]$bytes[0] * 16777216 + [uint64]$bytes[1] * 65536 + [uint64]$bytes[2] * 256 + [uint64]$bytes[3]
        $width = [uint64][Math]::Pow(2, 32 - [int]$parts[1])
        $first = [uint64]([Math]::Floor($value / $width) * $width)
        return @($first, ($first + $width - 1))
    }
    foreach ($second in @(30, 29, 28, 31)) {
        foreach ($third in (0..255 | Sort-Object { Get-Random })) {
            $candidate = "172.$second.$third.0/24"
            $range = Range-Of $candidate
            $overlaps = $false
            foreach ($subnet in $subnets) {
                $existing = Range-Of $subnet
                if ($range[0] -le $existing[1] -and $existing[0] -le $range[1]) { $overlaps = $true; break }
            }
            if (-not $overlaps) { return @{ Subnet = $candidate; Caddy = "172.$second.$third.10"; Regex = "172[.]$second[.]$third[.]10" } }
        }
    }
    throw 'QA_PRIVATE_SUBNET_UNAVAILABLE'
}

function Invoke-QaSql {
    param([Parameter(Mandatory)] [string]$Database, [Parameter(Mandatory)] [string]$Sql, [switch]$AsApplication)
    if ($Database -cne $qaDatabase -and $Database -cne $qaRestoreDatabase) { throw 'QA_SQL_DATABASE_REJECTED' }
    $postgres = Get-QaContainer -Service postgres
    $role = if ($AsApplication) { $qaDatabaseUser } else { 'postgres' }
    return Invoke-QaDocker -Arguments @('exec', '--interactive', $postgres, 'psql', '-X', '-q', '-t', '-A',
        '--set', 'ON_ERROR_STOP=1', '--username', $role, '--dbname', $Database) -InputText $Sql -Capture
}

function Get-QaDatabaseManifest {
    param([Parameter(Mandatory)] [string]$Database, [switch]$AsApplication)
    # Only deterministic names/counts/digests leave PostgreSQL. No row contents,
    # cookies or hashes of credentials are printed to the user's terminal.
    $sql = @'
SET TIME ZONE 'UTC';
SELECT format('SELECT %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(row_to_json(q)::text, E''\n'' ORDER BY row_to_json(q)::text)),md5('''')) FROM %I.%I q;',
              'table|' || schemaname || '.' || tablename, schemaname, tablename)
FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
SELECT format('SELECT %L || ''|'' || last_value || ''|'' || is_called FROM %I.%I;',
              'sequence|' || schemaname || '.' || sequencename, schemaname, sequencename)
FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename
\gexec
'@
    return Invoke-QaSql -Database $Database -Sql $sql -AsApplication:$AsApplication
}

function Invoke-QaHttpPhase {
    param([Parameter(Mandatory)] [ValidateSet('init', 'api', 'restart', 'restore')] [string]$Phase)
    Invoke-QaProcess -File $NodeCommand -Arguments @($qaHelper, $Phase, $qaDirectory) -TimeoutSeconds 300
    $result = Get-Content -LiteralPath (Join-Path $qaDirectory "$Phase-result.json") -Raw | ConvertFrom-Json
    if (-not $result.passed) { throw 'QA_HTTP_PHASE_FAILED' }
    foreach ($item in $result.checks) { $qaChecks.Add([string]$item) }
    Write-Output "QA $Phase checks: $(@($result.checks).Count) passed."
}

try {
    # Validate the optional release proof destination before creating QA
    # directories, starting processes, or inspecting Docker resources.
    $qaResultPath = Assert-QaResultPath -Path $ResultPath -RepositoryRoot $qaRepoRoot
    if ($HttpPort -eq $HttpsPort) { throw 'QA_PORTS_MUST_DIFFER' }
    foreach ($source in @($qaProductionCompose, $qaOverrideCompose, $qaHelper)) {
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'QA_REQUIRED_ASSET_MISSING' }
    }
    Assert-QaScope
    if (Test-Path -LiteralPath $qaDirectory) { throw 'QA_DIRECTORY_ALREADY_EXISTS' }
    [void](New-Item -ItemType Directory -Path $qaDirectory)
    if ($IsWindows) {
        $security = [Security.AccessControl.DirectorySecurity]::new()
        $security.SetAccessRuleProtection($true, $false)
        foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
            $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
        }
        Set-Acl -LiteralPath $qaDirectory -AclObject $security
    }
    [IO.File]::WriteAllText((Join-Path $qaDirectory 'qa-run.json'), (@{
        project = $qaProject; database = $qaDatabase; databaseUser = $qaDatabaseUser; httpPort = $HttpPort; httpsPort = $HttpsPort
    } | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Invoke-QaHttpPhase -Phase init
    if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('DOCKER_HOST', 'Process'))) {
        throw 'QA_DOCKER_HOST_OVERRIDE_REJECTED'
    }
    $dockerEndpoint = Invoke-QaDocker -Arguments @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}') -Capture
    if ($dockerEndpoint -notmatch '^(npipe://|unix://)') { throw 'QA_REMOTE_DOCKER_CONTEXT_REJECTED' }
    if ((Invoke-QaDocker -Arguments @('info', '--format', '{{.OSType}}') -Capture) -cne 'linux') { throw 'QA_REQUIRES_LINUX_DOCKER' }
    # The unique project must have no pre-existing resource before first up.
    foreach ($argumentSet in @(
        @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject"),
        @('volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject"),
        @('network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject")
    )) { if (Invoke-QaDocker -Arguments $argumentSet -Capture) { throw 'QA_PROJECT_RESOURCE_COLLISION' } }
    $network = Get-QaNetworkRange
    $hostPath = { param([string]$name) (Join-Path $qaDirectory $name).Replace('\', '/') }
    $environment = [ordered]@{
        COMPOSE_PROJECT_NAME = $qaProject; APP_DOMAIN = 'localhost'; POSTGRES_DB = $qaDatabase; APP_DB_USERNAME = $qaDatabaseUser
        BACKEND_IMAGE = 'jeongeum-backend:production-qa'; FRONTEND_IMAGE = 'jeongeum-frontend:production-qa'; CADDY_IMAGE = 'jeongeum-caddy:production-qa'
        ACME_EMAIL = 'qa@example.invalid'
        POSTGRES_ADMIN_PASSWORD_FILE = & $hostPath 'postgres-admin-password.txt'; APP_DB_PASSWORD_FILE = & $hostPath 'app-db-password.txt'
        VAPID_PUBLIC_KEY_FILE = & $hostPath 'vapid-public-key.txt'; VAPID_PRIVATE_KEY_FILE = & $hostPath 'vapid-private-key.txt'; VAPID_SUBJECT_FILE = & $hostPath 'vapid-subject.txt'
        BOOTSTRAP_LOGIN_ID_FILE = & $hostPath 'bootstrap-login-id.txt'; BOOTSTRAP_PASSWORD_FILE = & $hostPath 'bootstrap-password.txt'; BOOTSTRAP_DISPLAY_NAME_FILE = & $hostPath 'bootstrap-display-name.txt'
        QA_HTTP_PORT = $HttpPort; QA_HTTPS_PORT = $HttpsPort; APP_NETWORK_SUBNET = $network.Subnet; CADDY_INTERNAL_IP = $network.Caddy; APP_TRUSTED_PROXY_REGEX = $network.Regex
        JEONGEUM_RECURRING_WORKER_ENABLED = 'false'; JEONGEUM_EXCHANGE_WORKER_ENABLED = 'false'; JEONGEUM_REMINDER_WORKER_ENABLED = 'false'; JEONGEUM_PUSH_WORKER_ENABLED = 'false'; JEONGEUM_PUSH_ENABLED = 'false'
    }
    $envLines = foreach ($item in $environment.GetEnumerator()) { '{0}={1}' -f $item.Key, $item.Value }
    [IO.File]::WriteAllText($qaEnvironmentFile, (($envLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    Invoke-QaCompose -Arguments @('config', '--quiet')
    Assert-QaResolvedConfiguration
    if (-not $SkipBuild) { Write-Output 'Building Linux QA images; detailed output is private.'; Invoke-QaCompose -Arguments @('build', 'backend', 'frontend', 'caddy') }
    $qaResourcesStarted = $true
    Invoke-QaCompose -Arguments @('up', '--detach', '--wait', '--wait-timeout', '180', 'postgres')
    Invoke-QaCompose -Arguments @('run', '--rm', '--no-deps', '-e', 'SPRING_PROFILES_ACTIVE=bootstrap-admin', 'backend') -TimeoutSeconds 300
    Invoke-QaCompose -Arguments @('up', '--detach', '--wait', '--wait-timeout', '240', 'backend', 'frontend', 'caddy') -TimeoutSeconds 360
    Assert-QaResourceOwnership
    foreach ($service in @('backend', 'frontend', 'caddy')) {
        $container = Get-QaContainer -Service $service
        $uid = Invoke-QaDocker -Arguments @('exec', $container, 'id', '-u') -Capture
        if ($uid -notmatch '^[1-9][0-9]*$') { throw 'QA_APPLICATION_RUNNING_AS_ROOT' }
        $qaChecks.Add("${service}_nonroot")
    }
    foreach ($service in @('postgres', 'backend', 'frontend')) {
        $container = Get-QaContainer -Service $service
        $bindings = Invoke-QaDocker -Arguments @('inspect', '--format', '{{json .HostConfig.PortBindings}}', $container) -Capture
        if ($bindings -notin @('null', '{}')) { throw 'QA_INTERNAL_SERVICE_HAS_HOST_PORT' }
    }
    $qaChecks.Add('database_and_application_ports_private')
    $backend = Get-QaContainer -Service backend
    $flags = Invoke-QaDocker -Arguments @('exec', $backend, 'sh', '-c', 'printf "%s\n" "$JEONGEUM_RECURRING_WORKER_ENABLED" "$JEONGEUM_EXCHANGE_WORKER_ENABLED" "$JEONGEUM_REMINDER_WORKER_ENABLED" "$JEONGEUM_PUSH_WORKER_ENABLED" "$JEONGEUM_PUSH_ENABLED"') -Capture
    if ((($flags -split '\r?\n') -join ',') -cne 'false,false,false,false,false') { throw 'QA_WORKERS_OR_PUSH_NOT_DISABLED' }
    $qaChecks.Add('all_four_workers_and_external_push_disabled')
    $caddy = Get-QaContainer -Service caddy
    $ports = Invoke-QaDocker -Arguments @('inspect', '--format', '{{json .HostConfig.PortBindings}}', $caddy) -Capture | ConvertFrom-Json
    foreach ($entry in $ports.PSObject.Properties.Value) {
        foreach ($binding in $entry) { if ($binding.HostIp -cne '127.0.0.1') { throw 'QA_HTTPS_NOT_LOOPBACK_ONLY' } }
    }
    $qaChecks.Add('qa_https_http_bind_only_loopback')
    Invoke-QaDocker -Arguments @('cp', "${caddy}:/data/caddy/pki/authorities/local/root.crt", (Join-Path $qaDirectory 'caddy-root.crt'))
    $versions = Invoke-QaSql -Database $qaDatabase -Sql "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL;"
    if ($versions -cne '1,2,3,4,5,6,7,8,9,10,11,12') { throw 'QA_FLYWAY_HISTORY_INVALID' }
    $qaChecks.Add('flyway_v1_through_v12_success')
    $roleElevated = Invoke-QaSql -Database $qaDatabase -Sql "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication FROM pg_roles WHERE rolname='$qaDatabaseUser';"
    if ($roleElevated -cne 'f') { throw 'QA_APPLICATION_DATABASE_ROLE_ELEVATED' }
    $qaChecks.Add('application_database_role_not_cluster_administrator')
    Invoke-QaHttpPhase -Phase api

    Write-Output 'Checking a real application stop/start and existing synthetic sessions.'
    Invoke-QaCompose -Arguments @('stop', 'caddy', 'frontend', 'backend')
    Assert-QaStoppedServices -Stage before_restart
    $beforeRestart = Get-QaDatabaseManifest -Database $qaDatabase
    [IO.File]::WriteAllText((Join-Path $qaDirectory 'before-restart.private.manifest'), $beforeRestart, [Text.UTF8Encoding]::new($false))
    Invoke-QaCompose -Arguments @('up', '--detach', '--wait', '--wait-timeout', '240', 'backend', 'frontend', 'caddy') -TimeoutSeconds 360
    $afterRestart = Get-QaDatabaseManifest -Database $qaDatabase
    if ($beforeRestart -cne $afterRestart) { throw 'QA_RESTART_DATABASE_CHANGED' }
    $qaChecks.Add('restart_preserves_all_table_rows_sessions_sequences_flyway')
    Invoke-QaHttpPhase -Phase restart

    Write-Output 'Checking a synthetic dump restored into a separate QA database.'
    Invoke-QaCompose -Arguments @('stop', 'caddy', 'frontend', 'backend')
    Assert-QaStoppedServices -Stage before_dump
    $originalManifest = Get-QaDatabaseManifest -Database $qaDatabase
    $postgres = Get-QaContainer -Service postgres
    Invoke-QaDocker -Arguments @('exec', $postgres, 'pg_dump', '--username', 'postgres', '--dbname', $qaDatabase, '--format=custom', '--no-owner', '--no-acl', '--file=/tmp/qa-preserved.dump')
    $dumpPath = Join-Path $qaDirectory 'synthetic-preserved.dump'
    Invoke-QaDocker -Arguments @('cp', "${postgres}:/tmp/qa-preserved.dump", $dumpPath)
    Invoke-QaDocker -Arguments @('exec', $postgres, 'createdb', '--username', 'postgres', '--owner', $qaDatabaseUser, $qaRestoreDatabase)
    Invoke-QaDocker -Arguments @('exec', $postgres, 'pg_restore', '--username', 'postgres', '--role', $qaDatabaseUser, '--dbname', $qaRestoreDatabase, '--exit-on-error', '--no-owner', '--no-acl', '/tmp/qa-preserved.dump')
    $restoredManifest = Get-QaDatabaseManifest -Database $qaRestoreDatabase -AsApplication
    if ($originalManifest -cne $restoredManifest) { throw 'QA_RESTORE_DATABASE_DIFFERENT' }
    $qaChecks.Add('separate_qa_database_restore_preserves_all_rows_sessions_sequences_flyway')
    $qaChecks.Add('restore_is_same_qa_cluster_not_external_disaster_recovery')
    [IO.File]::WriteAllText((Join-Path $qaDirectory 'restored.private.manifest'), $restoredManifest, [Text.UTF8Encoding]::new($false))
    $ownership = Invoke-QaSql -Database $qaRestoreDatabase -AsApplication -Sql "SELECT current_user='$qaDatabaseUser' AND bool_and(tableowner=current_user) FROM pg_tables WHERE schemaname='public';"
    if ($ownership -cne 't') { throw 'QA_RESTORE_APPLICATION_OWNERSHIP_INVALID' }
    $qaChecks.Add('restored_tables_owned_and_readable_by_non_superuser_application_role')
    # Activate only the backend against the restored QA DB. --no-deps is
    # essential: do not reconfigure/reinitialize the source PostgreSQL service.
    $restoreEnvLines = foreach ($item in $environment.GetEnumerator()) {
        '{0}={1}' -f $item.Key, $(if ($item.Key -ceq 'POSTGRES_DB') { $qaRestoreDatabase } else { $item.Value })
    }
    [IO.File]::WriteAllText($qaEnvironmentFile, (($restoreEnvLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    Invoke-QaCompose -Arguments @('up', '--detach', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '240', 'backend') -TimeoutSeconds 360
    Invoke-QaCompose -Arguments @('up', '--detach', '--no-deps', '--wait', '--wait-timeout', '180', 'frontend', 'caddy') -TimeoutSeconds 300
    Invoke-QaHttpPhase -Phase restore
    $qaChecks.Add('restored_database_serves_existing_sessions_and_reservation_apis')
    $qaPassed = $true
} catch {
    $qaFailureRecord = $_
    $qaFailureCode = if ($_.Exception.Message -cmatch '^QA_[A-Z0-9_]+$') { $_.Exception.Message } else { 'QA_UNEXPECTED_FAILURE' }
    if (Test-Path -LiteralPath $qaDirectory -PathType Container) {
        # Save only fixed diagnostic fields. Never serialize the error record,
        # command arguments, response bodies, or a possibly secret message.
        $failureLocation = "QA failure type=$($qaFailureRecord.Exception.GetType().FullName) line=$($qaFailureRecord.InvocationInfo.ScriptLineNumber)`n"
        [IO.File]::AppendAllText($qaLogFile, $failureLocation, [Text.UTF8Encoding]::new($false))
    }
    if ($qaResourcesStarted) {
        try {
            Assert-QaResourceOwnership
            Invoke-QaCompose -Arguments @('logs', '--no-color', '--tail', '120')
        } catch {
            # Log collection is best effort and must not bypass safe cleanup.
            if (Test-Path -LiteralPath $qaDirectory -PathType Container) {
                [IO.File]::AppendAllText($qaLogFile, "QA_PRIVATE_LOG_CAPTURE_UNAVAILABLE`n", [Text.UTF8Encoding]::new($false))
            }
        }
    }
    Write-Output "Production QA failed: $qaFailureCode. See the private QA directory."
    # Publish only source coordinates and the CLR type, never exception messages.
    Write-Output ("QA failure location: line {0} ({1})." -f [int]$qaFailureRecord.InvocationInfo.ScriptLineNumber, $qaFailureRecord.Exception.GetType().FullName)
} finally {
    $cleanupPassed = -not $qaResourcesStarted
    if ($qaResourcesStarted -and -not $KeepQaResources) {
        try {
            Assert-QaResourceOwnership
            Invoke-QaCompose -Arguments @('down', '--volumes', '--remove-orphans', '--timeout', '30')
            $remaining = Invoke-QaDocker -Arguments @('ps', '--all', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject") -Capture
            $remainingVolumes = Invoke-QaDocker -Arguments @('volume', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject") -Capture
            $remainingNetworks = Invoke-QaDocker -Arguments @('network', 'ls', '--quiet', '--filter', "label=com.docker.compose.project=$qaProject") -Capture
            $cleanupPassed = -not $remaining -and -not $remainingVolumes -and -not $remainingNetworks
            if (-not $cleanupPassed) { throw 'QA_CLEANUP_INCOMPLETE' }
        } catch {
            $qaPassed = $false
            $qaFailureCode = 'QA_CLEANUP_GUARD_OR_REMOVAL_FAILED'
            Write-Output 'QA cleanup could not be verified; no broader cleanup was attempted.'
        }
    }
    $summary = [ordered]@{
        project = $qaProject; passed = $qaPassed; error = $qaFailureCode; checks = $qaChecks.ToArray()
        cleanupVerified = $cleanupPassed; resourcesKept = [bool]$KeepQaResources
        developmentDatabaseAccessed = $false; actualPushSent = $false
        restoreScope = 'Separate database in the same synthetic QA PostgreSQL cluster'
    }
    if (Test-Path -LiteralPath $qaDirectory -PathType Container) {
        [IO.File]::WriteAllText((Join-Path $qaDirectory 'result.json'), ($summary | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
        Write-Output "QA evidence: $qaDirectory"
    }
    if ($qaResultPath) {
        try { Write-QaResultExport -Path $qaResultPath -RepositoryRoot $qaRepoRoot -Summary $summary } catch {
            $qaPassed = $false
            $qaFailureCode = 'QA_RESULT_EXPORT_FAILED'
            $summary.passed = $false
            $summary.error = $qaFailureCode
            if (Test-Path -LiteralPath $qaDirectory -PathType Container) {
                [IO.File]::WriteAllText((Join-Path $qaDirectory 'result.json'), ($summary | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
            }
            Write-Output 'QA result export failed; no existing destination was overwritten.'
        }
    }
}
if (-not $qaPassed) { throw "Production container QA did not pass ($qaFailureCode)." }
Write-Output "Production container QA passed: $($qaChecks.Count) checks."
