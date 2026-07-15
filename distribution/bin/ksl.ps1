#!/usr/bin/env pwsh
#
# ksl - manage the installed KSL suite (Windows).
#
#   ksl list                          what's available and what's installed
#   ksl install <id> [--from <zip>]   add one item (reuses the shared lib\)
#   ksl uninstall <id>                remove one item
#   ksl update [id] [--from <zip>]    refresh everything, or just one item
#   ksl refresh                       rebuild the Start-Menu shortcuts
#
# Two roots, deliberately separate (mirroring the macOS layout):
#
#   <KSL_HOME>    the SOFTWARE. This script's own home - %LOCALAPPDATA%\Programs\KSL.
#                 Holds bin\ksl and a hidden .support\ with lib\, the per-app jars and
#                 launchers, Servers\, Tools\ and manifest.json. Owned by the installer.
#   <workspace>   YOUR WORK - bundles, configs, per-app output. Defaults to
#                 Documents\KSLWork, set in ~/.ksl/settings.toml, owned by the apps.
#                 This script never reads or writes it.
#
# Invoked through bin\ksl.cmd so plain `ksl <cmd>` works regardless of execution policy.
# This is the Windows twin of bin/ksl (the macOS/Linux bash version).
#
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null
$IsWin = $env:OS -eq "Windows_NT"

$kslHome  = Split-Path -Parent $PSScriptRoot        # bin\ksl.ps1 -> KSL_HOME
$support  = Join-Path $kslHome ".support"
$manifest = Join-Path $support "manifest.json"

function Say([string]$m) { Write-Host $m }
function Die([string]$m) { Write-Host "ksl: $m"; exit 1 }
if (-not (Test-Path $manifest)) { Die "no manifest at $manifest - run this as an installed <KSL_HOME>\bin\ksl" }

# catalog straight from the manifest - paths are relative to .support\
$items = @((Get-Content $manifest -Raw | ConvertFrom-Json).items)
function PathOf([string]$id) { ($items | Where-Object { $_.id -eq $id } | Select-Object -First 1).path }
function KindOf([string]$id) { ($items | Where-Object { $_.id -eq $id } | Select-Object -First 1).kind }

# args: pull --from / -From out of the list, then dispatch on what's left
$From = ""; $pos = @()
for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq "--from" -or $args[$i] -eq "-From") { $From = [string]$args[$i + 1]; $i++ }
    else { $pos += [string]$args[$i] }
}
$cmd  = if ($pos.Count -ge 1) { $pos[0] } else { "list" }
$arg1 = if ($pos.Count -ge 2) { $pos[1] } else { "" }

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
# extract only this item's entries into .support -- the analog of `unzip "<path>/*"`
function ExtractItem([string]$zip, [string]$path) {
    $za = [System.IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($e in $za.Entries) {
            if ($e.FullName -notlike "$path/*") { continue }
            if ([string]::IsNullOrEmpty($e.Name)) { continue }   # skip directory entries
            $dest = Join-Path $support ($e.FullName -replace '/', [System.IO.Path]::DirectorySeparatorChar)
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
            try {
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
            }
            catch [System.IO.IOException] {
                Die "could not replace $dest. Close any running KSL apps or servers, then try again."
            }
        }
    } finally { $za.Dispose() }
}
function Dequarantine([string]$p) {
    if ($IsWin -and (Test-Path $p)) { Get-ChildItem -Recurse -File $p -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue }
}

