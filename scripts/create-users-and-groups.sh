#!/usr/bin/env bash

NUMBER_OF_USERS=10

for i in $(seq "$NUMBER_OF_USERS"); do
    echo $i;
    useradd "user$i"
    echo -e -n "password$i\npassword$i" | passwd "user$i"
    groupadd "group$i"
done

for i in $(seq "$NUMBER_OF_USERS"); do
    usermod -a -G group"$NUMBER_OF_USERS" "user$i"
done