#!/bin/bash
# Verifies the Java executable and JVM heap precedence baked into both launchers:
#   Java: PGCK_JAVA_EXE > JAVA_HOME/bin/java > java on PATH
#   heap: explicit -vmargs -Xmx/-Xms > PGCK_JAVA_XMS/XMX > -Xms256m/-Xmx3072m
#   empty/0/-1 heap environment values mean "use default"
#
# It runs the shell launcher against fake Java executables and statically locks
# the equivalent Windows launcher selection, quoting and argument order.
set -u

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
LAUNCHER="$SCRIPT_DIR/../pgcodekeeper-cli.sh"
BAT_LAUNCHER="$SCRIPT_DIR/../pgcodekeeper-cli.bat"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PATH_SHIMDIR="$WORK/path bin"
JAVA_HOME_DIR="$WORK/java home"
EXPLICIT_DIR="$WORK/explicit java"
mkdir -p "$PATH_SHIMDIR" "$JAVA_HOME_DIR/bin" "$EXPLICIT_DIR"
CAP="$WORK/args.txt"
JAVA_EXE_CAP="$WORK/java-exe.txt"

# Each fake Java records which precedence branch selected it and every argument.
cat > "$PATH_SHIMDIR/java" <<'SHIM'
#!/bin/bash
printf '%s\n' PATH > "$JAVA_EXE_CAP"
printf '%s\n' "$@" > "$CAP"
exit 0
SHIM
cat > "$JAVA_HOME_DIR/bin/java" <<'SHIM'
#!/bin/bash
printf '%s\n' JAVA_HOME > "$JAVA_EXE_CAP"
printf '%s\n' "$@" > "$CAP"
exit 0
SHIM
EXPLICIT_JAVA="$EXPLICIT_DIR/java probe"
cat > "$EXPLICIT_JAVA" <<'SHIM'
#!/bin/bash
printf '%s\n' PGCK_JAVA_EXE > "$JAVA_EXE_CAP"
printf '%s\n' "$@" > "$CAP"
exit 0
SHIM
chmod +x "$PATH_SHIMDIR/java" "$JAVA_HOME_DIR/bin/java" "$EXPLICIT_JAVA"

# Launcher copy + dummy jar so the pgcodekeeper-cli-* glob resolves.
cp "$LAUNCHER" "$WORK/pgcodekeeper-cli.sh"
chmod +x "$WORK/pgcodekeeper-cli.sh"
: > "$WORK/pgcodekeeper-cli-15.0.0.jar"

