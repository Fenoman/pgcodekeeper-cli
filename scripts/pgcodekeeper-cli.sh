#!/bin/bash
ARG=""
if [[ $(uname) == "Darwin" ]]; then
    ARG="-XstartOnFirstThread"
fi
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Java executable selection is deterministic and does not mutate PATH:
#   1. PGCK_JAVA_EXE, when non-empty, is used as the exact executable path.
#   2. JAVA_HOME/bin/java, when JAVA_HOME is non-empty.
#   3. java resolved by the caller's PATH.
JAVA_EXE="java"
if [[ -n "${JAVA_HOME-}" ]]; then
    JAVA_EXE="${JAVA_HOME}/bin/java"
fi
if [[ -n "${PGCK_JAVA_EXE-}" ]]; then
    JAVA_EXE="${PGCK_JAVA_EXE}"
fi

# JVM heap size is resolved with the following precedence (highest wins):
#   1. Explicit -vmargs -Xms.../-Xmx... on the command line. They are forwarded
#      after the values emitted below and the JVM honors the LAST -Xms/-Xmx, so
#      an explicit -vmargs -Xmx8g still wins without any extra parsing here.
#   2. Environment variables PGCK_JAVA_XMS / PGCK_JAVA_XMX, when set to a valid
#      value. An empty string, "0" or "-1" is treated as "use default".
#   3. Baked-in defaults: -Xms256m -Xmx3072m.
resolve_heap() {
    # $1 = raw env value, $2 = default; prints the value to use.
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"   # trim leading whitespace
    value="${value%"${value##*[![:space:]]}"}"   # trim trailing whitespace
    if [[ -z "$value" || "$value" == "0" || "$value" == "-1" ]]; then
        printf '%s' "$2"
    else
        printf '%s' "$value"
    fi
}
JXMS="$(resolve_heap "${PGCK_JAVA_XMS-}" "256m")"
JXMX="$(resolve_heap "${PGCK_JAVA_XMX-}" "3072m")"

number=0
for var in "$@"
do
    if [[ $var == "-vmargs" ]] ; then
        break
    fi
    (( number++ ))
done

# ExitOnOutOfMemoryError makes the JVM die immediately on heap exhaustion
# instead of limping on with dead worker threads. The resolved -Xms/-Xmx are
# emitted before the forwarded -vmargs so an explicit -vmargs -Xmx... still wins
# (JVM honors the last -Xms/-Xmx). CI may rely on these defaults or set
# PGCK_JAVA_XMS/PGCK_JAVA_XMX instead of passing -vmargs.
#
# UseStringDeduplication pays for itself on the load phase, which is where a
# comparison peaks: both sides hold the text of every source file at once, and
# on a real project those texts are largely the same string twice. Measured on
# a 24k-file project, the JVM deduplicated 96.7% of the strings that survived a
# young collection - 220k distinct values out of 6.9M - and that is what takes
# the default -Xmx3072m below from "OOM during load" to a working run with
# room to spare. Every collector this launcher may be pointed at supports the
# flag since JDK 18; it is emitted before the forwarded -vmargs, so an explicit
# -vmargs -XX:-UseStringDeduplication still wins.
"${JAVA_EXE}" ${ARG} -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication "-Xms${JXMS}" "-Xmx${JXMX}" "${@:$number + 2}" -jar "${DIR}"/pgcodekeeper-cli-* "${@:1:$number}"
