@echo off
setlocal DisableDelayedExpansion

REM Quote every path assignment so cmd metacharacters in the install directory
REM remain data. Only JAR files participate in launcher discovery.
set "DIR=%~dp0"
set "JARSEARCH=%DIR%pgcodekeeper-cli-*.jar"
set "File="
for /f "delims=" %%F in ('dir /b /a-d "%JARSEARCH%" 2^>nul') do set "File=%%F"
set "JARFILE=%DIR%%File%"

REM Java executable selection is deterministic and does not mutate PATH.
REM Later assignments have higher precedence; every path is quoted at launch.
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if defined PGCK_JAVA_EXE set "JAVA_EXE=%PGCK_JAVA_EXE%"

REM JVM heap size is resolved with the following precedence (highest wins):
REM   1. Explicit -vmargs -Xms.../-Xmx... on the command line. They are forwarded
REM      after the values emitted below and the JVM honors the LAST -Xms/-Xmx, so
REM      an explicit -vmargs -Xmx8g still wins without extra parsing.
REM   2. Env vars PGCK_JAVA_XMS / PGCK_JAVA_XMX when set to a valid value;
REM      surrounding spaces and tabs are trimmed, while empty, "0" and "-1"
REM      mean "use default".
REM   3. Baked-in defaults: -Xms256m -Xmx3072m.
set "JXMS=256m"
set "JXMX=3072m"

set "cand=%PGCK_JAVA_XMS%"
:xms_ltrim
if not defined cand goto xms_done
if "%cand:~0,1%"==" " goto xms_ltrim_one
if "%cand:~0,1%"=="	" goto xms_ltrim_one
goto xms_rtrim

:xms_ltrim_one
set "cand=%cand:~1%"
goto xms_ltrim

:xms_rtrim
if not defined cand goto xms_done
if "%cand:~-1%"==" " goto xms_rtrim_one
if "%cand:~-1%"=="	" goto xms_rtrim_one
goto xms_value

:xms_rtrim_one
set "cand=%cand:~0,-1%"
goto xms_rtrim

:xms_value
if "%cand%"=="0" goto xms_done
if "%cand%"=="-1" goto xms_done
set "JXMS=%cand%"

:xms_done
set "cand=%PGCK_JAVA_XMX%"
:xmx_ltrim
if not defined cand goto xmx_done
if "%cand:~0,1%"==" " goto xmx_ltrim_one
if "%cand:~0,1%"=="	" goto xmx_ltrim_one
goto xmx_rtrim

:xmx_ltrim_one
set "cand=%cand:~1%"
goto xmx_ltrim

:xmx_rtrim
if not defined cand goto xmx_done
if "%cand:~-1%"==" " goto xmx_rtrim_one
if "%cand:~-1%"=="	" goto xmx_rtrim_one
goto xmx_value

:xmx_rtrim_one
set "cand=%cand:~0,-1%"
goto xmx_rtrim

:xmx_value
if "%cand%"=="0" goto xmx_done
if "%cand%"=="-1" goto xmx_done
set "JXMX=%cand%"

:xmx_done
REM Parse without parenthesized blocks or delayed expansion. Every external
REM argument is stripped once, deliberately re-quoted, and never reparsed.
set "BASEARG="
set "VMARG="

:basearg_loop
if "%~1"=="" goto arguments_done
if "%~1"=="-vmargs" goto vmarg_start
set BASEARG=%BASEARG% "%~1"
shift
goto basearg_loop

:vmarg_start
shift

:vmarg_loop
if "%~1"=="" goto arguments_done
set VMARG=%VMARG% "%~1"
shift
goto vmarg_loop

:arguments_done
REM ExitOnOutOfMemoryError makes the JVM die immediately on heap exhaustion.
REM UseStringDeduplication collapses the two comparison sides' identical source
REM texts during the load phase, where a comparison peaks; it is what keeps the
REM default -Xmx3072m from running out of heap on a large project.
REM Resolved heap values precede forwarded VMARG, preserving JVM last-value wins.
"%JAVA_EXE%" -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication "-Xms%JXMS%" "-Xmx%JXMX%" %VMARG% -jar "%JARFILE%" %BASEARG%
