#requires -Version 7.2
# Path/export, block-map and safe failure-code unit checks only. The QA entry point, Docker and DB are never run.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$sourcePath = Join-Path $PSScriptRoot 'Test-ProductionContainers.ps1'
$tokens = $null
$parseErrors = $null
$sourceAst = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'QA_EXPORT_SOURCE_PARSE_FAILED' }
foreach ($name in @('Assert-QaResultPath', 'Write-QaResultExport', 'Assert-QaFlywayHistory', 'Assert-QaTimeBlockMapping', 'Get-QaProcessExitFailureCode')) {
    $definition = $sourceAst.Find({ param($item) $item -is [Management.Automation.Language.FunctionDefinitionAst] -and $item.Name -ceq $name }, $true)
    if ($null -eq $definition) { throw 'QA_EXPORT_FUNCTION_MISSING' }
    . ([scriptblock]::Create($definition.Extent.Text))
}
$testLocalRoot = Join-Path $repositoryRoot '.local'
if (Test-Path -LiteralPath $testLocalRoot) {
    $localItem = Get-Item -Force -LiteralPath $testLocalRoot
    if ((-not $localItem.PSIsContainer) -or ($localItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'QA_EXPORT_TEST_ROOT_REJECTED' }
}
$testRoot = Join-Path $testLocalRoot "production-qa-export-unit-$([Guid]::NewGuid().ToString('N'))"
$testChecks = [Collections.Generic.List[string]]::new()
function Assert-Check {
    param([string]$Name, [bool]$Condition)
    if (-not $Condition) { throw "QA_EXPORT_CHECK_FAILED:$Name" }
    $testChecks.Add($Name)
}
function Assert-Rejected {
    param([string]$Name, [scriptblock]$Action)
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Check $Name $rejected
}
function Assert-FailureCode {
    param([string]$Name, [hashtable]$Parameters, [string]$Expected)
    # Capture every output stream: only the fixed code may leave the classifier.
    $output = @(& { Get-QaProcessExitFailureCode @Parameters } *>&1)
    Assert-Check $Name ($output.Count -eq 1 -and $output[0] -is [string] -and $output[0] -ceq $Expected)
}
try {
    $secretSentinel = 'QA_TEST_SECRET_MUST_NEVER_BE_PRINTED'
    $missingJdkRevision = "E: Version '21.0.12.1+1-1~22.04.4' for 'openjdk-21-jdk-headless' was not found"
    Assert-FailureCode 'apt_missing_pinned_jdk_revision_stderr' @{ ImageBuild = $true; StandardError = $missingJdkRevision } 'QA_BUILD_JDK_REVISION_UNAVAILABLE'
    Assert-FailureCode 'apt_missing_updated_jdk_revision_stderr' @{ ImageBuild = $true; StandardError = $missingJdkRevision.Replace('21.0.12.1+1-1~22.04.4', '21.0.13.0.3+1-1~22.04.1') } 'QA_BUILD_JDK_REVISION_UNAVAILABLE'
    Assert-FailureCode 'buildkit_missing_pinned_jdk_revision_stdout' @{ ImageBuild = $true; StandardOutput = "#12 8.427 $missingJdkRevision" } 'QA_BUILD_JDK_REVISION_UNAVAILABLE'
    Assert-FailureCode 'crlf_build_output_emits_code_only' @{ ImageBuild = $true; StandardError = "$secretSentinel`r`n#12 8.427 $missingJdkRevision`r`n$secretSentinel" } 'QA_BUILD_JDK_REVISION_UNAVAILABLE'
    Assert-FailureCode 'unknown_build_failure_emits_code_only' @{ ImageBuild = $true; StandardOutput = $secretSentinel; StandardError = "arbitrary build exception: $secretSentinel" } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'empty_build_output_is_generic' @{ ImageBuild = $true; StandardOutput = ''; StandardError = '' } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'null_build_output_is_generic' @{ ImageBuild = $true; StandardOutput = $null; StandardError = $null } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'malformed_array_output_is_generic' @{ ImageBuild = $true; StandardOutput = @($missingJdkRevision, $secretSentinel) } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'malformed_object_output_is_generic' @{ ImageBuild = $true; StandardError = [pscustomobject]@{ message = $missingJdkRevision; secret = $secretSentinel } } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'different_package_is_generic' @{ ImageBuild = $true; StandardError = $missingJdkRevision.Replace('openjdk-21-jdk-headless', 'openjdk-17-jdk-headless') } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'malformed_revision_is_generic' @{ ImageBuild = $true; StandardError = $missingJdkRevision.Replace('21.0.12.1+1-1~22.04.4', $secretSentinel) } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'command_line_pin_alone_is_generic' @{ ImageBuild = $true; StandardError = "apt-get install openjdk-21-jdk-headless=21.0.12.1+1-1~22.04.4 $secretSentinel" } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'quoted_diagnostic_is_generic' @{ ImageBuild = $true; StandardError = "command argument: $missingJdkRevision" } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'incomplete_apt_diagnostic_is_generic' @{ ImageBuild = $true; StandardError = $missingJdkRevision.Replace(' was not found', '') } 'QA_IMAGE_BUILD_FAILED'
    Assert-FailureCode 'nonbuild_missing_jdk_diagnostic_keeps_process_code' @{ StandardError = $missingJdkRevision } 'QA_PROCESS_EXIT_FAILED'
    Assert-FailureCode 'nonbuild_unknown_failure_keeps_process_code' @{ StandardOutput = $secretSentinel; StandardError = $secretSentinel } 'QA_PROCESS_EXIT_FAILED'
    $history = ((1..13 | ForEach-Object { "${_}:true" }) -join ',')
    Assert-QaFlywayHistory -Actual $history
    Assert-Check 'all_thirteen_migrations_succeeded' $true
    Assert-Rejected 'failed_future_migration_not_hidden' { Assert-QaFlywayHistory -Actual ($history + ',14:false') }
    Assert-Rejected 'failed_v13_rejected' { Assert-QaFlywayHistory -Actual ($history.Replace('13:true', '13:false')) }
    Assert-Rejected 'missing_v12_rejected' { Assert-QaFlywayHistory -Actual ($history.Replace(',12:true', '')) }
    Assert-Rejected 'unversioned_migration_rejected' { Assert-QaFlywayHistory -Actual ($history + ',unversioned:true') }
    # Build the expected schedule independently from hourly/evening arithmetic.
    $blocks = @(foreach ($hour in 0..17) {
        $number = if ($hour -lt 12) { $hour + 11 } else { $hour - 11 }
        'B{0:00}:{1}:{2}:60' -f $number, ($hour * 60), (($hour + 1) * 60)
    }) + @(foreach ($evening in 0..3) {
        'B{0:00}:{1}:{2}:90' -f ($evening + 7), (1080 + $evening * 90), (1170 + $evening * 90)
    })
    $mapping = $blocks -join '|'
    Assert-QaTimeBlockMapping -Actual $mapping
    Assert-Check 'full_day_22_blocks_keep_legacy_codes' $true
    Assert-Rejected 'missing_midnight_block_rejected' { Assert-QaTimeBlockMapping -Actual (($blocks | Select-Object -Skip 1) -join '|') }
    Assert-Rejected 'duplicate_block_rejected' { Assert-QaTimeBlockMapping -Actual ($mapping + '|B01:720:780:60') }
    Assert-Rejected 'legacy_code_renumbering_rejected' { Assert-QaTimeBlockMapping -Actual ($mapping.Replace('B01:720:780:60', 'B23:720:780:60')) }
    Assert-Rejected 'evening_duration_change_rejected' { Assert-QaTimeBlockMapping -Actual ($mapping.Replace('B07:1080:1170:90', 'B07:1080:1170:60')) }
    Assert-Rejected 'midnight_offset_change_rejected' { Assert-QaTimeBlockMapping -Actual ($mapping.Replace('B11:0:60:60', 'B11:1:61:60')) }
    [void](New-Item -ItemType Directory -Path (Join-Path $testRoot '.local'))
    $destination = Join-Path $testRoot ".local/production-image-release-qa-$([Guid]::NewGuid().ToString()).json"
    Assert-Check 'optional_destination_is_empty' ($null -eq (Assert-QaResultPath -RepositoryRoot $testRoot))
    Assert-Check 'fresh_uuid_destination_is_allowed' ((Assert-QaResultPath -Path $destination -RepositoryRoot $testRoot) -ceq $destination)
    Assert-Rejected 'relative_destination_rejected' { Assert-QaResultPath -Path '.local/production-image-release-qa-00000000000000000000000000000000.json' -RepositoryRoot $testRoot }
    Assert-Rejected 'outside_local_parent_rejected' { Assert-QaResultPath -Path (Join-Path $testRoot 'production-image-release-qa-00000000000000000000000000000000.json') -RepositoryRoot $testRoot }
    Assert-Rejected 'nested_local_parent_rejected' { Assert-QaResultPath -Path (Join-Path $testRoot '.local/nested/production-image-release-qa-00000000000000000000000000000000.json') -RepositoryRoot $testRoot }
    Assert-Rejected 'arbitrary_filename_rejected' { Assert-QaResultPath -Path (Join-Path $testRoot '.local/result.json') -RepositoryRoot $testRoot }
    $summary = [ordered]@{
        passed = $false; checks = @('synthetic_only'); cleanupVerified = $true; resourcesKept = $false
        developmentDatabaseAccessed = $false; actualPushSent = $false; restoreScope = 'synthetic QA cluster'
        project = 'must-not-export'; error = 'must-not-export'; secret = 'must-not-export'
    }
    Write-QaResultExport -Path $destination -RepositoryRoot $testRoot -Summary $summary
    $exported = Get-Content -Raw -LiteralPath $destination | ConvertFrom-Json
    Assert-Check 'failed_summary_preserves_safety_flags' ((-not $exported.passed) -and $exported.cleanupVerified -and (-not $exported.resourcesKept) -and (-not $exported.developmentDatabaseAccessed) -and (-not $exported.actualPushSent))
    Assert-Check 'only_seven_allowlisted_fields_exported' (($exported.PSObject.Properties.Name | Sort-Object) -join ',' -ceq 'actualPushSent,checks,cleanupVerified,developmentDatabaseAccessed,passed,resourcesKept,restoreScope')
    Assert-Check 'private_values_not_exported' (-not (Get-Content -Raw -LiteralPath $destination).Contains('must-not-export'))
    $before = [IO.File]::ReadAllText($destination)
    Assert-Rejected 'existing_destination_rejected' { Assert-QaResultPath -Path $destination -RepositoryRoot $testRoot }
    Assert-Rejected 'existing_destination_not_overwritten' { Write-QaResultExport -Path $destination -RepositoryRoot $testRoot -Summary $summary }
    Assert-Check 'existing_content_unchanged' ([IO.File]::ReadAllText($destination) -ceq $before)

    $linkedRoot = Join-Path $testRoot 'linked'
    $outside = Join-Path $testRoot 'outside'
    [void](New-Item -ItemType Directory -Path $linkedRoot)
    [void](New-Item -ItemType Directory -Path $outside)
    $linkKind = if ($IsWindows) { 'Junction' } else { 'SymbolicLink' }
    [void](New-Item -ItemType $linkKind -Path (Join-Path $linkedRoot '.local') -Target $outside)
    Assert-Rejected 'parent_link_rejected' { Assert-QaResultPath -Path (Join-Path $linkedRoot '.local/production-image-release-qa-00000000000000000000000000000000.json') -RepositoryRoot $linkedRoot }
    # A directory reparse point at an expected JSON destination is also unsafe.
    $targetLink = Join-Path $testRoot '.local/production-image-release-qa-00000000000000000000000000000000.json'
    [void](New-Item -ItemType $linkKind -Path $targetLink -Target $outside)
    Assert-Rejected 'target_link_rejected' { Assert-QaResultPath -Path $targetLink -RepositoryRoot $testRoot }
    $successPath = Join-Path $testRoot ".local/production-image-release-qa-$([Guid]::NewGuid().ToString('N')).json"
    $summary.passed = $true
    Write-QaResultExport -Path $successPath -RepositoryRoot $testRoot -Summary $summary
    Assert-Check 'successful_summary_exported' ((Get-Content -Raw -LiteralPath $successPath | ConvertFrom-Json).passed)
    Write-Output "Production QA result export passed: $($testChecks.Count) isolated checks; no Docker or DB access."
} finally {
    $fullTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ([IO.Path]::GetDirectoryName($fullTestRoot) -cne $testLocalRoot -or [IO.Path]::GetFileName($fullTestRoot) -cnotmatch '^production-qa-export-unit-[a-f0-9]{32}$') { throw 'QA_EXPORT_TEST_CLEANUP_REJECTED' }
    if (Test-Path -LiteralPath $fullTestRoot) { Remove-Item -LiteralPath $fullTestRoot -Recurse -Force }
}
