#!/usr/bin/env bash
set -e
./gradlew build
mkdir -p ../testserver/plugins
cp build/libs/CivitasCraft-*.jar ../testserver/plugins/
echo "Deployed. Run 'reload confirm' in the server console, or restart the server."
