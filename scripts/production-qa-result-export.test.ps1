#requires -Version 7.2
# Path/export unit checks only. The QA entry point, Docker and DB are never run.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$sourcePath = Join-Path $PSScriptRoot 'Test-ProductionContainers.ps1'
$tokens = $null
$parseErrors = $null
$sourceAst = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'QA_EXPORT_SOURCE_PARSE_FAILED' }
foreach ($name in @('Assert-QaResultPath', 'Write-QaResultExport')) {
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
try {
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
