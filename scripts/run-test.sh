#!/usr/bin/env bash

LOG_DIR="$HOME"

date_suffix=$(date +"%Y-%m-%dT%H_%M_%S");
SELF="$( cd -- "$( dirname -- "${BASH_SOURCE[0]:-$0}"; )" &> /dev/null && pwd 2> /dev/null; )";

"${SELF}"/run.sh -s=login -e=100 -c=10000 \
 -u=user1,user2,user3,user4,user5 \
 -p=password1,password2,password3,password4,password5 \
  2>&1 | tee "${LOG_DIR}/pam-authentication-test-${date_suffix}.log"