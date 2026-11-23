# WatchDocs installer for Windows.
# Run in PowerShell:
#   irm https://raw.githubusercontent.com/TwipleK/watchdocs/main/install.ps1 | iex
# It downloads Google's adb tool and the latest WatchDocs release, then installs it
# on your watch (and phone) over Wi-Fi. Nothing is installed on your PC.

$Repo = 'TwipleK/watchdocs'
$PackageName = 'com.watchdocs.app'
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'   # makes downloads much faster in Windows PowerShell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Work = Join-Path $env:LOCALAPPDATA 'WatchDocsInstaller'
New-Item -ItemType Directory -Force $Work | Out-Null

function Write-Step($text) { Write-Host ''; Write-Host "== $text ==" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host $text -ForegroundColor Green }
function Write-Bad($text) { Write-Host $text -ForegroundColor Red }

function Invoke-Adb {
    $out = & $script:Adb @args 2>&1 | ForEach-Object { "$_" }
    return ($out -join "`n").Trim()
}

function Find-Adb {
    $candidates = @()
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    $candidates += (Join-Path $Work 'platform-tools\adb.exe')
    $candidates += "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }

    Write-Step "Downloading Google's adb tool (about 7 MB)"
    $zip = Join-Path $Work 'platform-tools.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
    Expand-Archive $zip -DestinationPath $Work -Force
    Remove-Item $zip
    return (Join-Path $Work 'platform-tools\adb.exe')
}

function Get-Apk($name) {
    $path = Join-Path $Work $name
    Invoke-WebRequest "https://github.com/$Repo/releases/latest/download/$name" -OutFile $path -UseBasicParsing
    return $path
}

function Find-PairedDevice($kind) {
    # Devices paired before (with Wireless debugging on) announce themselves on the network.
    foreach ($line in ((Invoke-Adb mdns services) -split "`n")) {
        if ($line -match '_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)') { Invoke-Adb connect $Matches[1] | Out-Null }
    }
    foreach ($line in ((Invoke-Adb devices) -split "`n")) {
        if ($line -match '^(\d+\.\d+\.\d+\.\d+:\d+)\s+device$') {
            $conn = $Matches[1]
            $isWatch = (Invoke-Adb -s $conn shell getprop ro.build.characteristics) -match 'watch'
            if (($kind -eq 'watch') -eq $isWatch) {
                $model = Invoke-Adb -s $conn shell getprop ro.product.model
                $yes = Read-Host "Found $model. Install on it? (Y/n)"
                if ($yes -notmatch '^[nN]') { return $conn }
            }
        }
    }
    return $null
}

function Connect-Device($kind) {
    $known = Find-PairedDevice $kind
    if ($known) { return $known }

    Write-Step "Connect your $kind"
    if ($kind -eq 'watch') {
        Write-Host '1. On the watch: Settings > System > About > Versions > tap "Build number" 7 times.'
        Write-Host '2. Settings > Developer options > turn on "Wireless debugging".'
        Write-Host '3. Tap "Pair new device".'
    } else {
        Write-Host '1. On the phone: Settings > About phone > tap "Build number" 7 times'
        Write-Host '   (Samsung: About phone > Software information > Build number).'
        Write-Host '2. Settings > System > Developer options > turn on "Wireless debugging".'
        Write-Host '3. Tap "Wireless debugging" > "Pair device with pairing code".'
    }
    Write-Host "The $kind must be on the same Wi-Fi as this PC." -ForegroundColor Yellow
    Write-Host ''

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $addr = (Read-Host 'IP address & port shown with the code (e.g. 192.168.1.20:37099)').Trim()
        $code = (Read-Host '6-digit pairing code').Trim()
        $out = Invoke-Adb pair $addr $code
        if ($out -match 'Successfully paired') { break }
        Write-Bad "Pairing didn't work: $out"
        if ($attempt -eq 3) { return $null }
        Write-Host 'Try again (a new code appears each time you open "Pair new device").'
    }
    Write-Ok 'Paired.'

    # The connection port differs from the pairing port; the device announces it on the network.
    $ip = $addr.Split(':')[0]
    $target = $null
    for ($i = 0; $i -lt 10 -and -not $target; $i++) {
        foreach ($line in ((Invoke-Adb mdns services) -split "`n")) {
            if ($line -match ('_adb-tls-connect\._tcp\.?\s+(' + [regex]::Escape($ip) + ':\d+)')) { $target = $Matches[1] }
        }
        if (-not $target) { Start-Sleep -Seconds 1 }
    }
    if (-not $target) {
        $target = (Read-Host 'Now enter the IP address & port on the main "Wireless debugging" screen').Trim()
    }
    $out = Invoke-Adb connect $target
    if ($out -notmatch 'connected') {
        Write-Bad "Couldn't connect: $out"
        return $null
    }
    return $target
}

function Install-To($kind, $apk) {
    $target = Connect-Device $kind
    if (-not $target) { Write-Bad "Skipping the $kind."; return $false }

    $traits = Invoke-Adb -s $target shell getprop ro.build.characteristics
    $isWatch = $traits -match 'watch'
    if (($kind -eq 'watch') -ne $isWatch) {
        Write-Bad "Heads up: that device doesn't look like a $kind. Installing anyway."
    }

    Write-Step "Installing on your $kind"
    $out = Invoke-Adb -s $target install -r $apk
    if ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
        Write-Host 'An older WatchDocs build with a different signature is installed.' -ForegroundColor Yellow
        $yes = Read-Host 'Remove it and install the new one? Documents stored in it will be deleted. (y/n)'
        if ($yes -match '^[yYjJ]') {
            Invoke-Adb -s $target uninstall $PackageName | Out-Null
            $out = Invoke-Adb -s $target install -r $apk
        }
    }
    Invoke-Adb disconnect $target | Out-Null
    if ($out -match 'Success') { Write-Ok "WatchDocs is installed on your $kind."; return $true }
    Write-Bad "Install failed: $out"
    return $false
}

# ------------------------------------------------------------------ main
Write-Host ''
Write-Host '  WatchDocs installer' -ForegroundColor Cyan
Write-Host '  PDF, Word & Excel viewer for Wear OS'
Write-Host ''
Write-Host 'What do you want to install?'
Write-Host '  1) Watch + phone   (recommended)'
Write-Host '  2) Watch only'
Write-Host '  3) Phone only'
$choice = (Read-Host 'Choose 1, 2 or 3 [1]').Trim()
if (-not $choice) { $choice = '1' }

$Adb = Find-Adb
Invoke-Adb start-server | Out-Null

Write-Step 'Downloading the latest WatchDocs'
$results = @()
if ($choice -ne '3') {
    $watchApk = Get-Apk 'WatchDocs-watch.apk'
}
if ($choice -ne '2') {
    $phoneApk = Get-Apk 'WatchDocs-phone.apk'
}
Write-Ok 'Downloaded.'

if ($choice -ne '3') { $results += [pscustomobject]@{ Device = 'Watch'; Installed = (Install-To 'watch' $watchApk) } }
if ($choice -ne '2') {
    Write-Host ''
    Write-Host "Tip: you can also install the phone app without the PC: open this link on your phone:" -ForegroundColor Yellow
    Write-Host "  https://github.com/$Repo/releases/latest/download/WatchDocs-phone.apk"
    $results += [pscustomobject]@{ Device = 'Phone'; Installed = (Install-To 'phone' $phoneApk) }
}

Write-Step 'Done'
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host 'Open WatchDocs on your phone, tap "Send documents to watch", then open WatchDocs on the watch.'
Write-Host 'You can turn Wireless debugging off again now.'
