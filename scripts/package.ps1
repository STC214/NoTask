param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    if (!$SkipBuild) {
        if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build-icons.ps1')) {
            & (Join-Path $PSScriptRoot 'build-icons.ps1')
        }
        & cmd.exe /d /c '.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --console=plain > dist\build.log 2>&1'
        if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed; see dist/build.log' }
    }
    $sdk = $env:ANDROID_HOME
    if (!$sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (!$sdk) { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT' }
    $tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (!$tools) { throw 'Android build-tools missing' }
    $androidUserHome = $env:ANDROID_USER_HOME
    if (!$androidUserHome) { $androidUserHome = Join-Path $env:USERPROFILE '.android' }
    $key = Join-Path $androidUserHome 'debug.keystore'
    if (!(Test-Path -LiteralPath $key)) { throw 'Debug keystore missing; build assembleDebug first' }
    $metadata = Get-Content 'app/build/outputs/apk/release/output-metadata.json' -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $aligned = Join-Path $dist 'release-aligned-unsigned.apk'
    $signed = Join-Path $dist "NoTask-$version-release-test-signed.apk"
    & (Join-Path $tools.FullName 'zipalign.exe') -P 16 -f 4 'app/build/outputs/apk/release/app-release-unsigned.apk' $aligned
    if ($LASTEXITCODE -ne 0) { throw 'ZIP alignment failed' }
    # Optimized Release code, explicitly signed with the local Android test key.
    # Never commit a private signing key or use this test certificate for production identity.
    & cmd.exe /d /c "`"$($tools.FullName)\apksigner.bat`" sign --ks `"$key`" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out `"$signed`" `"$aligned`" > dist\signing.log 2>&1"
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & cmd.exe /d /c "`"$($tools.FullName)\apksigner.bat`" verify --verbose `"$signed`" > dist\signature-verification.log 2>&1"
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    & (Join-Path $tools.FullName 'zipalign.exe') -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment check failed' }
    & python (Join-Path $PSScriptRoot 'verify-apk.py') $signed
    if ($LASTEXITCODE -ne 0) { throw 'Post-R8 module entry verification failed' }
    foreach ($variant in @('debug', 'release')) {
        [xml]$lint = Get-Content "app/build/reports/lint-results-$variant.xml"
        if (@($lint.issues.issue).Where({ $null -ne $_ }).Count -ne 0) {
            throw "Lint issues remain in $variant"
        }
    }
    Copy-Item 'app/build/outputs/apk/debug/app-debug.apk' (Join-Path $dist "NoTask-$version-debug.apk") -Force
    Get-ChildItem $dist -Filter 'NoTask-*.apk' | ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        "$($hash.Hash.ToLowerInvariant())  $($_.Name)"
    } | Set-Content (Join-Path $dist 'SHA256SUMS.txt') -Encoding ASCII
    Write-Output "PACKAGE_OK $signed"
    Get-Content (Join-Path $dist 'SHA256SUMS.txt')
} finally { Pop-Location }
