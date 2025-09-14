#!/usr/bin/env bash
SELF="$( cd -- "$( dirname -- "${BASH_SOURCE[0]:-$0}"; )" &> /dev/null && pwd 2> /dev/null; )";
JAR_DIR="$(dirname "$SELF")"
java -jar "$JAR_DIR/libpam4j-client-1.0-SNAPSHOT.jar" "$@"
