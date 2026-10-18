#!/usr/bin/env bash
#
# Checks the commits in a range carry the maintainer's identity and no attribution trailers.
#
# The repository is public and GitHub lists commit authors as contributors, so a commit authored
# by an agent's own identity, a personal address or a work address publishes that identity, and
# history cannot be quietly rewritten after a push. AGENTS.md states the rule; this enforces it.
#
# Usage:
#   check-commit-identity.sh <base> <head>     checks every commit in base..head, merges included
#
# Rules, per commit:
#   author email     must be the maintainer's GitHub noreply address
#   committer email  the same, or noreply@github.com (a merge or edit made on github.com)
#   message          no `Co-Authored-By:` or `Claude-Session:` line, in any case
# Names are not checked: GitHub attributes by email, and a display name is not an identity.
#
# Output names the commit and the failing field, never the email itself -- this runs in public CI
# logs, and printing the address that should not be there would publish it a second time.
#
# Exit codes: 0 every commit passes, 1 a commit fails, 2 usage error or an empty/unreadable range.
# It is never meant to run over all of history, which is already public: give it a range.

set -euo pipefail

MAINTAINER_EMAIL="3518471+Shafichariri@users.noreply.github.com"
WEB_FLOW_EMAIL="noreply@github.com"

if [[ $# -ne 2 || -z "$1" || -z "$2" ]]; then
  echo "usage: $0 <base> <head>" >&2
  exit 2
fi
BASE="$1"
HEAD="$2"

# An all-zero base is what a push event reports for a new branch; there is no range to read.
if [[ "$BASE" =~ ^0+$ ]]; then
  echo "ERROR: base is the null SHA (a newly created ref); no range to check." >&2
  exit 2
fi

if ! COMMITS="$(git rev-list "$BASE..$HEAD" 2>/dev/null)"; then
  echo "ERROR: cannot compute $BASE..$HEAD -- is the history fetched (fetch-depth: 0)?" >&2
  exit 2
fi
# A range that lists nothing checks nothing, and a check that checks nothing must not pass.
if [[ -z "$COMMITS" ]]; then
  echo "ERROR: $BASE..$HEAD contains no commits; refusing to report that as a pass." >&2
  exit 2
fi

failures=0
count=0
while IFS= read -r sha; do
  count=$((count + 1))
  short="${sha:0:12}"
  author="$(git log -1 --format='%ae' "$sha")"
  committer="$(git log -1 --format='%ce' "$sha")"

  if [[ "$author" != "$MAINTAINER_EMAIL" ]]; then
    echo "FAIL $short: author email is not the maintainer's noreply address"
    failures=$((failures + 1))
  fi
  if [[ "$committer" != "$MAINTAINER_EMAIL" && "$committer" != "$WEB_FLOW_EMAIL" ]]; then
    echo "FAIL $short: committer email is neither the maintainer's noreply address nor GitHub's"
    failures=$((failures + 1))
  fi
  # Read into a variable, not piped: under pipefail, `git log | grep -q` can lose the race when
  # grep exits on the first match and git takes SIGPIPE, which makes a found trailer read as none.
  message="$(git log -1 --format='%B' "$sha")"
  if grep -qiE '^[[:space:]]*(co-authored-by|claude-session)[[:space:]]*:' <<< "$message"; then
    echo "FAIL $short: message carries an attribution trailer (Co-Authored-By or Claude-Session)"
    failures=$((failures + 1))
  fi
done <<< "$COMMITS"

if [[ $failures -gt 0 ]]; then
  echo "commit identity: $failures problem(s) in $count commit(s) of $BASE..$HEAD"
  echo "See AGENTS.md, 'Agents commit as the maintainer and add no attribution trailers'."
  exit 1
fi
echo "commit identity: $count commit(s) in $BASE..$HEAD checked, all pass"
