# Builds both APKs:
#   wear/build/outputs/apk/release/wear-release.apk      (install on the watch)
#   mobile/build/outputs/apk/release/mobile-release.apk  (install on the phone)
Set-Location $PSScriptRoot
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$sdkmanager = "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"

# Accept the Android SDK licenses (needed once).
(1..30 | ForEach-Object { "y" }) | & $sdkmanager --sdk_root="$env:ANDROID_HOME" --licenses | Out-Null

if (-not (Test-Path .\gradlew.bat)) {
    & "$env:LOCALAPPDATA\Android\gradle\gradle-8.11.1\bin\gradle.bat" wrapper --gradle-version 8.11.1 -q
}
[Environment]::CurrentDirectory = $PSScriptRoot
& .\gradlew.bat assembleRelease --console=plain 2>&1 | ForEach-Object { "$_" } | Out-File -Encoding utf8 build.log
$code = $LASTEXITCODE
Get-Content build.log -Tail 25
Write-Host "EXIT CODE: $code"
exit $code
