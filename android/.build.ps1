#Requires -Version 5.1
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"

# 1. Configure JAVA_HOME if not already set
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jbrCandidates = [System.Collections.Generic.List[string]]@(
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )

    $userJbrDir = Join-Path $env:USERPROFILE ".jbr"

    if (Test-Path $userJbrDir) {
        Get-ChildItem $userJbrDir -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $jbrCandidates.Add($_.FullName)
            }
    }

    foreach ($candidate in $jbrCandidates) {
        if (Test-Path (Join-Path $candidate "bin\java.exe")) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}

Write-Host "==> Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan

# 2. Build the Release APK (R8 runs automatically when enabled)
Write-Host "==> Building Release APK with R8 (assembleRelease)..." -ForegroundColor Cyan

& .\gradlew.bat :app:assembleRelease

if ($LASTEXITCODE -ne 0) {
    throw "Gradle assembleRelease failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path $ApkPath)) {
    throw "Built Release APK not found at: $ApkPath"
}

$apkSizeMb = [Math]::Round((Get-Item $ApkPath).Length / 1MB, 2)

Write-Host "==> Release APK built successfully ($apkSizeMb MB): $ApkPath" -ForegroundColor Green

# 3. Locate adb.exe
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue

if ($adbCmd) {
    $AdbPath = $adbCmd.Source
} else {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

    if (Test-Path $sdkAdb) {
        $AdbPath = $sdkAdb
    } else {
        throw "Could not find adb.exe in PATH or $sdkAdb"
    }
}

# 4. Verify connected Android device
Write-Host "==> Checking connected Android device(s)..." -ForegroundColor Cyan

$devicesOutput = & $AdbPath devices

$unauthorizedDevices = $devicesOutput |
    Select-Object -Skip 1 |
    Where-Object { $_ -match "\S+\s+unauthorized\s*$" }

$connectedDevices = $devicesOutput |
    Select-Object -Skip 1 |
    Where-Object { $_ -match "\S+\s+device\s*$" }

if (-not $connectedDevices) {
    Write-Host ($devicesOutput | Out-String) -ForegroundColor Yellow

    if ($unauthorizedDevices) {
        Write-Warning "Your phone is connected but unauthorized. Unlock your phone, approve the USB debugging prompt, and rerun this script."
    } else {
        Write-Warning "No authorized Android device detected. Release APK was built successfully, but cannot be installed automatically."
    }

    return
}

# 5. Dynamically determine Package Name
Write-Host "==> Resolving package name dynamically..." -ForegroundColor Cyan

$PackageName = $null

$Sdk = $env:ANDROID_SDK_ROOT

if (-not $Sdk) {
    $Sdk = $env:ANDROID_HOME
}

if (-not $Sdk) {
    $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

if (Test-Path $Sdk) {
    $BuildTools = Join-Path $Sdk "build-tools"

    $Aapt = Get-ChildItem $BuildTools -Filter "aapt.exe" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1

    if ($Aapt) {
        $Badging = & $Aapt.FullName dump badging $ApkPath 2>$null

        $Line = $Badging |
            Where-Object { $_ -match "^package: name='" } |
            Select-Object -First 1

        if ($Line -match "^package: name='([^']+)'") {
            $PackageName = $Matches[1]
        }
    }
}

# Fallback: Read applicationId from Gradle files
if (-not $PackageName) {
    $GradleFiles = @(
        Get-ChildItem $PSScriptRoot -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @("build.gradle", "build.gradle.kts") }

        Get-ChildItem (Join-Path $PSScriptRoot "app") -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @("build.gradle", "build.gradle.kts") }
    )

    $ApplicationIdPattern = '(?m)^\s*applicationId\s*(?:=\s*)?["'']([^"'']+)["'']'

    foreach ($File in $GradleFiles) {
        $Content = Get-Content $File.FullName -Raw
        $Match = [regex]::Match($Content, $ApplicationIdPattern)

        if ($Match.Success) {
            $PackageName = $Match.Groups[1].Value
            break
        }
    }
}

if (-not $PackageName) {
    throw "Could not determine package ID automatically. Check your Android SDK or build files."
}

Write-Host "==> Package Name: $PackageName" -ForegroundColor Green

# 6. Install Release APK onto phone
Write-Host "==> Installing Release APK onto phone..." -ForegroundColor Cyan

$installOutput = & $AdbPath install -r $ApkPath 2>&1

if ($LASTEXITCODE -ne 0) {
    $installText = ($installOutput | Out-String)

    if ($installText -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        Write-Host "==> Replacing previous debug-signed build with Release-signed build..." -ForegroundColor Yellow

        & $AdbPath uninstall $PackageName | Out-Null

        & $AdbPath install -r $ApkPath

        if ($LASTEXITCODE -ne 0) {
            throw "adb install failed with exit code $LASTEXITCODE"
        }
    } else {
        Write-Host $installText -ForegroundColor Red
        throw "adb install failed with exit code $LASTEXITCODE"
    }
} else {
    Write-Host ($installOutput | Out-String).Trim() -ForegroundColor Gray
}

# 7. Resolve and launch the app's launcher activity
Write-Host "==> Resolving launcher activity..." -ForegroundColor Cyan

$ActivityOutput = & $AdbPath shell cmd package resolve-activity --brief `
    -a android.intent.action.MAIN `
    -c android.intent.category.LAUNCHER `
    $PackageName 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "Could not resolve launcher activity through adb."
}

$Activity = $ActivityOutput |
    ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -match '^[^/]+/.+$' } |
    Select-Object -Last 1

if (-not $Activity) {
    throw "No launcher activity found for $PackageName. The APK was installed, but could not be launched."
}

Write-Host "==> Launching activity ($Activity)..." -ForegroundColor Cyan

& $AdbPath shell am start -n $Activity

if ($LASTEXITCODE -ne 0) {
    throw "Failed to launch activity via adb"
}

Write-Host "==> Done! App (Release) is installed and running on your phone." -ForegroundColor Green
