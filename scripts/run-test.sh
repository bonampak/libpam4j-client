#!/usr/bin/env bash

USER1_USERNAME="$1"
USER2_USERNAME="$2"

NR_OF_THREADS="$3"
NR_OF_CALLABLES="$4"


SELF="$( cd -- "$( dirname -- "${BASH_SOURCE[0]:-$0}"; )" &> /dev/null && pwd 2> /dev/null; )";
date_suffix=$(date +"%Y-%m-%dT%H_%M_%S")
"${SELF}/run.sh" "${USER1_USERNAME}" "${USER2_USERNAME}" "${NR_OF_THREADS}" "${NR_OF_CALLABLES}" 2>&1 | tee ~/test-libpam4j-"${NR_OF_THREADS}"-threads-"${NR_OF_CALLABLES}"-callables-"${date_suffix}".log