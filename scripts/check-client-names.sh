#!/usr/bin/env bash
#
# Scans the working tree, a range's commit messages and the branch name for client names.
#
# The repository is public, and nothing in it may name a client, an employer or their products.
# The terms are never written into the repository -- listing them would publish exactly what was
# removed -- so they arrive from outside: the CLIENT_NAME_TERMS secret in CI, or a file kept in the
# maintainer's local notes. Run it before committing; CI catches a match before merge, but by then
# the term has been pushed to a branch on a public remote.
#
# Usage:
#   check-client-names.sh [--terms-file F] [--range BASE..HEAD] [--branch NAME] [--require-terms]
#
# Terms: one per line, from --terms-file if given, else from $CLIENT_NAME_TERMS. Case-insensitive,
# fixed strings. Blank lines and CR are ignored.
#
# What it prints, and what it never prints. CI logs on a public repository are public, and GitHub
# masks a secret only where its whole value appears -- one term out of a list would print in clear.
# So a match is reported as file:line, a matching path as a count, a matching commit message as its
# SHA, and a matching branch name as that fact. The matched text never appears.
#
# With no terms at all it says NOT RUN and exits 0, because pull requests from forks get no secrets;
# it never prints a pass in that case. --require-terms makes that exit 1 instead.
#
# Exit codes: 0 no match (or NOT RUN), 1 a match (or no terms under --require-terms), 2 usage error.

set -euo pipefail
# Never `set -x`: it would echo every term into the log.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

usage() {
  echo "usage: $0 [--terms-file F] [--range BASE..HEAD] [--branch NAME] [--require-terms]" >&2
  exit 2
}

# A flag given without its value is a usage error, never a scan with that part quietly skipped:
# `--range "$UNSET"` in a workflow would otherwise check no commit messages and still report.
value_of() {
  if [[ $# -lt 2 || -z "$2" || "$2" == --* ]]; then
    echo "ERROR: $1 needs a value." >&2
    usage
  fi
}

TERMS_FILE=""
RANGE=""
BRANCH=""
REQUIRE_TERMS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --terms-file) value_of "$@"; TERMS_FILE="$2"; shift 2 ;;
    --range) value_of "$@"; RANGE="$2"; shift 2 ;;
    --branch) value_of "$@"; BRANCH="$2"; shift 2 ;;
    --require-terms) REQUIRE_TERMS=1; shift ;;
    *) usage ;;
  esac
done

# The range is validated before anything else, including the no-terms exit below, so a broken
# range expression in the workflow shows up while the secret is still unset rather than later.
shas=""
if [[ -n "$RANGE" ]]; then
  if ! shas="$(git rev-list "$RANGE" 2>/dev/null)"; then
    echo "ERROR: cannot compute the range $RANGE -- is the history fetched?" >&2
    exit 2
  fi
  # As in check-commit-identity.sh: a range with no commits checks no messages, and must not pass.
  if [[ -z "$shas" ]]; then
    echo "ERROR: $RANGE contains no commits; refusing to report that as a pass." >&2
    exit 2
  fi
fi

umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PATTERNS="$WORK/terms"

if [[ -n "$TERMS_FILE" ]]; then
  if [[ ! -f "$TERMS_FILE" ]]; then
    echo "ERROR: no terms file at the given path." >&2
    exit 2
  fi
  tr -d '\r' < "$TERMS_FILE" > "$PATTERNS"
else
  printf '%s\n' "${CLIENT_NAME_TERMS:-}" | tr -d '\r' > "$PATTERNS"
fi
# Drop blank and whitespace-only lines, and trim the rest: a stray space in a secret would
# otherwise make a term that matches every space in the repository.
sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e '/^$/d' "$PATTERNS" > "$PATTERNS.clean"
mv "$PATTERNS.clean" "$PATTERNS"

if [[ ! -s "$PATTERNS" ]]; then
  echo "client-name scan: NOT RUN — no terms (fork PR or secret not set)"
  echo "::warning::client-name scan NOT RUN: no terms were provided, so nothing was scanned."
  if [[ $REQUIRE_TERMS -eq 1 ]]; then exit 1; fi
  exit 0
fi

found=0

# Paths first. A matching path *is* the term, so such paths are counted and never printed, and
# their content hits below are counted rather than listed for the same reason.
# `core.quotePath=false` on every listing: by default git prints a non-ASCII path octal-escaped
# and quoted, so a term with an accent neither matched the path count nor stayed out of the
# file:line list, where the escaped form spelled it out.
git -c core.quotePath=false ls-files --cached --others --exclude-standard > "$WORK/paths"
grep -i -F -f "$PATTERNS" "$WORK/paths" > "$WORK/named" || true
paths=$(wc -l < "$WORK/named" | tr -d ' ')
if [[ "$paths" -gt 0 ]]; then
  echo "$paths file path(s) match a term (not listed: the path would print the term)."
  found=1
fi

# File contents, tracked and untracked-but-not-ignored, so it is useful before `git add`. `-I`
# skips binaries, which is where the Gradle wrapper jar's false positives come from. Only
# file:line survives `cut`; the matched line itself is discarded before anything is printed.
# Exit 1 is "no match"; anything else is git failing, and a scan that failed is not a clean one.
status=0
git -c core.quotePath=false grep --untracked -I -n -i -F -f "$PATTERNS" > "$WORK/content" 2>/dev/null || status=$?
if [[ $status -gt 1 ]]; then
  echo "ERROR: git grep failed (exit $status); nothing was scanned." >&2
  exit 2
fi
if [[ $status -eq 0 ]]; then
  cut -d: -f1,2 "$WORK/content" > "$WORK/hits"
  hidden=0
  if [[ -s "$WORK/named" ]]; then
    hidden=$(grep -c -F -f "$WORK/named" "$WORK/hits" || true)
    grep -v -F -f "$WORK/named" "$WORK/hits" > "$WORK/shown" || true
  else
    cp "$WORK/hits" "$WORK/shown"
  fi
  if [[ -s "$WORK/shown" ]]; then
    echo "Matches in file contents (file:line):"
    sed 's/^/  /' "$WORK/shown"
  fi
  if [[ "$hidden" -gt 0 ]]; then
    echo "$hidden more match(es) inside files whose path matches a term (not listed)."
  fi
  found=1
fi

# Commit messages in the range, by SHA only.
if [[ -n "$RANGE" ]]; then
  for sha in $shas; do
    message="$(git log -1 --format='%B' "$sha")"
    if grep -q -i -F -f "$PATTERNS" <<< "$message"; then
      echo "Commit message matches a term: ${sha:0:12}"
      found=1
    fi
  done
fi

if [[ -n "$BRANCH" ]] && grep -q -i -F -f "$PATTERNS" <<< "$BRANCH"; then
  echo "The branch name matches a term."
  found=1
fi

if [[ $found -eq 1 ]]; then
  echo "client-name scan: FAILED. See AGENTS.md, 'No client names in this repository'."
  exit 1
fi
echo "client-name scan: no match for $(wc -l < "$PATTERNS" | tr -d ' ') term(s)"
