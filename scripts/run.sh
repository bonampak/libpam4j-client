#!/usr/bin/env bash

JAVA_CMD="/usr/bin/java"

SELF="$( cd -- "$( dirname -- "${BASH_SOURCE[0]:-$0}"; )" &> /dev/null && pwd 2> /dev/null; )";
JAR_DIR="$(dirname "$SELF")"
"${JAVA_CMD}" -jar "$JAR_DIR/libpam4j-client-1.0-SNAPSHOT.jar" "$@"
