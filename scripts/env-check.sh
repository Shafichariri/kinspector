#!/usr/bin/env bash
# What this machine has, against what the build and the checks need. Run it first in a fresh
# clone or before handing work to an agent: on 2026-10-19 an engineer found JDK 17 where the build
# needs 21, downloaded one into a scratch directory, and every later prompt had to carry its path.
#
#   ./scripts/env-check.sh          # prints one line per tool; exit 1 if a required one is missing
#
# For JDK 21 it prints the JAVA_HOME to use and the Gradle flag that points at it. It never
# installs or downloads anything.
set -u

missing=0
ok()   { printf 'ok    %-10s %s\n' "$1" "$2"; }
warn() { printf 'warn  %-10s %s\n' "$1" "$2"; }
fail() { printf 'MISS  %-10s %s\n' "$1" "$2"; missing=1; }

java_major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }

# JDK 21: JAVA_HOME first, then macOS's registry, then any JDK Gradle was told about.
jdk=""
for candidate in "${JAVA_HOME:-}" \
    "$( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 2>/dev/null )" \
    $(sed -n 's/^org.gradle.java.installations.paths=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | tr ',' ' '); do
  [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ "$(java_major "$candidate")" = "21" ] && { jdk="$candidate"; break; }
done
if [ -n "$jdk" ]; then
  ok jdk21 "$jdk"
  [ "${JAVA_HOME:-}" = "$jdk" ] || echo "      export JAVA_HOME=\"$jdk\"   # or pass -Porg.gradle.java.installations.paths=\"$jdk\""
else
  have="$(command -v java >/dev/null && java -version 2>&1 | head -1)"
  fail jdk21 "not found${have:+ (java on PATH: $have)}. Install a JDK 21 (e.g. Temurin) yourself; agents do not download one."
fi

if command -v node >/dev/null; then
  v="$(node -v)"; major="${v#v}"; major="${major%%.*}"
  [ "$major" -ge 22 ] && ok node "$v" || warn node "$v (the web scripts use global fetch and WebSocket; 22 or later is what they are run with)"
else
  fail node "not found: check-web-static.js and the probes need it"
fi

chrome="${CHROME:-}"
[ -z "$chrome" ] && [ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ] && chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
[ -z "$chrome" ] && chrome="$(command -v google-chrome || command -v chromium || true)"
[ -n "$chrome" ] && ok chrome "$chrome" || warn chrome "not found: measure-web-ui.js and rendered QA need it (or set CHROME=<binary>)"

command -v backlog >/dev/null && ok backlog "$(backlog --version 2>/dev/null | head -1)" || warn backlog "not found: tickets need it (npm i -g backlog.md)"
command -v gh >/dev/null && ok gh "$(gh --version | head -1)" || warn gh "not found: PRs need it"

email="$(git config user.email || true)"
case "$email" in
  3518471+Shafichariri@users.noreply.github.com) ok git-email "$email" ;;
  *) warn git-email "${email:-unset}: agents commit as the noreply address; pass -c user.email=… (AGENTS.md)" ;;
esac

# Resolved the way the probes resolve it, so a node_modules in a parent directory counts.
jsdom="$(cd "$(dirname "$0")" && node -e 'try { console.log(require("jsdom/package.json").version) } catch (e) {}' 2>/dev/null)"
[ -n "$jsdom" ] && ok jsdom "$jsdom (CI uses 29.1.1)" || warn jsdom "not resolvable from scripts/: the probes need it (npm install jsdom@29.1.1)"

exit "$missing"
