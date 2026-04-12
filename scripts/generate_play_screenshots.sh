#!/usr/bin/env bash
#
# Chess Tournament Manager - Play Store Screenshot Generator
#
# Uses UI Automator to find and tap elements by content-description/text,
# so taps work regardless of screen size. Falls back to coordinates if needed.
#
# IMPORTANT: Does NOT use back button when on Home (Players/Tournaments tabs)
# because back would exit the app.
#
# Requires: device connected, app installed, Python 3 (for XML parsing)
# Usage: ./scripts/generate_play_screenshots.sh

set -e

ADB="${ADB:-}"
if [[ -n "$ANDROID_HOME" ]]; then
  ADB="${ANDROID_HOME}/platform-tools/adb"
elif [[ -d "$HOME/Library/Android/sdk" ]]; then
  ADB="$HOME/Library/Android/sdk/platform-tools/adb"
fi
[[ -z "$ADB" ]] && ADB="adb"

PACKAGE="com.chunkymonkey.chess.tournament"
ACTIVITY="${PACKAGE}/.MainActivity"
OUTPUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/fastlane/metadata/android/en-US/images/phoneScreenshots"
WAIT=2
LONG_WAIT=3

# Screen size for coordinate fallback and swipes
W=960
H=2142

screenshot() {
  mkdir -p "$OUTPUT_DIR"
  "$ADB" exec-out screencap -p > "${OUTPUT_DIR}/${1}.png"
  echo "  Captured: $1"
}

tap() {
  "$ADB" shell input tap "$1" "$2"
  sleep "$WAIT"
}

