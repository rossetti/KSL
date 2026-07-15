#!/usr/bin/env pwsh
#
# KSL one-command installer (Windows).
#
# Run it (downloads to a temp file, then runs that file with a bypassed execution
# policy so `exit` stays contained and there's no Mark-of-the-Web block):
#   irm https://raw.githubusercontent.com/rossetti/KSL/main/install.ps1 -OutFile "$env:TEMP\ksl-install.ps1"; powershell -ExecutionPolicy Bypass -File "$env:TEMP\ksl-install.ps1"
#
# Installs the whole KSL suite (desktop apps + servers + kslpkg, sharing one ~150 MB
# lib/) into a single KSLWork folder, on your system Java 21 — no bundled runtime.
# Re-running updates in place; your model bundles and working output are never touched.
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
$java = "java"
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/java*"))) { $java = Join-Path $env:JAVA_HOME "bin/java" }
try { $vtext = (& $java -version 2>&1 | Out-String) }
catch { Die "Java not found. Install JDK 21 — the same one you use in IntelliJ — then re-run." }
$vline = ($vtext -split "\r?\n" | Where-Object { $_ } | Select-Object -First 1)
$vmaj = if ($vtext -match 'version "(\d+)') { [int]$Matches[1] } else { 0 }
if ($vmaj -lt 21) { Die "Java 21+ required. Found: $vline" }
Say "* Java $vmaj ($java)"

# --- 2. KSLWork root ---
if ($env:KSLWORK) { $root = $env:KSLWORK }
else {
    $docs = [Environment]::GetFolderPath("MyDocuments")
    $root = if ($docs) { Join-Path $docs "KSLWork" } else { Join-Path $HOME "KSLWork" }
}
New-Item -ItemType Directory -Force -Path $root | Out-Null
Say "* Installing into: $root"

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

    # --- 4. unpack (the zip holds only lib/ Apps/ Servers/ Tools/ bin/, so bundles/ and
    #         the per-app working dirs already in $root are left untouched) ---
    Say "* Unpacking..."
    Expand-Archive -Path $zip -DestinationPath $root -Force

    # --- 5. Windows: clear the Mark-of-the-Web so launchers open without a SmartScreen block ---
    if ($IsWin) {
        Get-ChildItem -Recurse -File -ErrorAction SilentlyContinue -Path `
            (Join-Path $root "Apps"),(Join-Path $root "Servers"),(Join-Path $root "Tools"),(Join-Path $root "bin") |
            Unblock-File -ErrorAction SilentlyContinue
        Say "* Cleared Mark-of-the-Web"
    }

    # --- 6. record what's installed ---
    $ver = ""
    if ($manifest -and (Test-Path $manifest)) {
        try { $ver = (Get-Content $manifest -Raw | ConvertFrom-Json).suite.version } catch {}
        Copy-Item $manifest (Join-Path $root "manifest.json") -Force
    }
    $apps    = (Get-ChildItem -Directory (Join-Path $root "Apps")    -ErrorAction SilentlyContinue | ForEach-Object Name) -join " "
    $servers = (Get-ChildItem -Directory (Join-Path $root "Servers") -ErrorAction SilentlyContinue | ForEach-Object Name) -join " "
    @(
        "KSL suite installed $(Get-Date)"
        "root:    $root"
        $(if ($ver) { "version: $ver" })
        "java:    $vline"
        "apps:    $apps"
        "servers: $servers"
    ) | Where-Object { $_ } | Set-Content -Path (Join-Path $root "VERSIONS.txt")
}
finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }

# --- 7. entry points: put a real Start-Menu shortcut in front of each app, and drop
#         the launchers this OS can't run (the payload is one cross-platform zip, so
#         every app folder also ships a Unix shell script). bin\ksl.ps1 owns this so
#         install, `ksl install` and `ksl update` all produce the same result.
#         Runs after step 6: it reads the manifest we just copied in. ---
$kslPs1 = Join-Path $root "bin\ksl.ps1"
if (Test-Path $kslPs1) { & $kslPs1 refresh | ForEach-Object { Say "* $_" } }

# --- 8. next steps ---
Say ""
Say "Done. KSL is installed in $root"
Say "  Apps      Start Menu -> KSL -> <Name>          e.g. KSL Single"
Say "            (the folders under Apps\ are just plumbing)"
Say "  Servers   at   $root\Servers\<name>\           (point your MCP client's config here)"
Say "  kslpkg    run  $root\Tools\kslpkg\kslpkg.cmd"
Say "  Manage    run  $root\bin\ksl list              (add / remove / update apps + servers)"
Say "  Bundles + output stay under $root and are preserved across updates."
Say "  Update later by re-running this installer, or: $root\bin\ksl update"
