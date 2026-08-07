@echo off
setlocal DisableDelayedExpansion

REM Run with a real Windows command processor, for example:
REM   wine64 cmd /d /v:off /c Z:\path\to\windows-launcher-test.bat
REM The same file is copied as fake java.cmd; the marker selects that mode.
if defined PGCK_WINDOWS_FAKE_JAVA goto fake_java

set "SCRIPT_DIR=%~dp0"
set "LAUNCHER_SOURCE=%SCRIPT_DIR%..\pgcodekeeper-cli.bat"
if "%~1"=="" goto launcher_source_ready
set "LAUNCHER_SOURCE=%~1"
:launcher_source_ready
set "WORK=%TEMP%\pgck launcher & (meta) !"
set "INSTALL=%WORK%\install & (dir) !"
set "PATH_JAVA=%WORK%\path & (java) !"
set "JAVA_HOME_DIR=%WORK%\home & (java) !"
set "EXPLICIT_DIR=%WORK%\explicit & (java) !"
set "EXPLICIT_JAVA=%EXPLICIT_DIR%\java probe.cmd"
set "LAUNCHER=%INSTALL%\pgcodekeeper-cli.bat"
set "PGCK_CAPTURE=%WORK%\capture.txt"
set "JAVA_HOME_OUTPUT=%WORK%\java-home-output.txt"
set "JDBC_ARG=jdbc:postgresql://db/name?user=test&password=p!ss&(sslmode=disable)"
set "VMARG_META=-Dprobe=value&bang!&(kept)"
set "FAILURES=0"
set "ASSERTIONS=0"

if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%INSTALL%"
mkdir "%PATH_JAVA%"
mkdir "%JAVA_HOME_DIR%\bin"
mkdir "%EXPLICIT_DIR%"
copy /y "%LAUNCHER_SOURCE%" "%LAUNCHER%" >nul
copy /y "%~f0" "%PATH_JAVA%\java.cmd" >nul
copy /y "%~f0" "%EXPLICIT_JAVA%" >nul
copy /y "%WINDIR%\system32\hostname.exe" "%JAVA_HOME_DIR%\bin\java.exe" >nul
type nul > "%INSTALL%\pgcodekeeper-cli-15.0.0-test.jar"

set "ORIGINAL_PATH=%PATH%"
set "PATH=%PATH_JAVA%;%ORIGINAL_PATH%"
set "PGCK_WINDOWS_FAKE_JAVA=1"

echo (a) exact PGCK_JAVA_EXE, metachar install path, defaults and base args
set "PGCK_FAKE_ID=PGCK_JAVA_EXE"
set "PGCK_JAVA_EXE=%EXPLICIT_JAVA%"
set "JAVA_HOME=%JAVA_HOME_DIR%"
set "PGCK_JAVA_XMS="
set "PGCK_JAVA_XMX="
if exist "%PGCK_CAPTURE%" del /q "%PGCK_CAPTURE%"
call "%LAUNCHER%" "%JDBC_ARG%" --version
call :load_capture
set "EXPECTED=ID=PGCK_JAVA_EXE"
set "ACTUAL=ID=%CAP_ID%"
call :assert_equal "PGCK_JAVA_EXE wins over JAVA_HOME and PATH"
set "EXPECTED=EXE=%EXPLICIT_JAVA%"
set "ACTUAL=EXE=%CAP_EXE%"
call :assert_equal "PGCK_JAVA_EXE path preserves spaces and metacharacters"
set "EXPECTED=ARG[2]=-Xms256m"
set "ACTUAL=ARG[2]=%CAP_ARG[2]%"
call :assert_equal "default Xms is 256m"
set "EXPECTED=ARG[3]=-Xmx3072m"
set "ACTUAL=ARG[3]=%CAP_ARG[3]%"
call :assert_equal "default Xmx is 3072m"
set "EXPECTED=ARG[5]=%INSTALL%\pgcodekeeper-cli-15.0.0-test.jar"
set "ACTUAL=ARG[5]=%CAP_ARG[5]%"
call :assert_equal "jar path preserves install-directory metacharacters"
set "EXPECTED=ARG[6]=%JDBC_ARG%"
set "ACTUAL=ARG[6]=%CAP_ARG[6]%"
call :assert_equal "base JDBC argument preserves ampersand and exclamation"
set "EXPECTED=ARG[7]=--version"
set "ACTUAL=ARG[7]=%CAP_ARG[7]%"
call :assert_equal "base argument order is preserved"
set "EXPECTED="
set "ACTUAL=%CAP_ARG[8]%"
call :assert_equal "default launch has no unexpected extra arguments"

