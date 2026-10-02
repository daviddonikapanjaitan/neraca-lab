@echo off
rem Neraca Lab full stack on Windows: postgres, redis, backend and frontend in Docker.
rem
rem   scripts\start-windows.bat           build, start and wait until ready (opens http://localhost:3000)
rem   scripts\stop-windows.bat            stop (database data is kept)
rem   scripts\start-windows.bat help      all commands and options
rem
rem Needs Docker Desktop for Windows (started automatically when installed but not running).
rem Double-clicking the file runs "start" and keeps the window open at the end.
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_NAME=%~nx0"
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "COMPOSE_FILE=%ROOT%\docker-compose.yaml"

if not defined FRONTEND_PORT set "FRONTEND_PORT=3000"
if not defined BACKEND_PORT set "BACKEND_PORT=8080"
if not defined POSTGRES_PORT set "POSTGRES_PORT=5432"
if not defined REDIS_PORT set "REDIS_PORT=6379"
if not defined WAIT_TIMEOUT set "WAIT_TIMEOUT=600"

rem Started by double-click (cmd /c "...start-windows.bat"): pause before the window closes.
set "PAUSE_AT_END="
set "CMDLINE=!CMDCMDLINE!"
if /i not "!CMDLINE:%SCRIPT_NAME%=!"=="!CMDLINE!" set "PAUSE_AT_END=1"
if defined NO_PAUSE set "PAUSE_AT_END="

set "COMMAND=%~1"
if "%COMMAND%"=="" set "COMMAND=start"
set "RC=0"

if /i "%COMMAND%"=="start"   goto do_start
if /i "%COMMAND%"=="stop"    goto do_stop
if /i "%COMMAND%"=="down"    goto do_stop
if /i "%COMMAND%"=="restart" goto do_restart
if /i "%COMMAND%"=="status"  goto do_status
if /i "%COMMAND%"=="ps"      goto do_status
if /i "%COMMAND%"=="logs"    goto do_logs
if /i "%COMMAND%"=="help"    goto do_help
if /i "%COMMAND%"=="-h"      goto do_help
if /i "%COMMAND%"=="--help"  goto do_help
call :usage
echo.
echo error Unknown command: %COMMAND%
set "RC=1"
goto finish

:do_start
call :cmd_start
set "RC=!ERRORLEVEL!"
goto finish

:do_stop
call :cmd_stop
set "RC=!ERRORLEVEL!"
goto finish

:do_restart
call :cmd_stop
if errorlevel 1 (
    set "RC=1"
    goto finish
)
call :cmd_start
set "RC=!ERRORLEVEL!"
goto finish

:do_status
call :check_docker
if errorlevel 1 (
    set "RC=1"
    goto finish
)
docker compose --project-directory "%ROOT%" -f "%COMPOSE_FILE%" ps -a
set "RC=!ERRORLEVEL!"
goto finish

:do_logs
call :check_docker
if errorlevel 1 (
    set "RC=1"
    goto finish
)
docker compose --project-directory "%ROOT%" -f "%COMPOSE_FILE%" logs -f --tail=200 %2 %3 %4
set "RC=!ERRORLEVEL!"
goto finish

:do_help
call :usage
goto finish

:finish
if defined PAUSE_AT_END (
    echo.
    pause
)
exit /b %RC%


rem ================================================================== commands

:cmd_start
echo ==^> Neraca Lab full stack (windows)
call :check_docker
if errorlevel 1 exit /b 1
call :check_env
call :check_ports
if errorlevel 1 exit /b 1
echo ==^> Building and starting containers (the first run downloads images and builds: several minutes)...
docker compose --project-directory "%ROOT%" -f "%COMPOSE_FILE%" up -d --build
if errorlevel 1 (
    echo warn docker compose up failed. Container status:
    docker compose --project-directory "%ROOT%" -f "%COMPOSE_FILE%" ps -a
    echo error Start failed. See the messages above, or run: %SCRIPT_NAME% logs
    exit /b 1
)
echo ==^> Waiting for the services to become ready...
call :wait_healthy neracalab-postgres Postgres
if errorlevel 1 exit /b 1
call :wait_healthy neracalab-redis Redis
if errorlevel 1 exit /b 1
call :wait_healthy neracalab-backend Backend
if errorlevel 1 exit /b 1
call :wait_healthy neracalab-frontend Frontend
if errorlevel 1 exit /b 1
echo.
echo Neraca Lab is running
echo   Frontend   http://localhost:%FRONTEND_PORT%
echo   Backend    http://localhost:%BACKEND_PORT%/api/v1/exchanges
echo   Postgres   localhost:%POSTGRES_PORT%  (db/user/password: neracalab)
echo.
echo   Logs: %SCRIPT_NAME% logs    Stop: stop-windows.bat
echo.
if not defined NO_BROWSER start "" "http://localhost:%FRONTEND_PORT%"
exit /b 0

:cmd_stop
rem Never starts Docker: when Docker is not running, nothing of the stack is running either.
where docker >nul 2>&1
if errorlevel 1 (
    echo error Docker is not installed. Install Docker Desktop: https://docs.docker.com/desktop/setup/install/windows-install/
    exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
    echo  ok Docker is not running, so Neraca Lab is not running either: nothing to stop
    exit /b 0
)
set "STACK_CONTAINERS="
for /f "delims=" %%c in ('docker compose --project-directory "%ROOT%" -f "%COMPOSE_FILE%" ps -a -q 2^>nul') do set "STACK_CONTAINERS=1"
if not defined STACK_CONTAINERS (
    echo  ok Neraca Lab is not running: nothing to stop
    exit /b 0
)
echo ==^> Stopping Neraca Lab (database data is kept)...
docker compose --project-directory "%ROOT%" -f "%COMPOSE_FILE%" down
if errorlevel 1 exit /b 1
echo  ok Stopped. Start again with: start-windows.bat
exit /b 0


rem ================================================================== checks

:check_docker
where docker >nul 2>&1
if errorlevel 1 (
    echo error Docker is not installed. Install Docker Desktop: https://docs.docker.com/desktop/setup/install/windows-install/
    exit /b 1
)
docker info >nul 2>&1
if not errorlevel 1 goto check_compose
set "DOCKER_DESKTOP=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" (
    echo error Docker is not running. Start Docker Desktop and run this script again.
    exit /b 1
)
echo ==^> Docker is not running; starting Docker Desktop...
start "" "%DOCKER_DESKTOP%"
set /a DOCKER_WAITED=0
:check_docker_wait
docker info >nul 2>&1
if not errorlevel 1 goto check_docker_started
if !DOCKER_WAITED! GEQ 180 (
    echo error Docker did not start within 180 s. Start Docker Desktop manually and run this script again.
    exit /b 1
)
call :sleep 3
set /a DOCKER_WAITED+=3
goto check_docker_wait
:check_docker_started
echo  ok Docker is running

:check_compose
set "COMPOSE_VERSION="
for /f "delims=" %%v in ('docker compose version --short 2^>nul') do set "COMPOSE_VERSION=%%v"
if not defined COMPOSE_VERSION (
    echo error Docker Compose v2 is missing. Update Docker Desktop.
    exit /b 1
)
if /i "!COMPOSE_VERSION:~0,1!"=="v" set "COMPOSE_VERSION=!COMPOSE_VERSION:~1!"
set "COMPOSE_MAJOR=0"
set "COMPOSE_MINOR=0"
for /f "tokens=1,2 delims=.-+" %%a in ("!COMPOSE_VERSION!") do (
    set "COMPOSE_MAJOR=%%a"
    set "COMPOSE_MINOR=%%b"
)
set /a COMPOSE_MAJOR_N=COMPOSE_MAJOR 2>nul
set /a COMPOSE_MINOR_N=COMPOSE_MINOR 2>nul
set "COMPOSE_TOO_OLD="
if !COMPOSE_MAJOR_N! LSS 2 set "COMPOSE_TOO_OLD=1"
if !COMPOSE_MAJOR_N! EQU 2 if !COMPOSE_MINOR_N! LSS 20 set "COMPOSE_TOO_OLD=1"
if defined COMPOSE_TOO_OLD (
    echo error Docker Compose !COMPOSE_VERSION! is too old; 2.20 or newer is required. Update Docker Desktop.
    exit /b 1
)
set "DOCKER_VERSION=?"
for /f "delims=" %%v in ('docker version --format "{{.Server.Version}}" 2^>nul') do set "DOCKER_VERSION=%%v"
echo  ok Docker !DOCKER_VERSION!, Compose !COMPOSE_VERSION!
exit /b 0

:check_env
if exist "%ROOT%\backend\.env" (
    echo  ok backend\.env found ^(AI settings^)
    exit /b 0
)
echo warn backend\.env not found: the app runs, but the AI upload needs OPENAI_API_KEY.
echo warn   copy backend\.env.example backend\.env   then set OPENAI_API_KEY and run: %SCRIPT_NAME% restart
exit /b 0

:check_ports
set "PORT_CONFLICT="
call :check_port frontend neracalab-frontend %FRONTEND_PORT% FRONTEND_PORT
call :check_port backend neracalab-backend %BACKEND_PORT% BACKEND_PORT
call :check_port postgres neracalab-postgres %POSTGRES_PORT% POSTGRES_PORT
call :check_port redis neracalab-redis %REDIS_PORT% REDIS_PORT
if defined PORT_CONFLICT (
    echo error The ports above are already used by another program.
    echo        Stop that program ^(e.g. a backend started from the IDE, or npm run dev^), or start with another port:
    echo        set "BACKEND_PORT=8081" ^& set "FRONTEND_PORT=3001" ^& %SCRIPT_NAME%
    exit /b 1
)
echo  ok Ports free: frontend %FRONTEND_PORT%, backend %BACKEND_PORT%, postgres %POSTGRES_PORT%, redis %REDIS_PORT%
exit /b 0

rem check_port SERVICE CONTAINER PORT VARIABLE
rem A port held by our own running container is fine (compose reuses it).
:check_port
set "CONTAINER_RUNNING="
for /f "delims=" %%r in ('docker inspect -f "{{.State.Running}}" %2 2^>nul') do set "CONTAINER_RUNNING=%%r"
if "!CONTAINER_RUNNING!"=="true" exit /b 0
netstat -ano | findstr /c:":%3 " | findstr /c:"LISTENING" >nul
if errorlevel 1 exit /b 0
echo error port %3 (%1) is in use - choose another with %4=^<port^>
set "PORT_CONFLICT=1"
exit /b 0


rem ================================================================== helpers

rem wait_healthy CONTAINER LABEL
:wait_healthy
set /a HEALTH_WAITED=0
:wait_healthy_loop
set "HEALTH_STATE=missing"
for /f "delims=" %%s in ('docker inspect -f "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}" %1 2^>nul') do set "HEALTH_STATE=%%s"
if "!HEALTH_STATE!"=="running healthy" (
    echo  ok %2 is ready
    exit /b 0
)
if "!HEALTH_STATE!"=="running unhealthy" goto wait_healthy_failed
if "!HEALTH_STATE!"=="missing" goto wait_healthy_failed
if "!HEALTH_STATE:~0,6!"=="exited" goto wait_healthy_failed
if "!HEALTH_STATE:~0,4!"=="dead" goto wait_healthy_failed
if !HEALTH_WAITED! GEQ %WAIT_TIMEOUT% (
    echo error %2 not ready after %WAIT_TIMEOUT% s: !HEALTH_STATE!
    exit /b 1
)
call :sleep 3
set /a HEALTH_WAITED+=3
goto wait_healthy_loop
:wait_healthy_failed
echo warn %2 is not healthy (!HEALTH_STATE!). Last log lines:
docker logs --tail 60 %1
echo error %2 failed to start. Full logs: %SCRIPT_NAME% logs
exit /b 1

rem sleep SECONDS - ping works without a console, unlike timeout
:sleep
set /a SLEEP_PINGS=%~1+1
ping -n %SLEEP_PINGS% 127.0.0.1 >nul
exit /b 0

:usage
echo Neraca Lab full stack in Docker (postgres, redis, backend, frontend).
echo.
echo Usage: %SCRIPT_NAME% [command]
echo.
echo Commands:
echo   start      build and start everything, wait until it is ready (default)
echo   stop       stop and remove the containers (database data is kept)
echo   restart    stop, then start
echo   status     show the containers and their health
echo   logs [svc] follow the logs (svc: frontend, backend, postgres, redis)
echo   help       show this help
echo.
echo Environment (optional, e.g. set "BACKEND_PORT=8081" before running):
echo   FRONTEND_PORT=%FRONTEND_PORT%  BACKEND_PORT=%BACKEND_PORT%  POSTGRES_PORT=%POSTGRES_PORT%  REDIS_PORT=%REDIS_PORT%
echo   NO_BROWSER=1   do not open the browser after start
echo   NO_PAUSE=1     do not wait for a key press at the end
echo   WAIT_TIMEOUT=%WAIT_TIMEOUT% seconds to wait for the containers to become healthy
echo.
echo AI upload needs backend\.env with OPENAI_API_KEY (copy backend\.env.example).
exit /b 0
