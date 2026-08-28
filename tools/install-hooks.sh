#!/bin/sh
# Install the hooks AGENTS.md already documents. Copies into .git/hooks so no
# git config change is required (core.hooksPath would need one).
set -e
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/tools/githooks"
dest="$(git -C "$root" rev-parse --git-path hooks)"

for name in pre-commit pre-push; do
  install -m 755 "$src/$name" "$dest/$name"
  echo "installed $dest/$name"
done