echo (b) PATH java is the final fallback
set "PGCK_FAKE_ID=PATH"
set "PGCK_JAVA_EXE="
set "JAVA_HOME="
if exist "%PGCK_CAPTURE%" del /q "%PGCK_CAPTURE%"
call "%LAUNCHER%" --version
call :load_capture
set "EXPECTED=ID=PATH"
set "ACTUAL=ID=%CAP_ID%"
call :assert_equal "PATH java is selected when overrides are absent"
set "EXPECTED=EXE=%PATH_JAVA%\java.cmd"
set "ACTUAL=EXE=%CAP_EXE%"
call :assert_equal "PATH java path preserves metacharacters"

echo (c) JAVA_HOME wins over PATH
set "PGCK_FAKE_ID=PATH"
set "JAVA_HOME=%JAVA_HOME_DIR%"
if exist "%PGCK_CAPTURE%" del /q "%PGCK_CAPTURE%"
if exist "%JAVA_HOME_OUTPUT%" del /q "%JAVA_HOME_OUTPUT%"
call "%LAUNCHER%" --version > "%JAVA_HOME_OUTPUT%" 2>&1
set "JAVA_HOME_RC=%ERRORLEVEL%"
call :assert_capture_absent "JAVA_HOME java.exe is selected instead of PATH java.cmd"
set "JAVA_HOME_FIRST_LINE="
for /f "usebackq delims=" %%L in ("%JAVA_HOME_OUTPUT%") do if not defined JAVA_HOME_FIRST_LINE set "JAVA_HOME_FIRST_LINE=%%L"
set "EXPECTED=Error: Invalid option 'X'."
set "ACTUAL=%JAVA_HOME_FIRST_LINE%"
call :assert_equal "JAVA_HOME path with metacharacters executes java.exe"
set "EXPECTED=1"
set "ACTUAL=%JAVA_HOME_RC%"
call :assert_equal "JAVA_HOME java.exe exit status is propagated"

echo (d) tab-padded sentinels select defaults
set "PGCK_FAKE_ID=PGCK_JAVA_EXE"
set "PGCK_JAVA_EXE=%EXPLICIT_JAVA%"
set "PGCK_JAVA_XMS=	0	"
set "PGCK_JAVA_XMX=	-1	"
if exist "%PGCK_CAPTURE%" del /q "%PGCK_CAPTURE%"
call "%LAUNCHER%" --version
call :load_capture
set "EXPECTED=ARG[2]=-Xms256m"
set "ACTUAL=ARG[2]=%CAP_ARG[2]%"
call :assert_equal "tab-padded Xms sentinel selects default"
set "EXPECTED=ARG[3]=-Xmx3072m"
set "ACTUAL=ARG[3]=%CAP_ARG[3]%"
call :assert_equal "tab-padded Xmx sentinel selects default"

echo (e) tab-padded heap values are trimmed
set "PGCK_JAVA_XMS=	512m	"
set "PGCK_JAVA_XMX=	4096m	"
if exist "%PGCK_CAPTURE%" del /q "%PGCK_CAPTURE%"
call "%LAUNCHER%" --version
call :load_capture
set "EXPECTED=ARG[2]=-Xms512m"
set "ACTUAL=ARG[2]=%CAP_ARG[2]%"
call :assert_equal "tab-padded Xms value is trimmed"
set "EXPECTED=ARG[3]=-Xmx4096m"
set "ACTUAL=ARG[3]=%CAP_ARG[3]%"
call :assert_equal "tab-padded Xmx value is trimmed"

