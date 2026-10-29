# 独立复现「闭源加密核心的契约测试全绿」。
#
# 三个通道，分工明确：
#   ① api.github.com 的 release 资产对象带 `digest` 字段（`sha256:…`）—— 这是**独立于下载通道**的指纹；
#   ② 下载通道取 `.aar` 的字节，自己算一遍 sha256 —— 与 ① 逐字比对 ⇒ 两条通道互相印证；
#   ③ 本仓的契约测试（公开源码）对着解出来的 `classes.jar` 跑。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/verify-cipher-contract.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/verify-cipher-contract.ps1 -Tag v1.1.18
#   powershell -ExecutionPolicy Bypass -File scripts/verify-cipher-contract.ps1 -Asset android-agent-sdk-cipher-v1.0.0-20261029.aar
#
# 退出码：0 = 通过；1 = 不通过；2 = 不可判（环境/网络问题，**不是**「通过」）。
param(
    [string]$Tag = '',
    [string]$Asset = 'android-agent-sdk-cipher-v1.0.0-20261029.aar',
    [int]$ExpectedCases = 12
)

$ErrorActionPreference = 'Stop'

# ⚠️⚠️ 变量名**别和上面那两个 param 撞**：PowerShell 的变量名是**大小写不敏感**的，
#    而 `[string]$Asset` 带**类型约束** ⇒ 写 `$asset = $chosen.Asset` 会把对象
#    **强制转成字符串**（"System.Management.Automation.PSCustomObject"），
#    后面 `.name` / `.size` / `.browser_download_url` 全变 $null ⇒ curl 收到空 URL。
#    2026-10-29 实踩（第一次跑就报 `curl: (2) no URL specified`）。
#    ⇒ 所以内部一律用 `$assetObj`。
$root    = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$repo    = 'genghv/android-agent-sdk'
$apiBase = "https://api.github.com/repos/$repo"
$libs    = Join-Path $root 'cipher-contract-test\libs'
$jarPath = Join-Path $libs 'cipher-classes.jar'
$tmpAar  = Join-Path $env:TEMP ("cipher-verify-" + [guid]::NewGuid().ToString('N') + '.aar')
$startedAt = Get-Date

$apiHeaders = @{ 'User-Agent' = 'mavis-verify' }

Write-Output '=== 1. 取 release 资产的 digest（独立于下载通道）==='
$chosen = $null
if ($Tag) {
    $rel = Invoke-RestMethod -Uri "$apiBase/releases/tags/$Tag" -Headers $apiHeaders
    $hit = @($rel.assets) | Where-Object { $_.name -eq $Asset } | Select-Object -First 1
    if ($hit) { $chosen = [pscustomobject]@{ Tag = $rel.tag_name; AssetObj = $hit } }
} else {
    $rels = Invoke-RestMethod -Uri "$apiBase/releases?per_page=10" -Headers $apiHeaders
    foreach ($r in @($rels)) {
        $hit = @($r.assets) | Where-Object { $_.name -eq $Asset } | Select-Object -First 1
        if ($hit) { $chosen = [pscustomobject]@{ Tag = $r.tag_name; AssetObj = $hit }; break }
    }
}
if (-not $chosen) {
    Write-Output "  找不到资产 $Asset（用 -Tag 指定 release，或 -Asset 换名字）"
    exit 2
}

$assetObj = $chosen.AssetObj
# ★ 逐项断言「这一步真的拿到了东西」——不然后面全是 $null 在传，
#   而「拿到空值」和「没跑」看起来一模一样。
if (-not $assetObj.name)                { Write-Output '  ✗ 资产对象没有 name（取资产这步没成功）'; exit 2 }
if (-not $assetObj.size)                { Write-Output '  ✗ 资产对象没有 size'; exit 2 }
if (-not $assetObj.browser_download_url) { Write-Output '  ✗ 资产对象没有 browser_download_url'; exit 2 }

$expectedDigest = ''
if ($assetObj.digest -and $assetObj.digest.StartsWith('sha256:')) {
    $expectedDigest = $assetObj.digest.Substring(7).ToLower()
}
Write-Output ("  release = {0}" -f $chosen.Tag)
Write-Output ("  asset   = {0}  {1} bytes" -f $assetObj.name, $assetObj.size)
Write-Output ("  digest  = {0}" -f $assetObj.digest)
Write-Output '  来源    = api.github.com 的资产对象（与下载通道**独立**）'

