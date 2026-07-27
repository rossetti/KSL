#!/usr/bin/env pwsh
#
# KSL one-command installer (Windows).
#
# Run it (downloads to a temp file, then runs that file with a bypassed execution
# policy so `exit` stays contained and there's no Mark-of-the-Web block):
#   irm https://raw.githubusercontent.com/rossetti/KSL/main/install.ps1 -OutFile "$env:TEMP\ksl-install.ps1"; powershell -ExecutionPolicy Bypass -File "$env:TEMP\ksl-install.ps1"
#
# Installs the KSL suite - the desktop apps, the servers and kslpkg, all sharing ONE copy
# of the ~150 MB library - on your system Java 21 (no bundled runtime). Two roots, kept
# apart on purpose, mirroring the macOS layout:
#
#   %LOCALAPPDATA%\Programs\KSL   the SOFTWARE (this installer owns it): the app
#                                 launchers, bin\ksl, and a hidden .support\ with the
#                                 shared lib\ and jars. Start-Menu shortcuts point here.
#   Documents\KSLWork             YOUR WORK (the apps own it) - bundles, configs, output.
#                                 The installer only ever creates bundles\ here.
#
# The shipped examples (model bundles + polished animation layouts) are unpacked out of the
# hidden support folder into KSL\examples so a student can find them.
#
# Testing / offline: install from a locally-built payload instead of downloading:
#   powershell -ExecutionPolicy Bypass -File install.ps1 -From build\ksl-suite.zip
#
[CmdletBinding()]
param([string]$From = "")

$ErrorActionPreference = "Stop"
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null
$IsWin = $env:OS -eq "Windows_NT"

$OwnerRepo   = "rossetti/KSL"   # repo hosting the suite release + manifest.json
$ManifestUrl = "https://raw.githubusercontent.com/$OwnerRepo/main/manifest.json"
$ScriptDir   = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

function Say([string]$m) { Write-Host $m }
function Die([string]$m) { Write-Host "error: $m"; exit 1 }

# --- 1. Java 21+ ---
function JavaCommand {
    $exe = if ($IsWin) { "java.exe" } else { "java" }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path (Join-Path $env:JAVA_HOME "bin") $exe
        if (Test-Path $candidate) { return $candidate }
    }
    return $exe
}
function JavaVersionText([string]$cmd) {
    $oldEap = $ErrorActionPreference
    try {
        # java -version writes to stderr. Windows PowerShell 5.1 can turn that into
        # NativeCommandError when ErrorActionPreference is Stop, even when redirected.
        $ErrorActionPreference = "Continue"
        return (& $cmd -version 2>&1 | Out-String)
    }
    finally {
        $ErrorActionPreference = $oldEap
    }
}
$java = JavaCommand
try { $vtext = JavaVersionText $java }
catch { Die "Java not found. Install JDK 21 - the same one you use in IntelliJ - then re-run." }
$vline = ($vtext -split "\r?\n" | Where-Object { $_ } | Select-Object -First 1)
$vmaj = if ($vtext -match 'version "(\d+)') { [int]$Matches[1] } else { 0 }
if ($vmaj -lt 21) { Die "Java 21+ required. Found: $vline" }
Say "* Java $vmaj ($java)"

# --- 2. where the software goes ---
# %LOCALAPPDATA%\Programs\KSL is the per-user program location (no admin rights needed) -
# the Windows counterpart of ~/Applications/KSL. This is NOT your workspace; see step 8.
$kslHome = if ($env:KSL_HOME) { $env:KSL_HOME }
           elseif ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Programs\KSL" }
           else { Join-Path $HOME "KSL" }
$support = Join-Path $kslHome ".support"
New-Item -ItemType Directory -Force -Path $support, (Join-Path $kslHome "bin") | Out-Null
Say "* Installing the software into: $kslHome"

