#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BIN="$ROOT_DIR/desktop/build/compose/binaries/main/app/ClickControl/bin/ClickControl"
ICON_PATH="$ROOT_DIR/desktop/src/main/resources/icons/clickcontrol.png"

pick_jdk21() {
    for candidate in \
        "${JDK21_HOME:-}" \
        "${JAVA_HOME:-}" \
        "$HOME/sdk/jdk-21" \
        "$HOME"/.config/craft-anything/engines/jdk/jdk-21* \
        /usr/lib/jvm/*; do
        if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
            if "$candidate/bin/java" -version 2>&1 | grep -qE 'version "21(\.|")'; then
                echo "$candidate"
                return 0
            fi
        fi
    done
    return 1
}

if [[ ! -x "$APP_BIN" ]]; then
    echo "ClickControl desktop binary not built yet; building now..."
    if ! JDK21="$(pick_jdk21)"; then
        echo "ERROR: JDK 21 not found. Set JAVA_HOME to a JDK 21 installation and retry." >&2
        exit 1
    fi
    (
        cd "$ROOT_DIR"
        JAVA_HOME="$JDK21" ./gradlew :desktop:createDistributable -PskipAndroid=true --no-daemon
    )
fi

if [[ "${1:-}" == "--install-shortcut" ]]; then
    shift
    DESKTOP_DIR="$HOME/.local/share/applications"
    mkdir -p "$DESKTOP_DIR"
    cat > "$DESKTOP_DIR/clickcontrol-ClickControl.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=ClickControl
Comment=Zwift Click Controller Bridge
Exec="$APP_BIN"
Icon=$ICON_PATH
Terminal=false
Categories=Utility;Education;
StartupNotify=false
EOF
    chmod 0644 "$DESKTOP_DIR/clickcontrol-ClickControl.desktop"
    echo "Installed desktop launcher: $DESKTOP_DIR/clickcontrol-ClickControl.desktop"
    if [[ $# -eq 0 ]]; then
        exit 0
    fi
fi

exec "$APP_BIN" "$@"