Write-Output ''
Write-Output '=== 2. 下载并自算 sha256（与 digest 逐字比对）==='
# 两条下载通道，按顺序试（有的网络里 github.com/.../download 不通，API 端点通）：
#   ① github.com/.../releases/download      —— 官方 CDN 通道
#   ② api.github.com/.../releases/assets/<id> —— API 通道（Accept: application/octet-stream）
# 指纹**始终**取自 api.github.com 资产对象的 `digest` 字段 ⇒ 与「取字节」是两条不同的数据。
#   走 ① 时是「CDN 字节 vs API 元数据」，互证最强；
#   走 ② 时是「API 元数据 vs API 内容端点」，仍是两种数据，但同源 ⇒ 如实写出来。
$channels = @()
if ($assetObj.browser_download_url) {
    $channels += [pscustomobject]@{ Name = 'github.com/releases/download（CDN）'; Url = $assetObj.browser_download_url; Extra = @{} }
}
if ($assetObj.url) {
    $channels += [pscustomobject]@{ Name = 'api.github.com/releases/assets（API）'; Url = $assetObj.url; Extra = @{ 'Accept' = 'application/octet-stream' } }
}
if ($channels.Count -eq 0) { Write-Output '  ✗ 这个资产没有可用的下载地址'; exit 2 }

$downloaded = $false
$usedChannel = ''
foreach ($ch in $channels) {
    for ($try = 1; $try -le 3; $try++) {
        # curl 的 stderr 在 ErrorActionPreference=Stop 下会变成终止错误 ⇒ 先降级再收回。
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $curlArgs = @('-sSL', '--fail', '--max-time', '180', '-A', 'mavis-verify', '-o', $tmpAar)
        foreach ($h in $ch.Extra.Keys) { $curlArgs += @('-H', ("{0}: {1}" -f $h, $ch.Extra[$h])) }
        $curlArgs += $ch.Url
        $curlMsg = & curl.exe @curlArgs 2>&1
        $curlRc = $LASTEXITCODE
        $ErrorActionPreference = $prevEap
        if ($curlRc -eq 0 -and (Test-Path -LiteralPath $tmpAar)) {
            $sz = (Get-Item -LiteralPath $tmpAar).Length
            if ($sz -eq [int64]$assetObj.size) {
                $downloaded = $true
                $usedChannel = $ch.Name
                break
            }
            Write-Output ("  · 通道「{0}」第 {1} 次：下载体积不对（{2} 字节，应为 {3}）⇒ 重试" -f $ch.Name, $try, $sz, $assetObj.size)
        } else {
            $why = ''
            foreach ($m in @($curlMsg)) { $why = ([string]$m).Trim(); break }
            Write-Output ("  · 通道「{0}」第 {1} 次失败：exit={2} {3}" -f $ch.Name, $try, $curlRc, $why)
        }
    }
    if ($downloaded) { break }
}
if (-not $downloaded) { Write-Output '  ✗ 所有下载通道都没取到字节（网络不通？）⇒ 不可判'; exit 2 }
$gotSize = (Get-Item -LiteralPath $tmpAar).Length
$actualDigest = (Get-FileHash -LiteralPath $tmpAar -Algorithm SHA256).Hash.ToLower()
Write-Output ("  下载通道 = {0}" -f $usedChannel)
Write-Output ("  下载体积 = {0} 字节（与 API 报的一致）" -f $gotSize)
Write-Output ("  本地算出的 sha256 = {0}" -f $actualDigest)
if ($expectedDigest) {
    if ($actualDigest -ne $expectedDigest) {
        Write-Output ("  ✗ 与 API 的 digest 不一致！期望 {0} 实际 {1}" -f $expectedDigest, $actualDigest)
        exit 1
    }
    Write-Output '  ✓ 与 API 的 digest 字段逐字一致（指纹来自资产元数据、字节来自下载通道 ⇒ 两条不同的数据互相印证）'
} else {
    Write-Output '  ⚠️ 该资产没有 digest 字段 ⇒ 没有独立通道可比对，本步只作记录（**不算「已核」**）'
}

