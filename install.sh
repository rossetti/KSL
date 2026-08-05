#!/usr/bin/env bash
#
# KSL one-command installer (macOS / Linux).
#
#   curl -fsSL https://raw.githubusercontent.com/rossetti/KSL/main/install.sh | bash
#
# Installs the KSL suite — the desktop apps, the servers and kslpkg, all sharing ONE copy
# of the ~150 MB library — into ~/Applications/KSL, running on your system Java 21 (no
# bundled runtime). Two roots, kept apart on purpose:
#
#   ~/Applications/KSL      the SOFTWARE (this installer owns it). Double-clickable apps,
#                           bin/ksl, examples/ (model bundles + animation layouts to learn
#                           from), and a hidden .support/ with the shared lib/ and jars.
#   ~/Documents/KSLWork     YOUR WORK (the apps own it) — bundles, configs, output. The
#                           installer only ever creates bundles/ here; nothing else.
#
# Testing / offline: install from a locally-built payload instead of downloading:
#   ./install.sh --from build/ksl-suite.zip
#
set -euo pipefail

OWNER_REPO="rossetti/KSL"   # repo hosting the suite release + manifest.json
MANIFEST_URL="https://raw.githubusercontent.com/${OWNER_REPO}/main/manifest.json"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

say()  { printf '%s\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
sha256() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
           else sha256sum "$1" | awk '{print $1}'; fi; }

FROM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="${2:-}"; shift 2 ;;
    -h|--help) say "Usage: install.sh [--from <ksl-suite.zip>]"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

# --- 1. Java 21+ ---
JAVA="java"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && JAVA="$JAVA_HOME/bin/java"
command -v "$JAVA" >/dev/null 2>&1 || die "Java not found. Install JDK 21 — the same one you use in IntelliJ — then re-run."
JV="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
{ [ -n "$JV" ] && [ "$JV" -ge 21 ]; } 2>/dev/null \
  || die "Java 21+ required. Found: $("$JAVA" -version 2>&1 | head -1)"
say "* Java $JV ($JAVA)"

# --- 2. where the software goes ---
# ~/Applications/KSL is the traditional per-user app location: no admin rights needed,
# and Launchpad/Spotlight index it. This is NOT your workspace — see §8.
KSL_HOME="${KSL_HOME:-$HOME/Applications/KSL}"
SUPPORT="$KSL_HOME/.support"
mkdir -p "$SUPPORT" "$KSL_HOME/bin"
say "* Installing the software into: $KSL_HOME"

# --- 3. obtain ksl-suite.zip ---
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
ZIP="$TMP/ksl-suite.zip"; MANIFEST=""
if [ -n "$FROM" ]; then
  [ -f "$FROM" ] || die "--from: no such file: $FROM"
  cp "$FROM" "$ZIP"
  [ -f "$SCRIPT_DIR/manifest.json" ] && MANIFEST="$SCRIPT_DIR/manifest.json"
  say "* Using local payload: $FROM"
else
  MANIFEST="$TMP/manifest.json"
  curl -fsSL "$MANIFEST_URL" -o "$MANIFEST" || die "could not fetch manifest ($MANIFEST_URL)"
  URL="$(sed -n 's/.*"asset"[^"]*"\([^"]*ksl-suite\.zip\)".*/\1/p' "$MANIFEST" | head -1)"
  SHA="$(sed -n 's/.*"sha256"[^"]*"\([0-9a-f]\{64\}\)".*/\1/p' "$MANIFEST" | head -1)"
  [ -n "$URL" ] || die "manifest has no ksl-suite asset URL (a release must be published first)"
  say "* Downloading $URL"
  curl -fSL "$URL" -o "$ZIP" || die "download failed"
  if [ -n "$SHA" ]; then
    GOT="$(sha256 "$ZIP")"
    [ "$GOT" = "$SHA" ] || die "sha256 mismatch (expected $SHA, got $GOT)"
    say "* sha256 verified"
  fi
fi

# Delete anything in lib/ that the payload just extracted did not deliver. `unzip -o` overwrites
# what the zip contains and removes nothing else, so every jar the suite has ever shipped
# accumulates: updating 0.3.3 -> 0.3.4 left KSLCore-R1.4.jar beside KSLCore-R1.5.1.jar. That is not
# merely dead weight. Every launcher puts the whole directory on the classpath with `lib/*`, whose
# expansion order the JVM does not specify, so which of two versions of a class wins is not
# something a release controls -- a corrected library can silently lose to the one it replaced.
#
# lib/ is the one directory safe to prune wholesale: the catalog deliberately omits it (nothing
# there is independently installable), so nothing here can reflect a choice the user made. Apps/,
# Servers/ and Tools/ must NOT be treated this way -- `ksl uninstall single` removes Apps/Single,
# and pruning against the payload would silently reinstate it.
#
# Derived from the zip rather than a hand-kept list, which drifts (see the note in bin/ksl's
# whole-suite update), and run AFTER extraction so a failed unpack cannot leave an install with no
# lib/ at all.
prune_stale_lib() {  # $1 = the payload zip
  [ -d "$SUPPORT/lib" ] || return 0
  unzip -Z1 "$1" 'lib/*' 2>/dev/null | sed 's|^lib/||' | grep . | sort > "$TMP/lib-shipped" || return 0
  # An empty listing means the query failed, not that the payload ships no jars. Deleting the whole
  # of lib/ on that reading would leave nothing runnable, so treat it as "say nothing, do nothing".
  [ -s "$TMP/lib-shipped" ] || return 0
  ls -1 "$SUPPORT/lib" 2>/dev/null | sort > "$TMP/lib-present"
  n=0
  while IFS= read -r stale; do
    [ -n "$stale" ] || continue
    rm -f "$SUPPORT/lib/$stale" && n=$((n + 1))
  done < <(comm -13 "$TMP/lib-shipped" "$TMP/lib-present")
  [ "$n" -gt 0 ] && say "* removed $n stale jar(s) left by an earlier release"
  return 0
}