fail=0
run() { # default Java selection: PATH; heap env is inherited from the caller
    : > "$CAP"
    : > "$JAVA_EXE_CAP"
    (
        unset PGCK_JAVA_EXE JAVA_HOME
        PATH="$PATH_SHIMDIR:$PATH" CAP="$CAP" JAVA_EXE_CAP="$JAVA_EXE_CAP" \
            "$WORK/pgcodekeeper-cli.sh" "$@"
    )
}
run_java_home() {
    : > "$CAP"
    : > "$JAVA_EXE_CAP"
    (
        unset PGCK_JAVA_EXE
        PATH="$PATH_SHIMDIR:$PATH" JAVA_HOME="$JAVA_HOME_DIR" \
            CAP="$CAP" JAVA_EXE_CAP="$JAVA_EXE_CAP" \
            "$WORK/pgcodekeeper-cli.sh" "$@"
    )
}
run_explicit_java() {
    : > "$CAP"
    : > "$JAVA_EXE_CAP"
    PATH="$PATH_SHIMDIR:$PATH" JAVA_HOME="$JAVA_HOME_DIR" \
        PGCK_JAVA_EXE="$EXPLICIT_JAVA" CAP="$CAP" JAVA_EXE_CAP="$JAVA_EXE_CAP" \
        "$WORK/pgcodekeeper-cli.sh" "$@"
}
args() { tr '\n' ' ' < "$CAP"; }
assert_has() { if grep -qxF -- "$1" "$CAP"; then echo "  PASS: contains $1"; else echo "  FAIL: missing $1 | args: $(args)"; fail=1; fi; }
assert_absent() { if grep -qxF -- "$1" "$CAP"; then echo "  FAIL: unexpected $1 | args: $(args)"; fail=1; else echo "  PASS: absent  $1"; fi; }
assert_last_heap() { local prefix="$1" expected="$2" last; last="$(grep -E "^${prefix}" "$CAP" | tail -1)"; if [[ "$last" == "$expected" ]]; then echo "  PASS: effective (last) ${prefix} = $expected"; else echo "  FAIL: effective ${prefix} expected $expected got '$last' | args: $(args)"; fail=1; fi; }
assert_last_dedup() { local last; last="$(grep -E '^-XX:[-+]UseStringDeduplication$' "$CAP" | tail -1)"; if [[ "$last" == "$1" ]]; then echo "  PASS: effective (last) string deduplication = $1"; else echo "  FAIL: effective string deduplication expected $1 got '$last' | args: $(args)"; fail=1; fi; }
assert_java() { local actual; actual="$(cat "$JAVA_EXE_CAP")"; if [[ "$actual" == "$1" ]]; then echo "  PASS: selected Java = $1"; else echo "  FAIL: selected Java expected $1 got '$actual'"; fail=1; fi; }
assert_file_has() { if grep -qxF -- "$2" "$1"; then echo "  PASS: $(basename "$1") contains exact: $2"; else echo "  FAIL: $(basename "$1") missing exact: $2"; fail=1; fi; }
assert_file_lacks() { if grep -qFi -- "$2" "$1"; then echo "  FAIL: $(basename "$1") unexpectedly contains: $2"; fail=1; else echo "  PASS: $(basename "$1") lacks: $2"; fi; }
line_of() { grep -nF -- "$2" "$1" | head -1 | cut -d: -f1; }
assert_before() { local first second; first="$(line_of "$1" "$2")"; second="$(line_of "$1" "$3")"; if [[ -n "$first" && -n "$second" && $first -lt $second ]]; then echo "  PASS: $(basename "$1") order: $2 before $3"; else echo "  FAIL: $(basename "$1") order expected: $2 before $3"; fail=1; fi; }
assert_no_path_mutation() { if grep -Eiq '^[[:space:]]*(set[[:space:]]+"?PATH=|PATH=)' "$1"; then echo "  FAIL: $(basename "$1") mutates PATH"; fail=1; else echo "  PASS: $(basename "$1") does not mutate PATH"; fi; }

echo "(a) no env, no -vmargs -> baked defaults"
unset PGCK_JAVA_XMS PGCK_JAVA_XMX
run --version
assert_has "-Xms256m"; assert_has "-Xmx3072m"; assert_absent "-Xmx6144m"

echo "(b) heap env overrides both defaults"
export PGCK_JAVA_XMS=512m PGCK_JAVA_XMX=4096m
run --version
assert_has "-Xms512m"; assert_has "-Xmx4096m"

for sentinel in "" 0 -1; do
    echo "(c) both heap env vars '$sentinel' -> defaults"
    export PGCK_JAVA_XMS="$sentinel" PGCK_JAVA_XMX="$sentinel"
    run --version
    assert_has "-Xms256m"; assert_has "-Xmx3072m"
    [[ -z "$sentinel" ]] || { assert_absent "-Xms${sentinel}"; assert_absent "-Xmx${sentinel}"; }
done

echo "(d) explicit -vmargs heap is last and wins over env"
export PGCK_JAVA_XMS=1g PGCK_JAVA_XMX=4g
run --version -vmargs -Xms768m -Xmx2048m
assert_has "-Xms1g"; assert_has "-Xmx4g"
assert_last_heap -Xms "-Xms768m"; assert_last_heap -Xmx "-Xmx2048m"

echo "(e) heap env whitespace is trimmed"
export PGCK_JAVA_XMS="  384m " PGCK_JAVA_XMX=" 4096m  "
run --version
assert_has "-Xms384m"; assert_has "-Xmx4096m"

echo "(f) PATH java is the fallback"
unset PGCK_JAVA_XMS PGCK_JAVA_XMX
run --version
assert_java PATH

echo "(g) JAVA_HOME/bin/java wins over PATH java and supports spaces"
run_java_home --version
assert_java JAVA_HOME

