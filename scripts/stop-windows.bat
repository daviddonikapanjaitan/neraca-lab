@echo off
rem Stops Neraca Lab on Windows: removes the postgres, redis, backend and frontend containers
rem started by start-windows.bat. The database data is kept (Docker volume backend_postgres-data).
rem
rem   scripts\stop-windows.bat            stop
rem   scripts\start-windows.bat           start again
rem
rem Does nothing (and does not start Docker Desktop) when the stack or Docker is not running.
rem Double-clicking the file keeps the window open at the end.
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_NAME=%~nx0"

rem Started by double-click (cmd /c "...stop-windows.bat"): pause before the window closes.
set "PAUSE_AT_END="
set "CMDLINE=!CMDCMDLINE!"
if /i not "!CMDLINE:%SCRIPT_NAME%=!"=="!CMDLINE!" set "PAUSE_AT_END=1"
if defined NO_PAUSE set "PAUSE_AT_END="

set "RC=0"
if "%~1"=="" goto do_stop
if /i "%~1"=="help" goto do_help
if /i "%~1"=="-h" goto do_help
if /i "%~1"=="--help" goto do_help
echo error Unknown argument: %~1 - usage: %SCRIPT_NAME%, or %SCRIPT_NAME% help
set "RC=1"
goto finish

:do_stop
rem the stop logic lives in start-windows.bat; its own pause is disabled, this script pauses
set "NO_PAUSE=1"
call "%~dp0start-windows.bat" stop
set "RC=!ERRORLEVEL!"
goto finish

:do_help
echo Stops Neraca Lab: removes the postgres, redis, backend and frontend containers.
echo The database data is kept. Does nothing when the stack or Docker is not running.
echo.
echo Usage: %SCRIPT_NAME%
echo Start again: start-windows.bat
goto finish

:finish
if defined PAUSE_AT_END (
    echo.
    pause
)
exit /b %RC%
