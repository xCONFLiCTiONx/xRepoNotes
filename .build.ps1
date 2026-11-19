<#
.SYNOPSIS
    Build, run, or publish script for xRepoNotes.

.DESCRIPTION
    Automates building, cleaning, publishing, and running the xRepoNotes WPF desktop application.

.PARAMETER Configuration
    Build configuration: "Release" (default) or "Debug".

.PARAMETER Clean
    Cleans previous build output and bin/obj directories before compiling.

.PARAMETER Run
    Runs the built application immediately after a successful build.

.PARAMETER Publish
    Publishes a standalone or ready-to-deploy release folder.

.PARAMETER OutputDir
    Custom destination directory for publish output (optional).

.EXAMPLE
    .\.build.ps1
    Builds the project in Release configuration.

.EXAMPLE
    .\.build.ps1 -Run
    Builds and immediately starts the application.

.EXAMPLE
    .\.build.ps1 -Clean -Configuration Debug
    Cleans previous artifacts and compiles in Debug mode.

.EXAMPLE
    .\.build.ps1 -Publish
    Publishes a self-contained / single-file release package.
#>

[CmdletBinding()]
param(
    [ValidateSet("Release", "Debug")]
    [string]$Configuration = "Release",

    [switch]$Clean,
    [switch]$Run,
    [switch]$Publish,
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$ProjectFile = Join-Path $ScriptDir "xRepoNotes.csproj"
if (-not (Test-Path $ProjectFile)) {
    Write-Error "Project file not found: $ProjectFile"
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "       xRepoNotes Build System          " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Verify dotnet CLI
Write-Host "`n[1/4] Checking .NET environment..." -ForegroundColor Gray
try {
    $dotnetVersion = dotnet --version
    Write-Host "  Found .NET SDK version: $dotnetVersion" -ForegroundColor Green
}
catch {
    Write-Error ".NET SDK is not installed or not in PATH. Please install .NET 10 SDK."
    exit 1
}

# 2. Clean if requested
if ($Clean) {
    Write-Host "`n[2/4] Cleaning previous build artifacts..." -ForegroundColor Yellow
    dotnet clean $ProjectFile -c $Configuration -v minimal
    
    $dirsToClean = @(
        (Join-Path $ScriptDir "bin"),
        (Join-Path $ScriptDir "obj")
    )
    foreach ($dir in $dirsToClean) {
        if (Test-Path $dir) {
            Write-Host "  Removing $dir..." -ForegroundColor Gray
            Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} else {
    Write-Host "`n[2/4] Skipping clean (use -Clean to perform full cleanup)" -ForegroundColor Gray
}

# 3. Build or Publish
if ($Publish) {
    Write-Host "`n[3/4] Publishing xRepoNotes ($Configuration)..." -ForegroundColor Cyan
    $targetOutput = if ($OutputDir -ne "") { $OutputDir } else { Join-Path $ScriptDir "publish" }
    
    $publishArgs = @(
        "publish", $ProjectFile,
        "-c", $Configuration,
        "-o", $targetOutput,
        "--nologo"
    )
    
    Write-Host "  Executing: dotnet $($publishArgs -join ' ')" -ForegroundColor Gray
    & dotnet $publishArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Publish failed with exit code $LASTEXITCODE."
        exit $LASTEXITCODE
    }
    
    Write-Host "`n[+] Successfully published to:" -ForegroundColor Green
    Write-Host "    $targetOutput" -ForegroundColor White
    $exePath = Join-Path $targetOutput "xRepoNotes.exe"
}
else {
    Write-Host "`n[3/4] Compiling xRepoNotes ($Configuration)..." -ForegroundColor Cyan
    $buildArgs = @(
        "build", $ProjectFile,
        "-c", $Configuration,
        "--nologo"
    )

    Write-Host "  Executing: dotnet $($buildArgs -join ' ')" -ForegroundColor Gray
    & dotnet $buildArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Build failed with exit code $LASTEXITCODE."
        exit $LASTEXITCODE
    }

    $exePath = Join-Path $ScriptDir "bin\$Configuration\net10.0-windows\xRepoNotes.exe"
    Write-Host "`n[+] Build succeeded!" -ForegroundColor Green
    Write-Host "    Executable: $exePath" -ForegroundColor White

    if ($Configuration -eq "Release") {
        $deployDir = (Join-Path $env:LOCALAPPDATA "xPrograms\xRepoNotes")
        $releaseBinDir = Join-Path $ScriptDir "bin\Release\net10.0-windows"
        if (Test-Path (Join-Path $releaseBinDir "xRepoNotes.exe")) {
            Write-Host "`n[+] Deploying Release binaries to $deployDir..." -ForegroundColor Cyan
            Get-Process -Name "xRepoNotes" -ErrorAction SilentlyContinue | Stop-Process -Force
            Start-Sleep -Milliseconds 300
            New-Item -ItemType Directory -Path $deployDir -Force | Out-Null
            Copy-Item -Path "$releaseBinDir\*" -Destination $deployDir -Recurse -Force
            $exePath = Join-Path $deployDir "xRepoNotes.exe"
            Write-Host "    Deployed to $deployDir" -ForegroundColor Green
        }
    }
}

# 4. Run if requested
if ($Run) {
    if (-not (Test-Path $exePath)) {
        Write-Error "Executable not found at expected path: $exePath"
        exit 1
    }

    Write-Host "`n[4/4] Launching xRepoNotes..." -ForegroundColor Magenta
    Start-Process -FilePath $exePath
    Write-Host "  Process started." -ForegroundColor Green
}
else {
    Write-Host "`nTip: Pass -Run to build and launch immediately: .\.build.ps1 -Run" -ForegroundColor DarkGray
}

Write-Host "`nDone." -ForegroundColor Cyan

