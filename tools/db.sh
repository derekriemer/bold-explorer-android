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
#   4. The google and foss flavors are separate app IDs and can be installed side by side, each
#      with its own database. Picking one for you by whichever is installed is a fourth silent way
#      to get the wrong database, so the flavor is named, not guessed: google unless you say
#      otherwise, and a backup records the app it came from.
#
# Backups are made read-only on creation, because the reflex to "just open it and look" is how a
# reference copy stops being a reference copy.
set -euo pipefail

DB_NAME="bold_explorer.db"
BACKUP_ROOT="${BOLD_EXPLORER_BACKUP_DIR:-$HOME/bold-explorer-backups}"
DEFAULT_APP="google"
# Written into each backup so a restore can tell you when it is crossing flavors.
SOURCE_FILE="source-app"

die() { echo "error: $*" >&2; exit 1; }

flavor_label_for() {
    case "$1" in
        com.boldexplorer)      echo google ;;
        com.boldexplorer.foss) echo foss ;;
        *)                     echo "${1//./-}" ;;
    esac
}

reachable() { adb shell "run-as $1 true" >/dev/null 2>&1; }

# Resolve the requested flavor into the global APP_ID and prove run-as can reach it. Sets a global
# rather than echoing so that `die` here kills the script — inside a command substitution it would
# only kill the subshell and let the caller carry on with an empty app ID.
#
# Deliberately does not fall back to the other flavor: silently operating on a database you did not
# ask for is the whole failure mode this script exists to prevent.
APP_ID=""
resolve_app_id() {
    local other
    case "${1:-$DEFAULT_APP}" in
        google) APP_ID=com.boldexplorer ;;
        foss)   APP_ID=com.boldexplorer.foss ;;
        *.*)    APP_ID="$1" ;;
        *)      die "unknown app '$1' — use google, foss, or a full package id" ;;
    esac
    reachable "$APP_ID" && return 0

    for other in com.boldexplorer com.boldexplorer.foss; do
        [ "$other" = "$APP_ID" ] && continue
        if reachable "$other"; then
            die "$APP_ID is not reachable on this device, but $other is.
  Re-run with --app $(flavor_label_for "$other") if that is the one you meant."
        fi
    done
    die "no debuggable Bold Explorer install found on the device for $APP_ID.
  run-as needs a debug build (make install). A beta or release APK is not debuggable,
  and its data cannot be reached without root."
}

require_device() {
    adb get-state >/dev/null 2>&1 || die "no device. Try: make adb-connect"
}

cmd_backup() {
    local app="" dir=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --app) app="${2:-}"; [ -n "$app" ] || die "--app needs a value (google, foss, or a package id)"; shift 2 ;;
            --app=*) app="${1#--app=}"; shift ;;
            -*) die "unknown option: $1" ;;
            *) [ -z "$dir" ] || die "unexpected argument: $1"; dir="$1"; shift ;;
        esac
    done

    require_device
    local app_id stamp dest
    resolve_app_id "$app"
    app_id="$APP_ID"
    stamp=$(date +%Y%m%d-%H%M%S)
    dest="${dir:-$BACKUP_ROOT}/bold_explorer-$(flavor_label_for "$app_id")-$stamp"
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

    echo "$app_id" > "$dest/$SOURCE_FILE"
    chmod 444 "$dest"/*
    echo
    echo "backup of $app_id written to $dest (read-only)"
    echo "restore with: tools/db.sh restore $dest --force --app $(flavor_label_for "$app_id")"
}

cmd_restore() {
    local src="" app="" force=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --force) force=1; shift ;;
            --app) app="${2:-}"; [ -n "$app" ] || die "--app needs a value (google, foss, or a package id)"; shift 2 ;;
            --app=*) app="${1#--app=}"; shift ;;
            -*) die "unknown option: $1" ;;
            *) [ -z "$src" ] || die "unexpected argument: $1"; src="$1"; shift ;;
        esac
    done

    [ -n "$src" ] || die "usage: tools/db.sh restore <backup-dir> --force [--app google|foss]"
    [ -d "$src" ] || die "no such backup directory: $src"
    [ -f "$src/$DB_NAME" ] || die "$src does not contain $DB_NAME"
    [ "$force" = 1 ] || die "restore overwrites the database on the device.
  Re-run with --force if that is what you want:
    tools/db.sh restore $src --force${app:+ --app $app}"

    require_device
    local app_id source_app
    resolve_app_id "$app"
    app_id="$APP_ID"

    # Crossing flavors is a legitimate thing to want (walk the google database on the foss build),
    # so it is allowed — but it is announced, because doing it by accident looks identical to
    # doing it on purpose until you notice the trail list is somebody else's.
    source_app=$([ -f "$src/$SOURCE_FILE" ] && cat "$src/$SOURCE_FILE" || echo "")
    if [ -z "$source_app" ]; then
        echo "note: $src predates provenance tracking — its source app is unknown"
    elif [ "$source_app" != "$app_id" ]; then
        echo "note: cross-flavor restore — backup came from $source_app, restoring into $app_id"
    fi

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
    echo "restored $src into $app_id. Start the app and check the trail list before walking anywhere."
}

case "${1:-}" in
    backup)  shift; cmd_backup "$@" ;;
    restore) shift; cmd_restore "$@" ;;
    *)
        cat >&2 <<USAGE
usage:
  tools/db.sh backup [dir] [--app google|foss]
        pull the database (default dir: $BACKUP_ROOT)
  tools/db.sh restore <dir> --force [--app google|foss]
        push a backup back onto the device

--app picks which install to touch; google and foss are separate app IDs and can
be installed side by side. Defaults to $DEFAULT_APP, and never falls back to the
other one on its own. A full package id also works.

Needs a debuggable build on the device (make install) — run-as cannot reach a
beta or release APK's data.
USAGE
        exit 2
        ;;
esac
