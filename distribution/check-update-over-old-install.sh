#!/usr/bin/env bash
# Install a suite zip, update over it as an older install would be updated, and require the server to
# start. Run by `./gradlew checkUpdateOverOldInstall`; the arguments come from there.
#
#   check-update-over-old-install.sh <ksl-suite.zip> <scratch dir> <a jar an older release shipped>
#
# Why: 0.4.0 and 0.4.1 started cleanly on a fresh install and died at startup on an updated one. An
# update unzips over the old install, so a jar a release stopped shipping stays behind, and the server
# launchers took server-lib/* -- the Ktor 3.2.3 jars an update had left loaded beside 3.6.0. Every test
# ran a fresh build; nothing ran the path an existing user takes. This one does, with a real old jar.
#
# Writes nothing outside <scratch dir> except what the KSL Server writes to the workspace it is
# configured with (it does not read KSLWORK; see the issues backlog).
set -euo pipefail

ZIP="$1" SCRATCH="$2" OLD_JAR="$3"
fail() { echo "checkUpdateOverOldInstall: FAILED: $*" >&2; exit 1; }
[ -f "$ZIP" ] || fail "no suite zip at $ZIP"
[ -f "$OLD_JAR" ] || fail "no old jar at $OLD_JAR"

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"
export KSL_HOME="$SCRATCH/KSL" KSLWORK="$SCRATCH/KSLWork"
SUPPORT="$KSL_HOME/.support"
SERVER="$SUPPORT/Servers/suite"
here="$(cd "$(dirname "$0")/.." && pwd)"

echo "checkUpdateOverOldInstall: installing $ZIP into $SCRATCH"
(cd "$here" && ./install.sh --from "$ZIP" >/dev/null)
[ -x "$SERVER/ksl-suite" ] || fail "the install has no KSL Server launcher"

# 1. An older release's leftovers: jars this payload does not ship. The update must remove them.
cp "$OLD_JAR" "$SUPPORT/lib/stale-from-an-earlier-release.jar"
cp "$OLD_JAR" "$SERVER/server-lib/$(basename "$OLD_JAR")"
"$KSL_HOME/bin/ksl" update --from "$ZIP" >/dev/null
[ ! -e "$SUPPORT/lib/stale-from-an-earlier-release.jar" ] || fail "ksl update left a stale jar in lib/"
[ ! -e "$SERVER/server-lib/$(basename "$OLD_JAR")" ] || fail "ksl update left a stale jar in server-lib/"

# 2. An update run by an OLDER bin/ksl prunes nothing in server-lib/, so the launcher must not load what
# it finds there. Plant the old jar again and start the server: it has to come up.
cp "$OLD_JAR" "$SERVER/server-lib/$(basename "$OLD_JAR")"
grep -q 'server-lib/\*' "$SERVER/ksl-suite" && fail "the ksl-suite launcher loads server-lib/* (any leftover jar)"

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
KSL_MCP_PORT="$PORT" "$SERVER/ksl-suite" >"$SCRATCH/server.out" 2>"$SCRATCH/server.err" &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
health=""
for _ in $(seq 1 60); do
  health="$(curl -s -m 2 "http://127.0.0.1:$PORT/health" || true)"
  [ -n "$health" ] && break
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done
case "$health" in
  *'"status":"UP"'*) echo "checkUpdateOverOldInstall: the updated server is UP: $health" ;;
  *) echo "--- server stderr ---" >&2; tail -20 "$SCRATCH/server.err" >&2
     fail "the KSL Server did not start after an update over an older install" ;;
esac
