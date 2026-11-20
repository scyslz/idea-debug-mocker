#!/usr/bin/env bash
# Start the sandbox IDE in DEBUG mode.
#
#   ./scripts/dev-debug.sh
#
# Debug mode is what makes no-restart hot reload possible: after a Java change run
#
#   ./gradlew hotSwap
#
# which attaches to the IDE's JDWP port (5005 by default) and redefines the loaded plugin
# classes with the freshly compiled bytecode. No reinstall, no IDE restart.
#
# Changes to plugin.xml (new actions, tool windows, extension points) cannot be applied at
# runtime by the platform — use ./scripts/restart-ide.sh for those.
set -e
cd "$(dirname "$0")/.."

pkill -f 'com.intellij.idea.Main' 2>/dev/null || true
sleep 2

mkdir -p build
nohup env DISPLAY="${DISPLAY:-:1}" ./gradlew --offline --no-daemon runIde --debug-jvm \
    > build/runIde.log 2>&1 &
echo "DebugMock: IDE starting in debug mode (JDWP 5005, log: build/runIde.log)."
echo "DebugMock: after a Java change run: ./gradlew hotSwap"
