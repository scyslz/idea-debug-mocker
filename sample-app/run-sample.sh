#!/usr/bin/env bash
# Manual (guaranteed) way to verify the runtime agent, without the IDE.
set -e
cd "$(dirname "$0")"
AGENT_JAR="../build/agent/debugmock-agent.jar"

if [ ! -f "$AGENT_JAR" ]; then
  echo "Agent jar missing. Build it first:  cd .. && ./gradlew agentJar" >&2
  exit 1
fi

rm -rf out && mkdir -p out
javac -d out src/demo/*.java

echo "=== WITHOUT agent (real methods) ==="
java -cp out demo.Main

echo
echo "=== WITH agent (rules in .debugmock/rules.json) ==="
java -javaagent:"$(realpath "$AGENT_JAR")" \
     -Ddebugmock.rules="$(realpath .debugmock/rules.json)" \
     -cp out demo.Main