# --- 3. obtain ksl-suite.zip ---
$tmp = (New-Item -ItemType Directory -Force -Path (Join-Path ([System.IO.Path]::GetTempPath()) ("ksl-" + [System.IO.Path]::GetRandomFileName()))).FullName
try {
    $zip = Join-Path $tmp "ksl-suite.zip"
    $manifest = ""
    if ($From) {
        if (-not (Test-Path $From)) { Die "-From: no such file: $From" }
        Copy-Item $From $zip -Force
        if (Test-Path (Join-Path $ScriptDir "manifest.json")) { $manifest = Join-Path $ScriptDir "manifest.json" }
        Say "* Using local payload: $From"
    } else {
        $manifest = Join-Path $tmp "manifest.json"
        try { Invoke-WebRequest -UseBasicParsing -Uri $ManifestUrl -OutFile $manifest }
        catch { Die "could not fetch manifest ($ManifestUrl)" }
        $m = Get-Content $manifest -Raw | ConvertFrom-Json
        $url = $m.suite.asset; $sha = $m.suite.sha256
        if (-not $url) { Die "manifest has no ksl-suite asset URL (a release must be published first)" }
        Say "* Downloading $url"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
        if ($sha) {
            $got = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
            if ($got -ne $sha.ToLower()) { Die "sha256 mismatch (expected $sha, got $got)" }
            Say "* sha256 verified"
        }
    }

    # --- 4. unpack into the hidden support folder ---
    Say "* Unpacking..."
    Expand-Archive -Path $zip -DestinationPath $support -Force
    # The helper belongs next to the apps, not hidden away.
    foreach ($f in @("ksl.ps1", "ksl.cmd")) {
        $srcF = Join-Path $support "bin\$f"
        if (Test-Path $srcF) { Move-Item -Force $srcF (Join-Path $kslHome "bin\$f") }
    }
    Remove-Item -Recurse -Force (Join-Path $support "bin") -ErrorAction SilentlyContinue

    # The shipped examples are content, not plumbing -- model bundles to open and polished animation
    # layouts to look at -- so they come out of the hidden support folder and sit beside the apps.
    # Still software: this installer owns them and an update replaces them, which is why they are here
    # and not in the workspace, where a student's own edits must never be overwritten.
    $examplesSrc = Join-Path $support "examples"
    $examplesDst = Join-Path $kslHome "examples"
    if (Test-Path $examplesSrc) {
        Remove-Item -Recurse -Force $examplesDst -ErrorAction SilentlyContinue
        Move-Item -Force $examplesSrc $examplesDst
    }
    # Dot-folders are not hidden on Windows; set the attribute so students don't see it.
    if ($IsWin) { attrib +h $support 2>$null }

    # --- 5. record what's installed (inside .support: plumbing, not for students) ---
    $ver = ""
    if ($manifest -and (Test-Path $manifest)) {
        try { $ver = (Get-Content $manifest -Raw | ConvertFrom-Json).suite.version } catch {}
        Copy-Item $manifest (Join-Path $support "manifest.json") -Force
    }
    $apps    = (Get-ChildItem -Directory (Join-Path $support "Apps")    -ErrorAction SilentlyContinue | ForEach-Object Name) -join " "
    $servers = (Get-ChildItem -Directory (Join-Path $support "Servers") -ErrorAction SilentlyContinue | ForEach-Object Name) -join " "
    @(
        "KSL suite installed $(Get-Date)"
        "software: $kslHome"
        $(if ($ver) { "version: $ver" })
        "java:    $vline"
        "apps:    $apps"
        "servers: $servers"
    ) | Where-Object { $_ } | Set-Content -Path (Join-Path $support "VERSIONS.txt")

    # --- 6. Windows: clear the Mark-of-the-Web so nothing hits SmartScreen ---
    if ($IsWin) {
        Get-ChildItem -Recurse -File -Path $kslHome -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue
        Say "* Cleared Mark-of-the-Web"
    }
}
finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }

# --- 7. entry points (bin\ksl.ps1 owns this, so install and `ksl update` agree) ---
$kslPs1 = Join-Path $kslHome "bin\ksl.ps1"
if (Test-Path $kslPs1) { & $kslPs1 refresh | ForEach-Object { Say "* $_" } }

# --- 8. your workspace - the apps own it; we only make sure bundles\ exists ---
$work = if ($env:KSLWORK) { $env:KSLWORK }
        else {
            $docs = [Environment]::GetFolderPath("MyDocuments")
            if ($docs) { Join-Path $docs "KSLWork" } else { Join-Path $HOME "KSLWork" }
        }
New-Item -ItemType Directory -Force -Path (Join-Path $work "bundles") | Out-Null

# --- 9. clean up software a pre-split installer unpacked INTO the workspace. Gated on
#         our own manifest marker being present, and removes only the exact set we ever
#         put there - bundles\ and every per-app work folder are untouched. ---
function CleanupLegacy([string]$wk) {
    if (-not $wk -or -not (Test-Path $wk)) { return }
    $mf = Join-Path $wk "manifest.json"
    if (-not (Test-Path $mf)) { return }
    if (-not (Select-String -Path $mf -Pattern '"kslWorkLayout"' -Quiet -ErrorAction SilentlyContinue)) { return }
    $removed = 0
    foreach ($p in @("Apps", "lib", "Servers", "Tools", "bin", "examples", "Applications", "manifest.json", "VERSIONS.txt")) {
        $t = Join-Path $wk $p
        if (Test-Path $t) { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue; $removed++ }
    }
    if ($removed -gt 0) { Say "* Cleaned $removed stale software item(s) out of $wk (your bundles and work are untouched)" }
}
foreach ($w in @($env:KSLWORK, $work)) { CleanupLegacy $w }

# --- 10. next steps ---
Say ""
Say "Done."
Say '  Apps       Start Menu -> KSL -> "KSL <Name>"    e.g. KSL Single'
Say "  Software   $kslHome        (delete this folder to uninstall)"
Say "  Examples   $kslHome\examples  (model bundles + animation layouts - replaced on update)"
Say "  Your work  $work           (bundles, configs, output - never touched by updates)"
Say "             drop model bundle JARs into $work\bundles"
Say "  Servers    $support\Servers\<name>\   (point your MCP client's config here)"
Say "  kslpkg     $support\Tools\kslpkg\kslpkg.cmd"
Say "  Manage     $kslHome\bin\ksl list      (add / remove / update apps + servers)"
Say "  Update     re-run this installer, or: $kslHome\bin\ksl update"
