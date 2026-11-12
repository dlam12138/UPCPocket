#Requires -Version 7.0
<#
.SYNOPSIS
  生成 UPC Pocket 新版（com.upcpocket.next）应用内更新清单、独立签名和人工校验文件。

.DESCRIPTION
  只做本地生成与自检，不上传 GitHub Release。APK、清单、签名、SHA256SUMS 必须
  作为同一次发布的附件一起上传；旧包 update.json/update.sig 保持原样。
  发布后不要原地替换资产，修复必须提升 versionCode。

  私钥路径只从环境变量 UPCPOCKET_UPDATE_PRIVATE_KEY 读取，脚本不打印、不复制私钥。
  公钥文件 docs/updates/upcpocket-update-public-key.b64 与应用内 BuildConfig 同源。

.EXAMPLE
  $env:UPCPOCKET_UPDATE_PRIVATE_KEY = '<仓库外私钥路径>'
  .\tools\release_update.ps1 `
    -Apk '<构建目录>\UPC-Pocket-2.0.4.apk' `
    -VersionName '2.0.4' -VersionCode 25 `
    -NotesPath '<更新说明路径>' `
    -OutputDirectory '<空输出目录>'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Apk,
    [Parameter(Mandatory)] [string] $VersionName,
    [Parameter(Mandatory)] [int] $VersionCode,
    [Parameter(Mandatory)] [string] $NotesPath,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [string] $Tag = "v$VersionName",
    [string] $PublishedAt,
    [string] $ExpectedSignerSha256 = 'c8e461698efe19ee6ad5fb635dd28eaf860596caea2b2756efa5ce6b2051a23c',
    # 仅供本机 debug 受控 E2E 使用；正式发布保持默认生产公钥。
    [string] $ExpectedPublicKeyFile = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'com.upcpocket.next'
$maxApkBytes = 50L * 1024L * 1024L
$minApkBytes = 256L * 1024L
$publicKeyFile = Join-Path $repoRoot 'docs\updates\upcpocket-update-public-key.b64'

function Fail([string] $message) {
    throw "release_update: $message"
}

function Get-SdkRoot {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($line) {
            return ($line -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
        }
    }
    Fail '找不到 Android SDK；请设置 ANDROID_HOME 或在 local.properties 写 sdk.dir。'
}

function Get-BuildTool([string] $sdkRoot, [string] $name) {
    $buildTools = Join-Path $sdkRoot 'build-tools'
    $version = Get-ChildItem -LiteralPath $buildTools -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName $name) } |
        Select-Object -First 1
    if (-not $version) { Fail "找不到 build-tools/$name" }
    return (Join-Path $version.FullName $name)
}

