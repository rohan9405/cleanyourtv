#!/usr/bin/env bash
# The OMDb key for IMDb ratings lives on this Mac, never in the repo, and is sent to the Feed add-on over ADB, so
# nothing is typed with the remote.
#
# Usage: my-tv/omdb-key.sh save                       # paste the key once (hidden); it is checked with OMDb, then kept
#        TV_HOST=<tv ip> my-tv/omdb-key.sh send       # give the kept key to the Feed add-on on the TV
#        my-tv/omdb-key.sh send --emulator            # the same for the bravia_preview emulator
#        my-tv/omdb-key.sh forget                     # delete the kept key from this Mac
#
# The key file is ~/.cleantv-private/omdb_key (folder 700, file 600), or $OMDB_KEY_FILE. The script never prints
# the key. The TV keeps its own copy inside the Feed add-on's private storage, since the TV makes the lookups.
set -euo pipefail
cd "$(dirname "$0")/.."
KEY_FILE=${OMDB_KEY_FILE:-$HOME/.cleantv-private/omdb_key}
ACTIVITY=io.github.rohan9405.feed/.FeedActivity

check_key() {
  # OMDb answers {"Response":"True",...} for a good key and {"Response":"False","Error":"Invalid API key!"} otherwise.
  local out
  out=$(curl -fsS --max-time 10 --get "https://www.omdbapi.com/" --data-urlencode "apikey=$1" --data-urlencode "t=Panchayat" 2>/dev/null || true)
  case "$out" in
    *'"Response":"True"'*) return 0 ;;
    *'Invalid API key'*) echo "OMDb says this key is not valid. Did you click the activation link in OMDb's email?" >&2; return 1 ;;
    *) echo "Could not reach OMDb to check the key; saving it anyway." >&2; return 0 ;;
  esac
}

case "${1:-}" in
  save)
    printf 'Paste your OMDb key (input hidden): ' >&2
    read -rs KEY
    echo >&2
    KEY=$(printf '%s' "$KEY" | tr -d '[:space:]')
    [[ "$KEY" =~ ^[A-Za-z0-9]{6,40}$ ]] || { echo "That does not look like an OMDb key (letters and digits)." >&2; exit 2; }
    check_key "$KEY" || exit 1
    mkdir -p "$(dirname "$KEY_FILE")"
    chmod 700 "$(dirname "$KEY_FILE")"
    ( umask 077; printf '%s\n' "$KEY" > "$KEY_FILE" )
    echo "Saved to $KEY_FILE. Next: TV_HOST=<tv ip> my-tv/omdb-key.sh send" >&2
    ;;
  send)
    [ -s "$KEY_FILE" ] || { echo "No key saved yet: run my-tv/omdb-key.sh save first." >&2; exit 1; }
    KEY=$(tr -d '[:space:]' < "$KEY_FILE")
    CMD="am start -n $ACTIVITY --es omdb_key $KEY"
    if [ "${2:-}" = "--emulator" ]; then
      ADB=${ANDROID_HOME:-$HOME/.cleantv-toolchain/sdk}/platform-tools/adb
      "$ADB" -s "${ADB_SERIAL:-emulator-5554}" shell "$CMD" >/dev/null
    else
      [ -n "${TV_HOST:-}" ] || { echo "Set TV_HOST to the TV's IP address, or pass --emulator." >&2; exit 2; }
      PY=tools/venv/bin/python
      "$PY" -I tools/tv.py "$CMD" >/dev/null
    fi
    echo "Key sent; the Feed add-on is refreshing its rows with ratings." >&2
    ;;
  forget)
    rm -f "$KEY_FILE"
    echo "Deleted $KEY_FILE. The TV keeps its copy until the Feed add-on is uninstalled or given another key." >&2
    ;;
  *)
    echo "Usage: my-tv/omdb-key.sh save | send [--emulator] | forget" >&2
    exit 2
    ;;
esac