echo (f) explicit vmargs remain last and preserve metacharacters
set "PGCK_JAVA_XMS=1g"
set "PGCK_JAVA_XMX=4g"
if exist "%PGCK_CAPTURE%" del /q "%PGCK_CAPTURE%"
call "%LAUNCHER%" "%JDBC_ARG%" --version -vmargs -Xms768m -Xmx2048m "%VMARG_META%"
call :load_capture
set "EXPECTED=ARG[2]=-Xms1g"
set "ACTUAL=ARG[2]=%CAP_ARG[2]%"
call :assert_equal "environment Xms precedes explicit vmargs"
set "EXPECTED=ARG[3]=-Xmx4g"
set "ACTUAL=ARG[3]=%CAP_ARG[3]%"
call :assert_equal "environment Xmx precedes explicit vmargs"
set "EXPECTED=ARG[4]=-Xms768m"
set "ACTUAL=ARG[4]=%CAP_ARG[4]%"
call :assert_equal "explicit Xms follows resolved default"
set "EXPECTED=ARG[5]=-Xmx2048m"
set "ACTUAL=ARG[5]=%CAP_ARG[5]%"
call :assert_equal "explicit Xmx follows resolved default"
set "EXPECTED=ARG[6]=%VMARG_META%"
set "ACTUAL=ARG[6]=%CAP_ARG[6]%"
call :assert_equal "explicit vmarg preserves ampersand and exclamation"
set "EXPECTED=ARG[7]=-jar"
set "ACTUAL=ARG[7]=%CAP_ARG[7]%"
call :assert_equal "all explicit vmargs remain before -jar"
set "EXPECTED=ARG[9]=%JDBC_ARG%"
set "ACTUAL=ARG[9]=%CAP_ARG[9]%"
call :assert_equal "base JDBC argument remains after jar"
set "EXPECTED=ARG[10]=--version"
set "ACTUAL=ARG[10]=%CAP_ARG[10]%"
call :assert_equal "base arguments retain their original order"
set "EXPECTED="
set "ACTUAL=%CAP_ARG[11]%"
call :assert_equal "vmargs launch has no unexpected extra arguments"

set "PATH=%ORIGINAL_PATH%"
if exist "%WORK%" rmdir /s /q "%WORK%"
echo.
if "%FAILURES%"=="0" goto all_passed
echo SOME WINDOWS LAUNCHER CASES FAILED: %FAILURES% of %ASSERTIONS%
exit /b 1

:all_passed
echo ALL WINDOWS LAUNCHER CASES PASSED: %ASSERTIONS%
exit /b 0

:load_capture
set "CAP_ID="
set "CAP_EXE="
for /l %%N in (1,1,12) do set "CAP_ARG[%%N]="
if not exist "%PGCK_CAPTURE%" exit /b 0
for /f "usebackq tokens=1,* delims==" %%A in ("%PGCK_CAPTURE%") do set "CAP_%%A=%%B"
exit /b 0

:assert_equal
set /a ASSERTIONS+=1 >nul
if "%ACTUAL%"=="%EXPECTED%" goto assert_equal_pass
echo   FAIL: %~1
set "PGCK_PRINT=    expected: %EXPECTED%"
<nul set /p "=%PGCK_PRINT%"
echo.
set "PGCK_PRINT=      actual: %ACTUAL%"
<nul set /p "=%PGCK_PRINT%"
echo.
set /a FAILURES+=1 >nul
exit /b 0

:assert_equal_pass
echo   PASS: %~1
exit /b 0

:assert_capture_absent
set /a ASSERTIONS+=1 >nul
if not exist "%PGCK_CAPTURE%" goto assert_capture_absent_pass
echo   FAIL: %~1
type "%PGCK_CAPTURE%"
set /a FAILURES+=1 >nul
exit /b 0

:assert_capture_absent_pass
echo   PASS: %~1
exit /b 0

:fake_java
set "PGCK_FAKE_INDEX=0"
set "PGCK_FAKE_LINE=ID=%PGCK_FAKE_ID%"
> "%PGCK_CAPTURE%" <nul set /p "=%PGCK_FAKE_LINE%"
>> "%PGCK_CAPTURE%" echo.
set "PGCK_FAKE_LINE=EXE=%~f0"
>> "%PGCK_CAPTURE%" <nul set /p "=%PGCK_FAKE_LINE%"
>> "%PGCK_CAPTURE%" echo.

:fake_arg_loop
if "%~1"=="" goto fake_java_done
set /a PGCK_FAKE_INDEX+=1 >nul
set "PGCK_FAKE_LINE=ARG[%PGCK_FAKE_INDEX%]=%~1"
>> "%PGCK_CAPTURE%" <nul set /p "=%PGCK_FAKE_LINE%"
>> "%PGCK_CAPTURE%" echo.
shift
goto fake_arg_loop

:fake_java_done
exit /b 0
