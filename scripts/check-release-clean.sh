#!/usr/bin/env bash
#
# Proves that capture code is absent from a binary.
#
# :inspector-core carries a canary string. If that string appears in a release artifact, the
# real inspector linked in and the -Pinspector=off swap did not take effect. This is the
# enforcement half of the production-safety mechanism: the Gradle property is the convention,
# this script is the guarantee.
#
# Usage:
#   check-release-clean.sh                 scan :inspector-noop artifacts, expect NO canary
#   check-release-clean.sh --self-test     scan :inspector-core artifacts, expect the canary
#                                          (proves the detector actually detects)
#   check-release-clean.sh --paths P...    scan explicit files/dirs, expect NO canary
#                                          (use for app release APK/AAB/.framework outputs)
#
# Exit codes: 0 expected result, 1 unexpected canary state, 2 usage or internal error.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Captured before the cd below. --paths is given by a *caller*, usually from an app repo, so a
# relative path there means "relative to where you ran me", not "relative to Inspector". Resolving
# it against REPO_ROOT made it miss, and a miss used to be a skip, so the guard printed PASSED
# having scanned nothing. Reported from a consuming app; see the missing-target check further down.
INVOCATION_DIR="$PWD"

cd "$REPO_ROOT"

CANARY_SOURCE="inspector-core/src/commonMain/kotlin/dev/inspector/InspectorConfig.kt"

# Read the needle from the source of truth rather than hardcoding it, so renaming the constant
# cannot silently turn this guard into a no-op.
if [[ ! -f "$CANARY_SOURCE" ]]; then
  echo "ERROR: cannot find $CANARY_SOURCE to read the canary from." >&2
  exit 2
fi

CANARY="$(grep -oE 'INSPECTOR_CANARY_[A-Za-z0-9_]+' "$CANARY_SOURCE" | head -1 || true)"
if [[ -z "$CANARY" ]]; then
  echo "ERROR: no canary constant found in $CANARY_SOURCE." >&2
  echo "       A guard that cannot find its needle must fail, not pass." >&2
  exit 2
fi

MODE="expect-absent"
declare -a TARGETS=()

# macOS ships bash 3.2, which has no `mapfile`; collect with a portable read loop instead.
collect_artifacts() {
  local module_build="$1"

  # JVM and Android outputs.
  while IFS= read -r line; do
    [[ -n "$line" ]] && TARGETS+=("$line")
  done < <(find "$module_build" \( -name "*.jar" -o -name "*.aar" \) 2>/dev/null | grep -v sources | sort)

  # Kotlin/Native klib output, which is an unpacked directory rather than an archive. Without
  # this the guard would scan only JVM/Android artifacts and report an iOS build clean purely
  # because it never looked — and iOS is where the Gradle swap is the only protection.
  while IFS= read -r line; do
    [[ -n "$line" ]] && TARGETS+=("$line")
  done < <(find "$module_build/classes/kotlin" -maxdepth 3 -type d -name klib 2>/dev/null | sort)
}

case "${1:-}" in
  --self-test)
    MODE="expect-present"
    collect_artifacts inspector-core/build
    ;;
  --paths)
    shift
    if [[ $# -eq 0 ]]; then
      echo "ERROR: --paths needs at least one file or directory." >&2
      exit 2
    fi
    for given in "$@"; do
      case "$given" in
        /*) TARGETS+=("$given") ;;
        *)  TARGETS+=("$INVOCATION_DIR/$given") ;;
      esac
    done
    ;;
  "")
    collect_artifacts inspector-noop/build
    ;;
  *)
    echo "ERROR: unknown argument '${1}'. See the header of this script." >&2
    exit 2
    ;;
esac

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "ERROR: no artifacts to scan. Build first, e.g. './gradlew build'." >&2
  exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

hits=()

missing=()

scan_target() {
  local target="$1"
  # Never a skip. A guard that cannot find what it was asked to scan has proven nothing, and
  # saying PASSED there is the single most dangerous thing this script could do.
  [[ -e "$target" ]] || { missing+=("$target"); echo "  MISSING: $target"; return; }

  case "$target" in
    *.jar|*.aar|*.apk|*.aab|*.zip)
      local dest
      dest="$WORK/$(echo "$target" | tr '/' '_')"
      mkdir -p "$dest"
      unzip -qo "$target" -d "$dest" >/dev/null 2>&1 || true
      # .aar wraps classes.jar; unpack one level of nested archives too.
      while IFS= read -r nested; do
        unzip -qo "$nested" -d "${nested}.unpacked" >/dev/null 2>&1 || true
      done < <(find "$dest" -name "*.jar" 2>/dev/null)
      if grep -rlaF -- "$CANARY" "$dest" >/dev/null 2>&1; then
        hits+=("$target")
        echo "  CANARY FOUND: $target"
      else
        echo "  clean: $target"
      fi
      ;;
    *)
      local grep_args=(-laF)
      [[ -d "$target" ]] && grep_args=(-rlaF)
      if grep "${grep_args[@]}" -- "$CANARY" "$target" >/dev/null 2>&1; then
        hits+=("$target")
        echo "  CANARY FOUND: $target"
      else
        echo "  clean: $target"
      fi
      ;;
  esac
}

echo "Canary:  $CANARY"
echo "Mode:    $MODE"
echo "Scanning ${#TARGETS[@]} target(s):"
for target in "${TARGETS[@]}"; do
  scan_target "$target"
done
echo

if [[ ${#missing[@]} -gt 0 ]]; then
  echo "ERROR: ${#missing[@]} target(s) could not be found:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo >&2
  echo "Nothing was proven about them. Check the paths, and note that relative paths are" >&2
  echo "resolved against the directory you ran this from ($INVOCATION_DIR)." >&2
  exit 2
fi

if [[ "$MODE" == "expect-present" ]]; then
  if [[ ${#hits[@]} -eq 0 ]]; then
    echo "SELF-TEST FAILED: the canary was not found in :inspector-core artifacts."
    echo "The detector is broken — it would report release builds clean regardless."
    exit 1
  fi
  echo "SELF-TEST PASSED: canary detected in ${#hits[@]} core artifact(s). The guard works."
  exit 0
fi

if [[ ${#hits[@]} -gt 0 ]]; then
  echo "FAILED: capture code is present in ${#hits[@]} artifact(s):"
  printf '  %s\n' "${hits[@]}"
  echo
  echo "Build the release variant with -Pinspector=off so :inspector-noop is substituted."
  exit 1
fi

echo "PASSED: no capture code found in ${#TARGETS[@]} artifact(s)."
