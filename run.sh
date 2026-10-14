#!/usr/bin/env bash
# Requires JDK 17+ (tested on 21). No Maven/Gradle or downloads needed.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/notes/*.java
exec java -cp out notes.Server