function Get-Field([string[]] $lines, [string] $pattern) {
    $line = $lines | Where-Object { $_ -match $pattern } | Select-Object -First 1
    if (-not $line) { return $null }
    return $line
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { Fail "APK 不存在：$Apk" }
if (-not (Test-Path -LiteralPath $NotesPath -PathType Leaf)) { Fail "更新说明不存在：$NotesPath" }
$trustedPublicKeyFile = if ($ExpectedPublicKeyFile) {
    (Resolve-Path -LiteralPath $ExpectedPublicKeyFile -ErrorAction Stop).Path
} else {
    $publicKeyFile
}
if (-not (Test-Path -LiteralPath $trustedPublicKeyFile -PathType Leaf)) {
    Fail '缺少更新公钥文件'
}
if ($VersionName -notmatch '^[0-9]+(\.[0-9]+){1,3}(-[0-9A-Za-z.-]{1,32})?$') {
    Fail 'VersionName 格式非法'
}
if ($VersionCode -le 0) { Fail 'VersionCode 必须为正整数' }
if ($Tag -ne "v$VersionName") { Fail 'Tag 必须严格等于 v<VersionName>' }
if ([IO.Path]::GetFileName($Apk) -ne "UPC-Pocket-$VersionName.apk") {
    Fail "APK 文件名必须为 UPC-Pocket-$VersionName.apk"
}
if (-not $PublishedAt) {
    $PublishedAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
}
if ($PublishedAt -notmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$') {
    Fail 'PublishedAt 必须是 UTC yyyy-MM-ddTHH:mm:ssZ'
}

$sdkRoot = Get-SdkRoot
$aapt2 = Get-BuildTool $sdkRoot 'aapt2.exe'
$apksigner = Get-BuildTool $sdkRoot 'apksigner.bat'
$badging = & $aapt2 dump badging $Apk
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 读取 APK 失败' }
$packageLine = Get-Field $badging '^package:'
if (-not $packageLine) { Fail 'APK 缺少 package 信息' }
if ($packageLine -notmatch "name='([^']+)'") { Fail '无法解析包名' }
$apkPackage = $Matches[1]
if ($apkPackage -ne $packageName) { Fail "包名不匹配：$apkPackage" }
if ($packageLine -notmatch "versionCode='([0-9]+)'") { Fail '无法解析 versionCode' }
$apkCode = [int]$Matches[1]
if ($apkCode -ne $VersionCode) { Fail "APK versionCode=$apkCode，与 -VersionCode=$VersionCode 不一致" }
if ($packageLine -notmatch "versionName='([^']+)'") { Fail '无法解析 versionName' }
$apkName = $Matches[1]
if ($apkName -ne $VersionName) { Fail "APK versionName=$apkName，与 -VersionName=$VersionName 不一致" }
$minSdkLine = Get-Field $badging '^minSdkVersion:'
if (-not $minSdkLine -or $minSdkLine -notmatch "minSdkVersion:'([0-9]+)'") {
    Fail '无法解析 minSdkVersion'
}
$minSdk = [int]$Matches[1]

$apkInfo = Get-Item -LiteralPath $Apk
$sizeBytes = [long]$apkInfo.Length
if ($sizeBytes -lt $minApkBytes -or $sizeBytes -gt $maxApkBytes) {
    Fail "APK 大小超出边界：$sizeBytes"
}
$sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()

$certOutput = & $apksigner verify --print-certs $Apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail 'apksigner 校验失败' }
$certLine = $certOutput | Where-Object { $_ -match 'certificate SHA-256 digest:' } | Select-Object -First 1
if (-not $certLine) { Fail '无法读取 APK 签名证书' }
$signerSha256 = ($certLine -replace '^.*certificate SHA-256 digest:\s*', '').Trim().ToLowerInvariant()
if ($signerSha256 -ne $ExpectedSignerSha256.ToLowerInvariant()) {
    Fail "APK 签名证书不匹配：$signerSha256"
}

$notes = Get-Content -LiteralPath $NotesPath -Encoding utf8 |
    ForEach-Object { $_.TrimEnd("`r") } |
    Where-Object { $_.Trim().Length -gt 0 }
if ($notes.Count -lt 3 -or $notes.Count -gt 5) { Fail '更新说明必须为 3–5 条纯文本' }
$totalChars = 0
foreach ($note in $notes) {
    if ($note.Length -gt 180 -or $note -ne $note.Trim()) { Fail "更新说明长度/首尾空格非法：$note" }
    if ($note -match '[\x00-\x1f\x7f]') { Fail '更新说明不能包含控制字符/换行' }
    $totalChars += $note.Length
}
if ($totalChars -gt 900) { Fail '更新说明总长度超限' }

$manifest = [ordered]@{
    schemaVersion = 1
    packageName = $packageName
    versionName = $VersionName
    versionCode = $VersionCode
    minSdk = $minSdk
    tag = $Tag
    assetName = "UPC-Pocket-$VersionName.apk"
    sizeBytes = $sizeBytes
    sha256 = $sha256
    publishedAt = $PublishedAt
    notes = @($notes)
}
$json = $manifest | ConvertTo-Json -Depth 4
$json = ($json -replace "`r`n", "`n") + "`n"
$manifestBytes = [Text.UTF8Encoding]::new($false).GetBytes($json)

