#!/bin/bash
ARG=""
if [[ $(uname) == "Darwin" ]]; then
    ARG="-XstartOnFirstThread"
fi
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

number=0
for var in "$@"
do
    if [[ $var == "-vmargs" ]] ; then
        break
    fi
    (( number++ ))
done

java ${ARG} "${@:$number + 2}" -jar "${DIR}"/pgcodekeeper-cli-* "${@:1:$number}"
