@echo off
setlocal enabledelayedexpansion
SET DIR=%~dp0
SET JARSEARCH=%DIR%\pgcodekeeper-cli-*
for /f "Tokens=1* Delims=" %%F in ('dir /b "%JARSEARCH%"') do set File=%%F

:argument_loop
IF NOT "%~1" == "" (
    IF "%~1" == "-vmargs" (
        set ISVM=y
    ) ELSE (
        IF DEFINED ISVM (
            set VMARG=!VMARG! "%~1"
        ) ELSE (
            set BASEARG=!BASEARG! "%~1"
        )
    )
    SHIFT
    GOTO argument_loop
)

SET JARFILE=%DIR%%File%
java %VMARG% -jar "%JARFILE%" %BASEARG%