Write-Output ''
Write-Output '=== 3. 解出 classes.jar ==='
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path -LiteralPath $libs)) { New-Item -ItemType Directory -Path $libs -Force | Out-Null }
$zip = [System.IO.Compression.ZipFile]::OpenRead($tmpAar)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
    if (-not $entry) { Write-Output '  ✗ 这个 .aar 里没有 classes.jar'; exit 1 }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $jarPath, $true)
} finally { $zip.Dispose() }
if (-not (Test-Path -LiteralPath $jarPath)) { Write-Output '  ✗ classes.jar 没落地'; exit 1 }
$jarSize = (Get-Item -LiteralPath $jarPath).Length
if ($jarSize -le 0) { Write-Output '  ✗ classes.jar 是空的'; exit 1 }
# 再验一下里面确实有被测的类（否则测的可能不是这个产物）。
$jarZip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $hasCipher = @($jarZip.Entries | Where-Object { $_.FullName -eq 'com/agentsdk/cipher/KeystoreCredentialCipher.class' }).Count -gt 0
} finally { $jarZip.Dispose() }
if (-not $hasCipher) {
    Write-Output '  ✗ classes.jar 里没有 KeystoreCredentialCipher.class ⇒ 不是被测的那个产物'
    exit 1
}
Write-Output ("  已解出 {0}  ({1} 字节)  含 KeystoreCredentialCipher.class" -f $jarPath, $jarSize)

Write-Output ''
Write-Output '=== 4. 跑公开的契约测试 ==='
$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle)) { Write-Output "  ✗ 找不到 {0}" -f $gradle; exit 2 }
$prevEap2 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $gradle -p $root -PwithCipherContract=true :cipher-contract-test:test --console=plain --rerun-tasks
$gradleRc = $LASTEXITCODE
$ErrorActionPreference = $prevEap2
Write-Output ("  gradle exit = {0}" -f $gradleRc)
if ($gradleRc -ne 0) { Write-Output '  ✗ gradle 没跑成功'; exit 2 }

Write-Output ''
Write-Output '=== 5. 读结果（只认本次跑出来的）==='
$resDir = Join-Path $root 'cipher-contract-test\build\test-results'
# ★ 只统计「本次运行之后才产生」的结果文件 ⇒ 免得上一轮的旧 XML 冒充这次的结果。
$files = @(Get-ChildItem -LiteralPath $resDir -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
           Where-Object { $_.LastWriteTime -ge $startedAt })
Write-Output ("  本次运行产生的结果文件 = {0} 个" -f $files.Count)
if ($files.Count -eq 0) {
    Write-Output '  ⇒ **没读到本次运行的结果文件** ⇒ 不可判（别把「没跑」当成「通过」）'
    exit 2
}
$exec = 0; $fail = 0; $err = 0; $skip = 0
$caseNames = New-Object System.Collections.Generic.HashSet[string]
foreach ($f in $files) {
    # ★ 结果 XML 必须按 UTF-8 读：Get-Content -Raw 会按 GBK 解码，把中文用例名读坏。
    $txt = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($f.FullName))
    if ($txt -match '<testsuite\b[^>]*>') {
        $tag = $Matches[0]
        foreach ($k in @('tests','failures','errors','skipped')) {
            if ($tag -match ($k + '="(\d+)"')) {
                $v = [int]$Matches[1]
                switch ($k) {
                    'tests'    { $exec += $v }
                    'failures' { $fail += $v }
                    'errors'   { $err  += $v }
                    'skipped'  { $skip += $v }
                }
            }
        }
    }
    # `name=` 前面不能紧跟单词字符，否则会误吃到 `classname=`。
    foreach ($m in [regex]::Matches($txt, '<testcase\b[^>]*?(?<![\w-])name="([^"]*)"')) {
        [void]$caseNames.Add($m.Groups[1].Value)
    }
}
Write-Output ("  执行次数 = {0}（debug/release 两个变体各跑一遍）" -f $exec)
Write-Output ("  契约条数 = {0}（去重后的用例名）" -f $caseNames.Count)
Write-Output ("  failures = {0}   errors = {1}   skipped = {2}" -f $fail, $err, $skip)
foreach ($n in ($caseNames | Sort-Object)) { Write-Output ("    · {0}" -f [string]$n) }

$rc = 0
if ($caseNames.Count -ne $ExpectedCases) {
    Write-Output ("  ⇒ 契约条数与预期不符：预期 {0} 实际 {1} ⇒ 不通过" -f $ExpectedCases, $caseNames.Count)
    $rc = 1
} elseif ($fail -gt 0 -or $err -gt 0) {
    Write-Output '  ⇒ 契约测试有红 ⇒ 不通过'
    $rc = 1
} elseif ($skip -gt 0) {
    Write-Output '  ⇒ 有用例被跳过 ⇒ 不算全绿（跳过要显式记成 skipped）'
    $rc = 1
} elseif ($exec -lt $caseNames.Count) {
    Write-Output '  ⇒ 执行次数少于条数 ⇒ 不可判'
    $rc = 2
} else {
    Write-Output '  ⇒ 契约测试全绿（第三方可独立复现）'
}

if (Test-Path -LiteralPath $tmpAar) { [System.IO.File]::Delete($tmpAar) }
exit $rc
