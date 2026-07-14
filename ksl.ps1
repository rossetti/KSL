#!/usr/bin/env pwsh
#
# ksl — manage the KSL suite installed in this KSLWork folder (Windows).
#
#   ksl list                          what's available and what's installed
#   ksl install <id> [--from <zip>]   add one item (reuses the shared lib/)
#   ksl uninstall <id>                remove one item
#   ksl update [id] [--from <zip>]    refresh everything, or just one item
#
# Invoked through bin\ksl.cmd so plain `ksl <cmd>` works regardless of execution
# policy. This is the Windows twin of bin/ksl (the macOS/Linux bash version).
#
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null
$IsWin = $env:OS -eq "Windows_NT"

$root = Split-Path -Parent $PSScriptRoot          # bin\ksl.ps1 -> KSLWork
$manifest = Join-Path $root "manifest.json"

function Say([string]$m) { Write-Host $m }
function Die([string]$m) { Write-Host "ksl: $m"; exit 1 }
if (-not (Test-Path $manifest)) { Die "no manifest.json in $root — run this as an installed KSLWork\bin\ksl" }

# catalog straight from the manifest — no hand-rolled JSON parsing needed
$items = @((Get-Content $manifest -Raw | ConvertFrom-Json).items)
function PathOf([string]$id) { ($items | Where-Object { $_.id -eq $id } | Select-Object -First 1).path }

# args: pull --from / -From out of the list, then dispatch on what's left
$From = ""; $pos = @()
for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq "--from" -or $args[$i] -eq "-From") { $From = [string]$args[$i + 1]; $i++ }
    else { $pos += [string]$args[$i] }
}
$cmd  = if ($pos.Count -ge 1) { $pos[0] } else { "list" }
$arg1 = if ($pos.Count -ge 2) { $pos[1] } else { "" }

# a usable ksl-suite.zip: --from if given, else download from the manifest suite URL
function SuiteZip {
    if ($From) {
        if (-not (Test-Path $From)) { Die "--from: no such file: $From" }
        return (Resolve-Path $From).Path
    }
    $url = (Get-Content $manifest -Raw | ConvertFrom-Json).suite.asset
    if (-not $url) { Die "no suite URL in manifest and no --from given (publish a release first)" }
    $dl = Join-Path ([System.IO.Path]::GetTempPath()) ("ksl-suite-" + [System.IO.Path]::GetRandomFileName() + ".zip")
    Write-Host "downloading $url ..."
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $dl
    return $dl
}
# extract only this item's entries — the analog of `unzip "<path>/*"`
function ExtractItem([string]$zip, [string]$path) {
    $za = [System.IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($e in $za.Entries) {
            if ($e.FullName -notlike "$path/*") { continue }
            if ([string]::IsNullOrEmpty($e.Name)) { continue }   # skip directory entries
            $dest = Join-Path $root ($e.FullName -replace '/', [System.IO.Path]::DirectorySeparatorChar)
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
        }
    } finally { $za.Dispose() }
}
function Dequarantine([string]$p) {
    if ($IsWin -and (Test-Path $p)) { Get-ChildItem -Recurse -File $p -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue }
}

function CmdList {
    Say "KSLWork: $root"
    if (Test-Path (Join-Path $root "VERSIONS.txt")) {
        Get-Content (Join-Path $root "VERSIONS.txt") | Where-Object { $_ -match '^version:' } | ForEach-Object { Say $_ }
    }
    Say ("  {0,-13} {1,-7} {2}" -f "id", "kind", "installed")
    foreach ($it in $items) {
        $st = if (Test-Path (Join-Path $root $it.path)) { "yes" } else { "-" }
        Say ("  {0,-13} {1,-7} {2}" -f $it.id, $it.kind, $st)
    }
}
function CmdUninstall([string]$id) {
    $p = PathOf $id
    if (-not $p) { Die "unknown id: $id (see 'ksl list')" }
    $full = Join-Path $root $p
    if (-not (Test-Path $full)) { Say "$id is not installed."; return }
    Remove-Item -Recurse -Force $full
    Say "removed $id ($p)"
}
function CmdInstall([string]$id) {
    $p = PathOf $id
    if (-not $p) { Die "unknown id: $id (see 'ksl list')" }
    ExtractItem (SuiteZip) $p
    Dequarantine (Join-Path $root $p)
    Say "installed $id -> $p"
}
function CmdUpdate([string]$id) {
    $zip = SuiteZip
    if (-not $id) {
        Expand-Archive -Path $zip -DestinationPath $root -Force
        Dequarantine (Join-Path $root "Apps"); Dequarantine (Join-Path $root "Servers"); Dequarantine (Join-Path $root "Tools")
        Say "updated the whole suite (bundles/ and working output preserved)"
    } else {
        $p = PathOf $id
        if (-not $p) { Die "unknown id: $id" }
        ExtractItem $zip $p; Dequarantine (Join-Path $root $p)
        Say "updated $id"
    }
}

switch ($cmd) {
    "list"      { CmdList }
    "install"   { if (-not $arg1) { Die "usage: ksl install <id> [--from <zip>]" }; CmdInstall $arg1 }
    "update"    { CmdUpdate $arg1 }
    { $_ -in "uninstall", "remove" } { if (-not $arg1) { Die "usage: ksl uninstall <id>" }; CmdUninstall $arg1 }
    { $_ -in "help", "-h", "--help" } { Say "usage: ksl {list | install <id> | uninstall <id> | update [id]} [--from <ksl-suite.zip>]" }
    default     { Die "unknown command: $cmd (try 'ksl list')" }
}
