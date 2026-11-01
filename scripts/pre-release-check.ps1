# Pre-release gate (open-source trio + cipher shipped as artifact only)
# ASCII-only on purpose: Windows PowerShell 5.1 reads UTF-8 without BOM as ANSI.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/pre-release-check.ps1 -Root <repo-root> [-Stage <stage-dir>]
#
# Fails (exit 1) when:
#   1. binary artifacts present: *.aar / *.jar / *.so (gradle-wrapper.jar allowed)
#   2. cipher implementation source present (com.agentsdk.cipher outside contract tests)
#   3. docs red-line gate reports hits > 0
#   4. -Stage given and count gap != AllowedStageGap
param(
  [Parameter(Mandatory = $true)][string]$Root,
  [string]$Stage = '',
  [int]$AllowedStageGap = 1,
  [string]$GateScript = ''
)
$ErrorActionPreference = 'Stop'
$fail = @()

if (-not (Test-Path -LiteralPath $Root)) { Write-Output "MISSING_ROOT: $Root"; exit 2 }
$Root = (Resolve-Path -LiteralPath $Root).Path

# 1) binary artifacts
$bin = Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object { $_.Extension -in '.aar','.jar','.so' } |
  Where-Object { $_.Name -ne 'gradle-wrapper.jar' -and $_.FullName -notmatch '\\build\\|\\\.gradle\\|\\\.kotlin\\|\\node_modules\\' }
if ($bin) {
  $names = @($bin | ForEach-Object { $_.FullName.Substring($Root.Length + 1) })
  $fail += 'BINARY_ARTIFACT_PRESENT(' + $names.Count + '): ' + (($names | Select-Object -First 8) -join ', ')
}
Write-Output ('CHECK binary: {0} violation(s)' -f @($bin).Count)

# 2) cipher implementation source
$impl = Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object { $_.Extension -in '.kt','.java' } |
  Where-Object {
    $_.FullName -notmatch '\\build\\' -and
    $_.FullName -notmatch 'cipher-contract-test[\\/]src[\\/]test' -and
    (Select-String -LiteralPath $_.FullName -Pattern 'class\s+KeystoreCredentialCipher|package\s+com\.agentsdk\.cipher\s*$' -Quiet)
  }
if ($impl) {
  $names2 = @($impl | ForEach-Object { $_.FullName.Substring($Root.Length + 1) })
  $fail += 'CIPHER_IMPL_SOURCE_PRESENT(' + $names2.Count + '): ' + (($names2 | Select-Object -First 8) -join ', ')
}
Write-Output ('CHECK cipher-impl: {0} violation(s)' -f @($impl).Count)

# 3) docs red-line gate
if ($GateScript -ne '') { $gate = $GateScript } else { $gate = Join-Path $Root 'scripts/gate.mjs' }
if (Test-Path -LiteralPath $gate) {
  $docs = Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -eq '.md' } |
    Where-Object { $_.FullName -notmatch '\\build\\|\\node_modules\\' }
  $out = & node $gate @($docs | ForEach-Object { $_.FullName }) 2>&1 | Out-String
  $hit = ([regex]::Match($out, 'hits=(\d+)')).Groups[1].Value
  Write-Output ('CHECK docs-gate: hits={0}' -f $hit)
  if ($hit -and [int]$hit -gt 0) { $fail += "DOC_REDLINE_HITS: $hit" }
} else {
  Write-Output 'WARN docs-gate: gate.mjs not found (skipped)'
}

# 4) stage vs repo counts
if ($Stage -ne '') {
  if (-not (Test-Path -LiteralPath $Stage)) {
    $fail += "MISSING_STAGE: $Stage"
  } else {
    $repoCount = (Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -notmatch '\\build\\|\\\.gradle\\|\\\.kotlin\\|\\\.git\\' -and $_.Name -ne 'local.properties' }).Count
    $stageCount = (Get-ChildItem -LiteralPath $Stage -Recurse -File -ErrorAction SilentlyContinue).Count
    $gap = $repoCount - $stageCount
    Write-Output ('CHECK counts: repo={0} stage={1} gap={2} (allowed {3})' -f $repoCount, $stageCount, $gap, $AllowedStageGap)
    if ($gap -ne $AllowedStageGap) { $fail += "COUNT_GAP_UNEXPECTED: repo=$repoCount stage=$stageCount" }
  }
}

if ($fail.Count -gt 0) {
  Write-Output ''
  foreach ($f in $fail) { Write-Output "FAIL $f" }
  Write-Output 'PRE_RELEASE_VERDICT: FAIL'
  exit 1
}
Write-Output ''
Write-Output 'PRE_RELEASE_VERDICT: PASS'
exit 0
