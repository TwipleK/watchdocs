# One-click update: builds WatchDocs and installs it on every connected watch and phone.
#   update.bat          -> build + install
#   update.bat -Pair    -> pair a new device first (watch or phone), then build + install
param([switch]$Pair, [switch]$NoBuild)

Set-Location $PSScriptRoot
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$wearApk = Join-Path $PSScriptRoot "wear\build\outputs\apk\release\wear-release.apk"
$phoneApk = Join-Path $PSScriptRoot "mobile\build\outputs\apk\release\mobile-release.apk"

function Show-Header($text) { Write-Host ""; Write-Host "== $text ==" -ForegroundColor Cyan }

& $adb start-server 2>$null | Out-Null

if ($Pair) {
    Show-Header "Pair a device"
    Write-Host "On the device: Developer options > Wireless debugging > Pair device with pairing code"
    $addr = Read-Host "IP address & port shown under the code (e.g. 192.168.178.32:37011)"
    $code = Read-Host "6-digit pairing code"
    & $adb pair $addr $code
}

if (-not $NoBuild) {
    Show-Header "Building"
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed - see build.log" -ForegroundColor Red
        exit 1
    }
}

Show-Header "Finding devices"
# Paired devices with Wireless debugging on announce themselves on the Wi-Fi; connect to all of them.
foreach ($line in (& $adb mdns services 2>$null)) {
    if ($line -match '_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)') {
        & $adb connect $Matches[1] 2>$null | Out-Null
    }
}
Start-Sleep -Seconds 1

# Same device can show up twice (mDNS name + IP); keep one connection per serial number.
$targets = @{}
foreach ($line in (& $adb devices)) {
    if ($line -match '^(\S+)\s+device$') {
        $conn = $Matches[1]
        $serial = (& $adb -s $conn shell getprop ro.serialno 2>$null | Out-String).Trim()
        if (-not $serial) { $serial = $conn }
        if ($serial -and -not $targets.ContainsKey($serial)) { $targets[$serial] = $conn }
    }
}

if ($targets.Count -eq 0) {
    Write-Host "No devices found." -ForegroundColor Yellow
    Write-Host "Turn on Wireless debugging on the watch/phone (same Wi-Fi as this PC)."
    Write-Host "First time with a device? Run:  update.bat -Pair"
    Read-Host "Press Enter to close"
    exit 1
}

$results = @()
foreach ($serial in $targets.Keys) {
    $conn = $targets[$serial]
    $model = (& $adb -s $conn shell getprop ro.product.model 2>$null | Out-String).Trim()
    $traits = (& $adb -s $conn shell getprop ro.build.characteristics 2>$null | Out-String).Trim()
    $isWatch = $traits -match 'watch'
    $apk = if ($isWatch) { $wearApk } else { $phoneApk }
    $kind = if ($isWatch) { "watch" } else { "phone" }
    Show-Header "Installing on $model ($kind)"
    $out = (& $adb -s $conn install -r $apk 2>&1 | Out-String)
    if ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
        Write-Host "Installed build has a different signature (old debug build)." -ForegroundColor Yellow
        $yes = Read-Host "Uninstall it and install the new one? Documents stored in it will be deleted. (y/n)"
        if ($yes -match '^[yYjJ]') {
            & $adb -s $conn uninstall com.watchdocs.app 2>&1 | Out-Null
            $out = (& $adb -s $conn install -r $apk 2>&1 | Out-String)
        }
    }
    Write-Host $out.Trim()
    $ok = $out -match 'Success'
    $results += [pscustomobject]@{ Device = $model; Type = $kind; Result = $(if ($ok) { "Updated" } else { "FAILED" }) }
}

Show-Header "Done"
$results | Format-Table -AutoSize | Out-String | Write-Host
if (-not ($results | Where-Object Type -eq "phone")) {
    Write-Host "Phone not found. To update it too: enable Wireless debugging on the phone and run 'update.bat -Pair' once." -ForegroundColor Yellow
}
Read-Host "Press Enter to close"