$privateKeyPath = $env:UPCPOCKET_UPDATE_PRIVATE_KEY
if (-not $privateKeyPath) { Fail '请设置 UPCPOCKET_UPDATE_PRIVATE_KEY（仓库外私钥路径）' }
if (-not (Test-Path -LiteralPath $privateKeyPath -PathType Leaf)) { Fail '更新私钥不存在' }
$ecdsa = [Security.Cryptography.ECDsa]::Create()
try {
    $ecdsa.ImportFromPem([IO.File]::ReadAllText($privateKeyPath))
    $publicDer = $ecdsa.ExportSubjectPublicKeyInfo()
    $publicB64 = [Convert]::ToBase64String($publicDer)
    $repoPublicB64 = (Get-Content -LiteralPath $trustedPublicKeyFile -Raw).Trim()
    if ($publicB64 -ne $repoPublicB64) {
        Fail '私钥对应的公钥与 docs/updates/upcpocket-update-public-key.b64 不一致'
    }
    $signature = $ecdsa.SignData(
        $manifestBytes,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence
    )
    if ($signature.Length -lt 64 -or $signature.Length -gt 72) {
        Fail "签名 DER 长度异常：$($signature.Length)"
    }
    $verified = $ecdsa.VerifyData(
        $manifestBytes,
        $signature,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence
    )
    if (-not $verified) { Fail '清单签名自校验失败' }
} finally {
    $ecdsa.Dispose()
}

if (Test-Path -LiteralPath $OutputDirectory) {
    $existing = Get-ChildItem -LiteralPath $OutputDirectory -Force -ErrorAction SilentlyContinue
    if ($existing) { Fail "输出目录非空：$OutputDirectory" }
} else {
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
}
$apkDestination = Join-Path $OutputDirectory "UPC-Pocket-$VersionName.apk"
$manifestPath = Join-Path $OutputDirectory 'update-next.json'
$signaturePath = Join-Path $OutputDirectory 'update-next.sig'
Copy-Item -LiteralPath $Apk -Destination $apkDestination
[IO.File]::WriteAllBytes($manifestPath, $manifestBytes)
[IO.File]::WriteAllBytes($signaturePath, $signature)

$checksumLines = @(
    "$sha256  UPC-Pocket-$VersionName.apk",
    "$((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant())  update-next.json",
    "$((Get-FileHash -LiteralPath $signaturePath -Algorithm SHA256).Hash.ToLowerInvariant())  update-next.sig"
)
[IO.File]::WriteAllText(
    (Join-Path $OutputDirectory 'SHA256SUMS.txt'),
    ($checksumLines -join "`n") + "`n",
    [Text.UTF8Encoding]::new($false)
)

# 可选独立工具核对；openssl 不在 PATH 时跳过，不影响生成结果。
$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($openssl) {
    $publicPem = Join-Path ([IO.Path]::GetTempPath()) ("upcpocket-update-public-" + [Guid]::NewGuid() + '.pem')
    try {
        $pemBody = (Get-Content -LiteralPath $trustedPublicKeyFile -Raw).Trim() -split '(?<=\G.{64})' -join "`n"
        [IO.File]::WriteAllText(
            $publicPem,
            "-----BEGIN PUBLIC KEY-----`n$pemBody`n-----END PUBLIC KEY-----`n",
            [Text.UTF8Encoding]::new($false)
        )
        & $openssl.Source dgst -sha256 -verify $publicPem -signature $signaturePath $manifestPath | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail 'openssl 交叉验签失败' }
    } finally {
        Remove-Item -LiteralPath $publicPem -Force -ErrorAction SilentlyContinue
    }
}

Write-Host 'UPC Pocket update metadata prepared (NOT uploaded):'
Write-Host "  APK        : $apkDestination"
Write-Host "  version    : $VersionName ($VersionCode), minSdk $minSdk"
Write-Host "  size       : $sizeBytes"
Write-Host "  sha256     : $sha256"
Write-Host "  signer     : $signerSha256"
Write-Host "  update-next.json: $manifestPath"
Write-Host "  update-next.sig : $signaturePath"
Write-Host '  SHA256SUMS.txt'
