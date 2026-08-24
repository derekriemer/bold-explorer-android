#!/usr/bin/env bash
# jj fix tool wrapper for ktlint.
#
# ktlint 1.8.0's `--stdin --format` mode crashes (MissingFormatArgumentException)
# on any source file whose formatted output contains a literal printf-style
# token such as "%.1f" (see e.g. "%.1f".format(x) in AudioCuePlayer.kt) — it
# appears to pass file content through PrintWriter.printf() somewhere in its
# stdin code path. File-mode formatting (`ktlint --format <path>`) does not
# hit this bug, so this script recreates the file at its real repo-relative
# path under a scratch directory, formats it there, and streams the result
# back out on stdout the way `jj fix` expects.
#
# Usage: jj-fix-ktlint.sh <repo-relative-path>   (content comes from stdin)
set -euo pipefail

repo_relative_path=$1

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

target="$tmpdir/$repo_relative_path"
mkdir -p "$(dirname "$target")"
cat > "$target"

# Don't let remaining (non-autocorrectable) violations block jj from
# applying whatever ktlint *was* able to fix.
ktlint --format --log-level=none "$target" >&2 || true

cat "$target"