# --- 4. unpack into the hidden support folder ---
say "* Unpacking..."
command -v unzip >/dev/null 2>&1 || die "unzip not found"
unzip -q -o "$ZIP" -d "$SUPPORT"
prune_stale_lib "$ZIP"
# bin/ksl belongs next to the apps, not hidden away. Move rather than copy: on an update
# the old ksl may be the very script running this, and a rename leaves it on its old inode
# whereas an overwrite would corrupt it mid-read.
if [ -f "$SUPPORT/bin/ksl" ]; then
  chmod +x "$SUPPORT/bin/ksl"
  mv -f "$SUPPORT/bin/ksl" "$KSL_HOME/bin/ksl"
fi
rm -rf "$SUPPORT/bin"

# The shipped examples are content, not plumbing: model bundles to open and polished animation
# layouts to look at. A student told "open the animation examples" must be able to find them, so
# they come out of the hidden support folder and sit beside the apps. Still software -- this
# installer owns them and an update replaces them -- which is why they are here and not in the
# workspace, where a student's edits must never be overwritten.
rm -rf "$KSL_HOME/examples"
[ -d "$SUPPORT/examples" ] && mv -f "$SUPPORT/examples" "$KSL_HOME/examples"

# Up to 0.2.0 the shipped bundles lived at .support/bundles, and every launcher pointed there. They now
# live in examples/, launchers are regenerated to match, and nothing reads the old path -- so an upgrade
# left 736 KB of the PREVIOUS release's bundles sitting there, silently out of date. Harmless to run, but
# a student who goes looking finds the shipped examples in two places and one of them is a year old. The
# payload never writes here, so anything present can only be a leftover.
rm -rf "$SUPPORT/bundles"

# --- 5. record what's installed (inside .support: it's plumbing, not for students) ---
VER=""
if [ -n "$MANIFEST" ]; then
  VER="$(sed -n 's/.*"version"[^"]*"\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
  cp "$MANIFEST" "$SUPPORT/manifest.json"
fi
{
  echo "KSL suite installed $(date)"
  echo "software: $KSL_HOME"
  [ -n "$VER" ] && echo "version: $VER"
  echo "java:    $("$JAVA" -version 2>&1 | head -1)"
  echo "apps:    $(ls "$SUPPORT/Apps" 2>/dev/null | tr '\n' ' ')"
  echo "servers: $(ls "$SUPPORT/Servers" 2>/dev/null | tr '\n' ' ')"
} > "$SUPPORT/VERSIONS.txt"

# --- 6. macOS: clear the download quarantine ---
if [ "$(uname)" = "Darwin" ]; then
  xattr -dr com.apple.quarantine "$KSL_HOME" >/dev/null 2>&1 || true
  say "* Cleared macOS quarantine"
fi

# --- 7. entry points (bin/ksl owns this, so install and `ksl update` agree) ---
if [ -x "$KSL_HOME/bin/ksl" ]; then
  "$KSL_HOME/bin/ksl" refresh | sed 's/^/* /'
fi

# --- 8. your workspace — the apps own it; we only make sure bundles/ exists ---
if [ -n "${KSLWORK:-}" ];      then WORK="$KSLWORK"
elif [ -d "$HOME/Documents" ]; then WORK="$HOME/Documents/KSLWork"
else                                WORK="$HOME/KSLWork"; fi
mkdir -p "$WORK/bundles"

# --- 9. clean up software that a pre-split installer unpacked INTO the workspace.
#         Gated on our own manifest marker being there, and removes only the exact set
#         we ever put there — bundles/ and every per-app work folder are untouched. ---
cleanup_legacy() {
  local wk="$1" removed=0 p
  [ -n "$wk" ] && [ -d "$wk" ] || return 0
  grep -q '"kslWorkLayout"' "$wk/manifest.json" 2>/dev/null || return 0
  for p in Apps lib Servers Tools bin examples Applications manifest.json VERSIONS.txt; do
    if [ -e "$wk/$p" ]; then rm -rf "${wk:?}/$p"; removed=$((removed + 1)); fi
  done
  [ "$removed" -gt 0 ] && say "* Cleaned $removed stale software item(s) out of $wk (your bundles and work are untouched)"
  return 0
}
for w in "${KSLWORK:-}" "$HOME/Documents/KSLWork" "$HOME/KSLWork"; do cleanup_legacy "$w"; done

# --- 10. next steps ---
say ""
say "Done."
if [ "$(uname)" = "Darwin" ]; then
  say "  Apps       Launchpad -> \"KSL <Name>\"      e.g. KSL Single"
else
  say "  Apps       \"KSL <Name>\" in your applications menu"
fi
say "  Software   $KSL_HOME        (delete this folder to uninstall)"
say "  Examples   $KSL_HOME/examples   (model bundles + animation layouts — replaced on update)"
say "  Your work  $WORK            (bundles, configs, output — never touched by updates)"
say "             drop model bundle JARs into $WORK/bundles"
say "  Servers    $SUPPORT/Servers/<name>/   (point your MCP client's config here)"
say "  kslpkg     $SUPPORT/Tools/kslpkg/kslpkg"
say "  Manage     $KSL_HOME/bin/ksl list     (add / remove / update apps + servers)"
say "  Update     re-run this installer, or: $KSL_HOME/bin/ksl update"
