#!/bin/bash
# Copies committed revisions of this repository into one fresh directory, for comparing base
# against head without touching the shared working tree.
#
#   scripts/qa-snapshot.sh head=HEAD base=a86dde2      # prints the new directory, e.g. .../snap.Ab12Cd
#
# Each label becomes a subdirectory holding `git archive <rev>`, made into its own one-commit git
# repository (so `git diff` and `git stash` work inside it), with the repository's `node_modules`
# linked in when there is one.
#
# Why a script: reviewers used to type this as a loop that ran `rm -rf $DIR/$name` after `cd` into
# the repository. Claude Code asks the human to approve a command like that even in bypass mode,
# and on 2026-10-19 one teammate waited 3 h 54 min on that approval with nobody at the machine.
# This script never deletes anything: every call makes a new directory with `mktemp -d`, so the
# command an agent types is one plain line. See docs/TEAM.md, "Snapshots".
set -euo pipefail

if [ $# -eq 0 ]; then
  echo "usage: $0 <label>=<rev> [<label>=<rev> ...]" >&2
  exit 2
fi

repo=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d "${TMPDIR:-/tmp}/snap.XXXXXX")

for pair in "$@"; do
  label=${pair%%=*}
  rev=${pair#*=}
  if [ -z "$label" ] || [ "$label" = "$pair" ] || [[ "$label" == */* ]]; then
    echo "qa-snapshot: '$pair' is not <label>=<rev> with a plain label" >&2
    exit 2
  fi
  dir="$out/$label"
  mkdir "$dir"
  git -C "$repo" archive "$rev" | tar -x -C "$dir"
  git -C "$dir" init -q
  git -C "$dir" add -A
  # The session's GIT_AUTHOR_* variables win over these; they only stop a bare shell from failing.
  git -C "$dir" -c user.name=snapshot -c user.email=snapshot@invalid commit -q -m "snapshot of $rev"
  if [ -d "$repo/node_modules" ]; then
    ln -s "$repo/node_modules" "$dir/node_modules"
  fi
done

echo "$out"