# Dump UI and tap element by content-desc or text (uses Python for XML parsing)
tap_by() {
  local search="$1"
  "$ADB" shell uiautomator dump /sdcard/window_dump.xml 2>/dev/null
  sleep 0.5
  local xml
  xml=$("$ADB" shell cat /sdcard/window_dump.xml 2>/dev/null | tr -d '\r')

  # Python one-liner to find element and extract center of bounds
  local result
  result=$(echo "$xml" | python3 -c "
import sys, re, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except:
    sys.exit(1)
search = (sys.argv[1] or '').strip().lower()
def matches(desc, text):
    d, t = (desc or '').strip().lower(), (text or '').strip().lower()
    if not search: return False
    if d == search or t == search: return True
    # Substring but avoid 'Back' matching 'Backup' - require word boundary
    def word_match(s):
        if search not in s: return False
        idx = s.find(search)
        end = idx + len(search)
        before_ok = idx == 0 or not s[idx-1].isalnum()
        after_ok = end >= len(s) or not s[end].isalnum()
        return before_ok and after_ok
    return word_match(d) or word_match(t) or search in d or search in t
candidates = []
def find_nodes(node):
    desc, text = node.get('content-desc') or '', node.get('text') or ''
    if matches(desc, text):
        b = node.get('bounds')
        if b and '[' in b:
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
            if m:
                x1,y1,x2,y2 = map(int, m.groups())
                cx, cy = (x1+x2)//2, (y1+y2)//2
                candidates.append((cy, cx))
    for c in node:
        find_nodes(c)
find_nodes(root)
# Prefer top-left for Back/Save/Cancel; bottom-right for FAB (Add Player, Create Tournament)
if candidates:
    if any(x in search for x in ['back', 'save', 'cancel']):
        candidates.sort()
    else:
        candidates.sort(key=lambda c: (-c[0], -c[1]))
    cy, cx = candidates[0]
    print(cx, cy)
" "$search" 2>/dev/null)

  if [[ -n "$result" ]]; then
    local cx cy
    read -r cx cy <<< "$result"
    echo "    [tap_by '$search' -> ($cx, $cy)]"
    tap "$cx" "$cy"
    return 0
  else
    echo "    [tap_by '$search' NOT FOUND]"
    return 1
  fi
}

# Fallback: tap by coordinates (x y)
tap_at() {
  local xy=($1)
  echo "    [tap_at (${xy[0]}, ${xy[1]})]"
  tap "${xy[0]}" "${xy[1]}"
}

type_text() {
  local t="${1// /%s}"
  "$ADB" shell input text "$t"
  sleep 0.3
}

keyevent() {
  "$ADB" shell input keyevent "$1"
  sleep 0.5
}

dismiss_keyboard() {
  keyevent KEYCODE_ESCAPE
  sleep 0.3
}

detect_screen() {
  local size
  size=$("$ADB" shell wm size 2>/dev/null | sed -n 's/.*Physical size: \([0-9]*x[0-9]*\).*/\1/p')
  [[ -n "$size" ]] && { W=${size%x*}; H=${size#*x}; }
  echo "Screen: ${W}x${H}"
}

launch_app() {
  echo "Launching app..."
  "$ADB" shell am force-stop "$PACKAGE"
  sleep 1
  "$ADB" shell am start -n "$ACTIVITY"
  sleep "$LONG_WAIT"
}

# Use tap_by when possible; fallback to tap_at with given coords
add_player() {
  local name="$1"
  local rating="${2:-}"
  echo "Adding player: $name"

  tap_by "Add Player" || tap_at "850 $((H*91/100))"
  sleep "$WAIT"

  tap_by "Full Name" || tap "$((W/2))" "$((H*22/100))"
  sleep 0.5
  type_text "$name"
  sleep 0.2

  if [[ -n "$rating" ]]; then
    keyevent KEYCODE_TAB
    keyevent KEYCODE_TAB
    sleep 0.2
    type_text "$rating"
    sleep 0.2
  fi

  tap_by "Save player" || tap_by "Save" || tap "$((W*80/100))" "$((H*48/100))"
  sleep "$WAIT"
}

create_tournament() {
  local name="$1"
  echo "Creating tournament: $name"

  tap_by "Create Tournament" || tap_at "850 $((H*91/100))"
  sleep "$LONG_WAIT"

  tap_by "Tournament Name" || tap "$((W/2))" "$((H*22/100))"
  sleep 0.5
  type_text "$name"
  dismiss_keyboard
  sleep 0.5

  # Scroll down
  "$ADB" shell input swipe "$((W/2))" "$((H*65/100))" "$((W/2))" "$((H*25/100))" 400
  sleep "$WAIT"

  tap_by "All" || tap "$((W*85/100))" "$((H*48/100))"
  sleep 0.5

  "$ADB" shell input swipe "$((W/2))" "$((H*55/100))" "$((W/2))" "$((H*15/100))" 400
  sleep "$WAIT"

  tap_by "Create Tournament" || tap "$((W/2))" "$((H*82/100))"
  sleep "$LONG_WAIT"
}

main() {
  echo "=== Chess Tournament - Play Store Screenshot Generator ==="
  "$ADB" devices | grep -q 'device$' || { echo "No device found"; exit 1; }
  command -v python3 &>/dev/null || { echo "Python 3 required for UI Automator parsing"; exit 1; }

  detect_screen
  launch_app

  screenshot "01_home_players_empty"
  sleep "$WAIT"

  add_player "Alice"
  add_player "Bob" "1650"
  add_player "Carol" "1720"
  add_player "Dave" "1580"
  add_player "Eve" "1890"
  add_player "Frank" "1420"
  add_player "Grace" "2010"
  add_player "Henry" "1540"

  screenshot "02_home_players_list"
  sleep "$WAIT"

  tap_by "Tournaments" || tap_at "$((W*75/100)) $((H*98/100))"
  sleep "$WAIT"
  screenshot "03_home_tournaments_empty"
  sleep "$WAIT"

  create_tournament "ClubChampionship2025"

  screenshot "04_home_tournaments_list"
  sleep "$WAIT"

  echo "Opening tournament..."
  tap_by "ClubChampionship2025" || tap "$((W/2))" "$((H*38/100))"
  sleep "$LONG_WAIT"
  screenshot "05_tournament_detail_standings_empty"
  sleep "$WAIT"

  echo "Generating Round 1..."
  tap_by "Generate Round" || tap "$((W/2))" "$((H*32/100))"
  sleep "$LONG_WAIT"
  screenshot "06_tournament_detail_round1_generated"
  sleep "$WAIT"

  tap_by "Rounds" || tap "$((W*42/100))" "$((H*17/100))"
  sleep "$WAIT"
  screenshot "07_tournament_rounds_tab"
  sleep "$WAIT"

  tap_by "Round 1" || tap "$((W/2))" "$((H*42/100))"
  sleep "$LONG_WAIT"
  screenshot "08_round_detail_pairings"
  sleep "$WAIT"

  tap_by "1 - 0" || tap "$((W*22/100))" "$((H*55/100))"
  sleep 0.5
  "$ADB" shell input swipe "$((W/2))" "$((H*58/100))" "$((W/2))" "$((H*35/100))" 250
  sleep 0.5
  tap_by "0 - 1" || tap "$((W*78/100))" "$((H*55/100))"
  sleep 0.5
  screenshot "09_round_detail_with_results"
  sleep "$WAIT"

  tap_by "Back" || tap_at "100 $((H*7/100))"
  sleep "$LONG_WAIT"

  tap_by "Standings" || tap "$((W*22/100))" "$((H*17/100))"
  sleep "$WAIT"
  screenshot "10_tournament_standings"
  sleep "$WAIT"

  tap_by "Generate Round" || tap "$((W/2))" "$((H*32/100))"
  sleep "$LONG_WAIT"
  screenshot "11_tournament_round2_generated"
  sleep "$WAIT"

  tap_by "Cross" || tap "$((W*72/100))" "$((H*17/100))"
  sleep "$WAIT"
  screenshot "12_tournament_crosstable"
  sleep "$WAIT"

  tap_by "Settings" || tap "$((W*92/100))" "$((H*7/100))"
  sleep "$LONG_WAIT"
  screenshot "13_tournament_settings"
  sleep "$WAIT"

  tap_by "Back" || tap_at "100 $((H*7/100))"
  sleep "$WAIT"
  tap_by "Back" || tap_at "100 $((H*7/100))"
  sleep "$WAIT"

  tap_by "Backup" || tap "$((W*94/100))" "$((H*7/100))"
  sleep "$LONG_WAIT"
  screenshot "14_backup_restore"
  sleep "$WAIT"

  tap_by "Back" || tap_at "100 $((H*7/100))"
  sleep "$WAIT"
  tap_by "Create Tournament" || tap_at "850 $((H*91/100))"
  sleep "$LONG_WAIT"
  screenshot "15_create_tournament"
  sleep "$WAIT"

  tap_by "Back" || tap_at "100 $((H*7/100))"
  sleep "$WAIT"

  echo ""
  echo "Resizing screenshots..."
  for f in "$OUTPUT_DIR"/*.png; do
    [[ -f "$f" ]] && sips -z 2409 1080 "$f" 2>/dev/null || true
  done

  echo ""
  echo "=== Done! Screenshots: $OUTPUT_DIR ==="
  ls -la "$OUTPUT_DIR"/*.png 2>/dev/null | tail -20
}

main "$@"