echo "(h) exact PGCK_JAVA_EXE wins over JAVA_HOME and PATH and supports spaces"
run_explicit_java --version
assert_java PGCK_JAVA_EXE

echo "(i0) string deduplication is on by default and an explicit -vmargs override still wins"
unset PGCK_JAVA_XMS PGCK_JAVA_XMX
run --version
assert_has "-XX:+UseStringDeduplication"
run --version -vmargs -XX:-UseStringDeduplication
assert_last_dedup "-XX:-UseStringDeduplication"

echo "(i) launchers do not mutate PATH"
assert_no_path_mutation "$LAUNCHER"
assert_no_path_mutation "$BAT_LAUNCHER"

echo "(j) Windows launcher has deterministic path, Java and argument handling"
BAT_PATH='set "JAVA_EXE=java"'
BAT_HOME='if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"'
BAT_EXPLICIT='if defined PGCK_JAVA_EXE set "JAVA_EXE=%PGCK_JAVA_EXE%"'
BAT_INVOKE='"%JAVA_EXE%" -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication "-Xms%JXMS%" "-Xmx%JXMX%" %VMARG% -jar "%JARFILE%" %BASEARG%'
BAT_TAB=$'\t'
BAT_XMS_LTAB="if \"%cand:~0,1%\"==\"${BAT_TAB}\" goto xms_ltrim_one"
BAT_XMS_RTAB="if \"%cand:~-1%\"==\"${BAT_TAB}\" goto xms_rtrim_one"
BAT_XMX_LTAB="if \"%cand:~0,1%\"==\"${BAT_TAB}\" goto xmx_ltrim_one"
BAT_XMX_RTAB="if \"%cand:~-1%\"==\"${BAT_TAB}\" goto xmx_rtrim_one"
assert_file_has "$BAT_LAUNCHER" 'setlocal DisableDelayedExpansion'
assert_file_has "$BAT_LAUNCHER" 'set "DIR=%~dp0"'
assert_file_has "$BAT_LAUNCHER" 'set "JARSEARCH=%DIR%pgcodekeeper-cli-*.jar"'
assert_file_has "$BAT_LAUNCHER" 'set "JARFILE=%DIR%%File%"'
assert_file_has "$BAT_LAUNCHER" 'for /f "delims=" %%F in ('"'"'dir /b /a-d "%JARSEARCH%" 2^>nul'"'"') do set "File=%%F"'
assert_file_has "$BAT_LAUNCHER" 'set "JXMS=256m"'
assert_file_has "$BAT_LAUNCHER" 'set "JXMX=3072m"'
assert_file_has "$BAT_LAUNCHER" "$BAT_PATH"
assert_file_has "$BAT_LAUNCHER" "$BAT_HOME"
assert_file_has "$BAT_LAUNCHER" "$BAT_EXPLICIT"
assert_file_has "$BAT_LAUNCHER" "$BAT_INVOKE"
assert_file_has "$BAT_LAUNCHER" 'set BASEARG=%BASEARG% "%~1"'
assert_file_has "$BAT_LAUNCHER" 'set VMARG=%VMARG% "%~1"'
assert_file_has "$BAT_LAUNCHER" "$BAT_XMS_LTAB"
assert_file_has "$BAT_LAUNCHER" "$BAT_XMS_RTAB"
assert_file_has "$BAT_LAUNCHER" "$BAT_XMX_LTAB"
assert_file_has "$BAT_LAUNCHER" "$BAT_XMX_RTAB"
assert_file_lacks "$BAT_LAUNCHER" 'EnableDelayedExpansion'
assert_file_lacks "$BAT_LAUNCHER" '!cand!'
assert_file_lacks "$BAT_LAUNCHER" '!BASEARG!'
assert_file_lacks "$BAT_LAUNCHER" '!VMARG!'
assert_before "$BAT_LAUNCHER" "$BAT_PATH" "$BAT_HOME"
assert_before "$BAT_LAUNCHER" "$BAT_HOME" "$BAT_EXPLICIT"

unset PGCK_JAVA_XMS PGCK_JAVA_XMX
echo
if [[ $fail -eq 0 ]]; then echo "ALL LAUNCHER CASES PASSED"; else echo "SOME LAUNCHER CASES FAILED"; fi
exit $fail
