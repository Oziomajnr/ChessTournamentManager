#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
runtime="$root/.delivery-temporal"
server="io.github.oziomajnr.chess-delivery.temporal-server"
observer="io.github.oziomajnr.chess-delivery.temporal-observer"
ui="http://127.0.0.1:8233/namespaces/chess-delivery/workflows"
domain="gui/$(id -u)"
registered() { launchctl print "$domain/$1" >/dev/null 2>&1; }

case "${1:-status}" in
  start)
    mkdir -p "$runtime"
    cli="$runtime/bin/temporal"
    if [[ ! -x "$cli" ]]; then
      cli="$(command -v temporal)" || { echo 'Install the Temporal CLI with brew install temporal.' >&2; exit 1; }
    fi
    command -v gh >/dev/null
    cd "$root"
    if [[ ! -x "$runtime/venv/bin/python" ]]; then python3 -m venv "$runtime/venv"; fi
    python="$runtime/venv/bin/python"
    if ! "$python" -c 'from delivery.temporal_status.workflow import IssueDelivery' 2>/dev/null; then
      "$python" -m pip install -r delivery/temporal_status/requirements.txt
    fi
    if ! registered "$server"; then
      for port in 7233 8233; do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null; then
          echo "A different process is already listening on port $port." >&2; exit 1
        fi
      done
      launchctl submit -l "$server" -o "$runtime/server.log" -e "$runtime/server.err" -- \
        "$cli" server start-dev --ip 127.0.0.1 --ui-ip 127.0.0.1 --port 7233 --ui-port 8233 \
        --db-filename "$runtime/temporal.db" --namespace chess-delivery --search-attribute DeliveryStage=Keyword
    fi
    if ! registered "$observer"; then
      launchctl submit -l "$observer" -o "$runtime/observer.log" -e "$runtime/observer.err" -- \
        /usr/bin/env "PATH=$PATH" "PYTHONPATH=$root" "$python" -m delivery.temporal_status \
        --checkpoint "$runtime/cursor.json"
    fi
    echo "$ui"
    ;;
  stop)
    if registered "$observer"; then launchctl remove "$observer"; fi
    if registered "$server"; then launchctl remove "$server"; fi
    ;;
  status)
    for job in "$server" "$observer"; do
      if registered "$job"; then
        launchctl print "$domain/$job" | awk -v name="${job##*.}" '/^[ \t]*state = / { print name, $3; exit }'
      else
        echo "${job##*.} stopped"
      fi
    done
    echo "$ui"
    ;;
  *) echo "Usage: $0 {start|stop|status}" >&2; exit 2 ;;
esac
