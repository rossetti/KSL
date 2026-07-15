#!/usr/bin/env bash
#
# KSL one-command installer (macOS / Linux).
#
#   curl -fsSL https://raw.githubusercontent.com/rossetti/KSL/main/install.sh | bash
#
# Installs the whole KSL suite (desktop apps + servers + kslpkg, sharing one ~150 MB
# lib/) into a single KSLWork folder, run on your system Java 21 — no bundled runtime.
# Re-running updates in place; your model bundles and working output are never touched.
#
# Testing / offline: install from a locally-built payload instead of downloading:
#   KSLWORK=/tmp/ksltest ./install.sh --from build/ksl-suite.zip
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

# --- 2. KSLWork root ---
if   [ -n "${KSLWORK:-}" ];   then ROOT="$KSLWORK"
elif [ -d "$HOME/Documents" ]; then ROOT="$HOME/Documents/KSLWork"
else                               ROOT="$HOME/KSLWork"; fi
mkdir -p "$ROOT"
say "* Installing into: $ROOT"

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

# --- 4. unpack (the zip holds only lib/ Apps/ Servers/ Tools/, so bundles/ and the
#         per-app working dirs already in $ROOT are left untouched) ---
say "* Unpacking..."
command -v unzip >/dev/null 2>&1 || die "unzip not found"
unzip -q -o "$ZIP" -d "$ROOT"

# --- 5. macOS: clear the download quarantine so launchers open without a Gatekeeper block ---
if [ "$(uname)" = "Darwin" ]; then
  xattr -dr com.apple.quarantine "$ROOT/Apps" "$ROOT/Servers" "$ROOT/Tools" >/dev/null 2>&1 || true
  say "* Cleared macOS quarantine"
fi

# --- 6. record what's installed ---
VER=""
[ -n "$MANIFEST" ] && VER="$(sed -n 's/.*"version"[^"]*"\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
[ -n "$MANIFEST" ] && cp "$MANIFEST" "$ROOT/manifest.json"
{
  echo "KSL suite installed $(date)"
  echo "root:    $ROOT"
  [ -n "$VER" ] && echo "version: $VER"
  echo "java:    $("$JAVA" -version 2>&1 | head -1)"
  echo "apps:    $(ls "$ROOT/Apps" 2>/dev/null | tr '\n' ' ')"
  echo "servers: $(ls "$ROOT/Servers" 2>/dev/null | tr '\n' ' ')"
} > "$ROOT/VERSIONS.txt"

# --- 7. entry points: build the real double-clickable app per app, and drop the
#         launchers this OS can't run (the payload is one cross-platform zip, so
#         every app folder also ships a Windows .cmd). bin/ksl owns this so that
#         install, `ksl install` and `ksl update` all produce the same result.
#         Runs after step 6: it reads the manifest we just copied in. ---
if [ -x "$ROOT/bin/ksl" ]; then
  "$ROOT/bin/ksl" refresh | sed 's/^/* /'
fi

# --- 8. next steps ---
say ""
say "Done. KSL is installed in $ROOT"
if [ "$(uname)" = "Darwin" ]; then
  say "  Apps      double-click  $ROOT/Applications/<Name>.app     e.g. Single.app"
  say "            (drag them to your Dock; the folders under Apps/ are just plumbing)"
else
  say "  Apps      \"KSL <Name>\" in your applications menu"
  say "            (or run $ROOT/Apps/<Name>/<Name> from a terminal)"
fi
say "  Servers   at   $ROOT/Servers/<name>/          (point your MCP client's config here)"
say "  kslpkg    run  $ROOT/Tools/kslpkg/kslpkg"
say "  Manage    run  $ROOT/bin/ksl list             (add / remove / update apps + servers)"
say "  Bundles + output stay under $ROOT and are preserved across updates."
say "  Update later by re-running this installer, or: $ROOT/bin/ksl update"
