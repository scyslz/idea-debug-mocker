#!/usr/bin/env bash
# Rebuild the plugin and restart the sandbox IDE in the background.
#
#   ./scripts/restart-ide.sh
#
# Only needed when plugin.xml changes (new actions, tool window, extension points): the platform
# cannot register / unregister extensions at runtime. For Java changes keep the IDE running —
# start it once with ./scripts/dev-debug.sh and then use `./gradlew hotSwap`.
set -e
cd "$(dirname "$0")/.."

pkill -f 'com.intellij.idea.Main' 2>/dev/null || true
sleep 2

mkdir -p build
# --no-daemon: the daemon may have been started without DISPLAY, and the IDE would then open on
# a different (invisible) screen. A single-use build JVM always inherits our environment.
# --debug-jvm keeps the JDWP port open, so `./gradlew hotSwap` works after a restart too.
nohup env DISPLAY="${DISPLAY:-:1}" ./gradlew --offline --no-daemon runIde --debug-jvm \
    > build/runIde.log 2>&1 &
echo "DebugMock: plugin rebuilt; the sandbox IDE is restarting (log: build/runIde.log)."
echo "DebugMock: later Java changes can be applied with: ./gradlew hotSwap"