# --- entry points --------------------------------------------------------------
# Start-Menu shortcuts in front of the .cmd launchers buried in .support\. The .lnk
# targets the .cmd rather than javaw directly, so the launcher's Java 21 preflight
# still runs and its message is still visible. ($env:APPDATA is null off-Windows.)
$StartMenu = if ($env:APPDATA) { Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\KSL" } else { "" }

# The payload is ONE cross-platform zip, so .support\ also receives the Unix launchers
# (extension-less shell scripts). Drop what this machine can't run.
function PruneForeign {
    # Windows-only: off-Windows the "extension-less" files ARE the real launchers,
    # so running this there would delete the working install.
    if (-not $IsWin) { return }
    foreach ($d in @("Apps", "Servers", "Tools")) {
        $p = Join-Path $support $d
        if (Test-Path $p) {
            Get-ChildItem -Recurse -File -Path $p -ErrorAction SilentlyContinue |
                Where-Object { -not $_.Extension } |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }
    }
}
function MakeEntryPoint([string]$name) {
    if (-not $IsWin -or -not $StartMenu) { return }
    $target = Join-Path $support "Apps\$name\$name.cmd"
    $icon = Join-Path $support "Apps\$name\$name.ico"
    if (-not (Test-Path $target)) { return }
    if (-not (Test-Path $icon)) { Die "missing Windows icon for KSL ${name}: $icon" }
    New-Item -ItemType Directory -Force -Path $StartMenu | Out-Null
    $sh = New-Object -ComObject WScript.Shell
    $lnk = $sh.CreateShortcut((Join-Path $StartMenu "KSL $name.lnk"))
    $lnk.TargetPath       = $target
    $lnk.WorkingDirectory = $support
    $lnk.Description      = "KSL $name"
    $lnk.IconLocation     = "$icon,0"
    $lnk.Save()
}
function RemoveEntryPoint([string]$name) {
    if (-not $StartMenu) { return }
    Remove-Item -Force -ErrorAction SilentlyContinue -Path (Join-Path $StartMenu "KSL $name.lnk")
}
function CmdRefresh {
    PruneForeign
    $n = 0
    foreach ($d in (Get-ChildItem -Directory -Path (Join-Path $support "Apps") -ErrorAction SilentlyContinue)) {
        MakeEntryPoint $d.Name; $n++
    }
    if ($IsWin) { Say "refreshed $n app(s): look for the KSL folder in your Start Menu" }
    else { Say "refreshed $n app(s)" }
}

function CmdList {
    Say "KSL software: $kslHome"
    $v = Join-Path $support "VERSIONS.txt"
    if (Test-Path $v) { Get-Content $v | Where-Object { $_ -match '^version:' } | ForEach-Object { Say $_ } }
    Say ("  {0,-13} {1,-7} {2}" -f "id", "kind", "installed")
    foreach ($it in $items) {
        $st = if (Test-Path (Join-Path $support $it.path)) { "yes" } else { "-" }
        Say ("  {0,-13} {1,-7} {2}" -f $it.id, $it.kind, $st)
    }
}
function CmdUninstall([string]$id) {
    $p = PathOf $id
    if (-not $p) { Die "unknown id: $id (see 'ksl list')" }
    $full = Join-Path $support $p
    if (-not (Test-Path $full)) { Say "$id is not installed."; return }
    if ((KindOf $id) -eq "app") { RemoveEntryPoint (Split-Path -Leaf $p) }
    Remove-Item -Recurse -Force $full
    Say "removed $id ($p)"
}
function CmdInstall([string]$id) {
    $p = PathOf $id
    if (-not $p) { Die "unknown id: $id (see 'ksl list')" }
    ExtractItem (SuiteZip) $p
    Dequarantine (Join-Path $support $p)
    PruneForeign
    if ((KindOf $id) -eq "app") { MakeEntryPoint (Split-Path -Leaf $p) }
    Say "installed $id -> $p"
}
function CmdUpdate([string]$id) {
    $zip = SuiteZip
    if (-not $id) {
        foreach ($top in @("lib", "Apps", "Servers", "Tools", "bundles")) { ExtractItem $zip $top }
        # Replace this very script by rename, never by overwrite -- PowerShell may still
        # be reading it. Move-Item swaps the entry and leaves the running process alone.
        $t = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
        New-Item -ItemType Directory -Force -Path $t | Out-Null
        try {
            $za = [System.IO.Compression.ZipFile]::OpenRead($zip)
            try {
                foreach ($e in $za.Entries) {
                    if ($e.FullName -notlike "bin/*" -or [string]::IsNullOrEmpty($e.Name)) { continue }
                    $d = Join-Path $t $e.Name
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $d, $true)
                    if ($e.Name -in @("ksl.ps1", "ksl.cmd")) { Move-Item -Force $d (Join-Path $kslHome "bin\$($e.Name)") }
                }
            } finally { $za.Dispose() }
        } finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue }
        Dequarantine $support
        CmdRefresh
        Say "updated the whole suite (your workspace was not touched)"
    } else {
        $p = PathOf $id
        if (-not $p) { Die "unknown id: $id" }
        ExtractItem $zip $p; Dequarantine (Join-Path $support $p)
        PruneForeign
        if ((KindOf $id) -eq "app") { MakeEntryPoint (Split-Path -Leaf $p) }
        Say "updated $id"
    }
}

switch ($cmd) {
    "list"      { CmdList }
    "install"   { if (-not $arg1) { Die "usage: ksl install <id> [--from <zip>]" }; CmdInstall $arg1 }
    "update"    { CmdUpdate $arg1 }
    "refresh"   { CmdRefresh }
    { $_ -in "uninstall", "remove" } { if (-not $arg1) { Die "usage: ksl uninstall <id>" }; CmdUninstall $arg1 }
    { $_ -in "help", "-h", "--help" } { Say "usage: ksl {list | install <id> | uninstall <id> | update [id] | refresh} [--from <ksl-suite.zip>]" }
    default     { Die "unknown command: $cmd (try 'ksl list')" }
}
