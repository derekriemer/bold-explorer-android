#!/usr/bin/env bash
# Pull the app's database off a device, or put one back.
#
# Why this exists as a script rather than a remembered incantation: getting it wrong is expensive.
# The database holds every trail the owner has ever recorded, some of which cannot be re-walked, and
# the three failure modes below are all silent — you get a database that opens fine and is subtly
# wrong.
#
#   1. SQLite runs in WAL mode, so recent writes live in `-wal` beside the `.db`. Copying the `.db`
#      alone gives you a file that is missing them; restoring a `.db` next to somebody else's stale
#      `-wal` gives you a mixture of two databases.
#   2. A running app holds the connection open and will overwrite whatever you just pushed.
#   3. `adb push` cannot write into /data/data without root. `run-as` is the way in, and it only
#      works on a debuggable build — `make install`, not a beta or release APK.
#
# Backups are made read-only on creation, because the reflex to "just open it and look" is how a
# reference copy stops being a reference copy.
set -euo pipefail

DB_NAME="bold_explorer.db"
BACKUP_ROOT="${BOLD_EXPLORER_BACKUP_DIR:-$HOME/bold-explorer-backups}"

die() { echo "error: $*" >&2; exit 1; }

detect_app_id() {
    for candidate in com.boldexplorer com.boldexplorer.foss; do
        if adb shell "run-as $candidate true" >/dev/null 2>&1; then
            echo "$candidate"
            return 0
        fi
    done
    die "no debuggable Bold Explorer install found on the device.
  run-as needs a debug build (make install). A beta or release APK is not debuggable,
  and its data cannot be reached without root."
}

require_device() {
    adb get-state >/dev/null 2>&1 || die "no device. Try: make adb-connect"
}

cmd_backup() {
    require_device
    local app_id stamp dest
    app_id=$(detect_app_id)
    stamp=$(date +%Y%m%d-%H%M%S)
    dest="${1:-$BACKUP_ROOT}/bold_explorer-$stamp"
    mkdir -p "$dest"

    echo "stopping $app_id so nothing is written mid-copy"
    adb shell "am force-stop $app_id"

    local found=0
    for suffix in "" "-wal" "-shm"; do
        local remote="databases/$DB_NAME$suffix"
        if adb shell "run-as $app_id test -f $remote" >/dev/null 2>&1; then
            adb shell "run-as $app_id cat $remote" > "$dest/$DB_NAME$suffix"
            echo "  pulled $DB_NAME$suffix ($(wc -c < "$dest/$DB_NAME$suffix") bytes)"
            found=1
        fi
    done
    [ "$found" = 1 ] || die "no database found on the device — has the app ever run?"

    chmod 444 "$dest"/*
    echo
    echo "backup written to $dest (read-only)"
    echo "restore with: tools/db.sh restore $dest --force"
}

cmd_restore() {
    local src="${1:-}"
    [ -n "$src" ] || die "usage: tools/db.sh restore <backup-dir> --force"
    [ -d "$src" ] || die "no such backup directory: $src"
    [ -f "$src/$DB_NAME" ] || die "$src does not contain $DB_NAME"
    [ "${2:-}" = "--force" ] || die "restore overwrites the database on the device.
  Re-run with --force if that is what you want:
    tools/db.sh restore $src --force"

    require_device
    local app_id
    app_id=$(detect_app_id)

    echo "stopping $app_id"
    adb shell "am force-stop $app_id"

    # Clear the sidecars first. Leaving a stale -wal beside a restored .db is the silent
    # half-and-half case: SQLite replays it on open and you get neither database.
    adb shell "run-as $app_id rm -f databases/$DB_NAME-wal databases/$DB_NAME-shm"

    for suffix in "" "-wal" "-shm"; do
        local local_file="$src/$DB_NAME$suffix"
        [ -f "$local_file" ] || continue
        adb push "$local_file" "/data/local/tmp/$DB_NAME$suffix" >/dev/null
        adb shell "run-as $app_id cp /data/local/tmp/$DB_NAME$suffix databases/$DB_NAME$suffix"
        adb shell "rm -f /data/local/tmp/$DB_NAME$suffix"
        echo "  restored $DB_NAME$suffix"
    done
    echo
    echo "restored from $src. Start the app and check the trail list before walking anywhere."
}

case "${1:-}" in
    backup)  shift; cmd_backup "$@" ;;
    restore) shift; cmd_restore "$@" ;;
    *)
        cat >&2 <<USAGE
usage:
  tools/db.sh backup [dir]              pull the database (default: $BACKUP_ROOT)
  tools/db.sh restore <dir> --force     push a backup back onto the device

Needs a debuggable build on the device (make install) — run-as cannot reach a
beta or release APK's data.
USAGE
        exit 2
        ;;
esac
