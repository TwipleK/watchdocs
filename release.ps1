# Publishes a new GitHub release with both APKs.
#   1. Bump watchdocs.versionCode / watchdocs.versionName in gradle.properties
#   2. Commit + push
#   3. Run:  powershell -ExecutionPolicy Bypass -File .\release.ps1
# Needs ../signing/keystore.properties (the release key). Without it, users can't update.
Set-Location $PSScriptRoot
if (-not (Test-Path ..\signing\keystore.properties)) { Write-Host "Release key missing (..\signing)." -ForegroundColor Red; exit 1 }

$props = Get-Content gradle.properties
$version = (($props | Select-String '^watchdocs.versionName=(.+)$').Matches[0].Groups[1].Value).Trim()
$tag = "v$version"

& powershell -ExecutionPolicy Bypass -File .\build.ps1
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed - see build.log" -ForegroundColor Red; exit 1 }

$dist = Join-Path $PSScriptRoot "dist"
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item wear\build\outputs\apk\release\wear-release.apk "$dist\WatchDocs-watch.apk" -Force
Copy-Item mobile\build\outputs\apk\release\mobile-release.apk "$dist\WatchDocs-phone.apk" -Force
Copy-Item install-windows.bat "$dist\install-windows.bat" -Force

$notes = @"
## Install
- **Phone:** open this on your phone: https://github.com/TwipleK/watchdocs/releases/latest/download/WatchDocs-phone.apk
- **Watch (Windows PC):** download ``install-windows.bat`` below and double-click it, or run in PowerShell:
  ``irm https://raw.githubusercontent.com/TwipleK/watchdocs/main/install.ps1 | iex``

See the README for details.
"@
gh release create $tag "$dist\WatchDocs-watch.apk" "$dist\WatchDocs-phone.apk" "$dist\install-windows.bat" --title "WatchDocs $version" --notes $notes
