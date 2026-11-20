#!/bin/bash
# Astrion: screenshots of the remote, for a Mac (Linux works too), with nothing to install.
#
# In Terminal:
#   bash -c "$(curl -fsSL https://raw.githubusercontent.com/dckiller51/astrion-custom-dashboard/main/scripts/grab_screens_mac.sh)"
#
# Uses adb if it is already there, else downloads Google's platform-tools once into
# ~/.astrion-tools (nothing installed system-wide). Each press of Enter saves what the remote
# shows into a folder on the Desktop, which opens at the end.

set -uo pipefail

TOOLS="$HOME/.astrion-tools"
OUT="$HOME/Desktop/Astrion screenshots"

echo "========================================================"
echo "           ASTRION SCREENSHOTS"
echo "========================================================"
echo

# ---- 1. adb ------------------------------------------------------------------
if command -v adb >/dev/null 2>&1; then
    ADB="adb"
elif [ -x "$TOOLS/platform-tools/adb" ]; then
    ADB="$TOOLS/platform-tools/adb"
else
    case "$(uname -s)" in
        Darwin) OS="darwin" ;;
        Linux) OS="linux" ;;
        *) echo "[ERROR] This script is for a Mac or Linux. On Windows, ask for the Windows version."; exit 1 ;;
    esac
    echo "[1/3] Downloading Google's adb tool (once, about 10 MB)..."
    mkdir -p "$TOOLS"
    if ! curl -fsSL -o "$TOOLS/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"; then
        echo "[ERROR] The download failed. Check the internet connection and try again."
        exit 1
    fi
    unzip -q -o "$TOOLS/platform-tools.zip" -d "$TOOLS" && rm -f "$TOOLS/platform-tools.zip"
    ADB="$TOOLS/platform-tools/adb"
    echo "      Done."
fi
echo

# ---- 2. the remote -------------------------------------------------------------
echo "[2/3] Looking for the remote..."
echo "      Plug it in with a USB cable that carries data, and accept"
echo "      \"Allow USB debugging\" on the remote if it asks."
echo "      (USB debugging must be on, as for the first install.)"
"$ADB" start-server >/dev/null 2>&1
if [ "$("$ADB" devices | grep -c 'device$')" -gt 1 ]; then
    echo "[ERROR] More than one Android device is connected: unplug the others."
    exit 1
fi
"$ADB" wait-for-device
echo "      Remote found."
echo

# ---- 3. screenshots ------------------------------------------------------------
mkdir -p "$OUT"
echo "[3/3] Show on the remote what you want to capture, then press Enter here."
echo "      Do it again for each screen. Type q then Enter when you are done."
echo
n=0
while true; do
    read -r -p "  Enter = screenshot, q = finish: " answer || break
    case "$answer" in q | Q) break ;; esac
    n=$((n + 1))
    file="$OUT/astrion_$(date +%Y%m%d_%H%M%S)_$n.png"
    if "$ADB" exec-out screencap -p >"$file" 2>/dev/null && [ -s "$file" ]; then
        echo "  📸 Saved: $(basename "$file")"
    else
        rm -f "$file"
        n=$((n - 1))
        echo "  ⚠️  No screenshot: is the remote still plugged in and awake?"
    fi
done

echo
echo "========================================================"
echo "Done: $n screenshot(s) in the folder \"Astrion screenshots\" on your Desktop."
echo "Send them in your reply. You can unplug the remote."
echo "========================================================"
if [ "$n" -gt 0 ]; then
    if [ "$(uname -s)" = "Darwin" ]; then open "$OUT"; elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$OUT" >/dev/null 2>&1; fi
fi
