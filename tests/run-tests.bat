@echo off
setlocal enabledelayedexpansion

set "BASE_URL=http://127.0.0.1:8080/api"
set "K6_SCRIPT_DIR=%~dp0k6\scenarios"
set "RUN_ID=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%"
set "RUN_ID=!RUN_ID: =0!"

if "%~1"=="" (
    echo EmotionHub Test Suite
    echo Usage: run-tests.bat [test-type]
    echo.
    echo test-types: smoke  load  stress  endurance  spike  concurrency  all  dashboard  integration
    exit /b 0
)

set "TEST_TYPE=%~1"

REM Check if k6 is installed
where k6 >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [INFO] k6 not found in PATH. Trying Docker...
    set "K6_CMD=docker run --rm -i grafana/k6:latest run -"
    set "USING_DOCKER=1"
) else (
    set "K6_CMD=k6 run"
)

echo.
echo ============================================
echo EmotionHub Test - %TEST_TYPE%
echo BASE_URL: %BASE_URL%
echo ============================================
echo.

if "%TEST_TYPE%"=="smoke" (
    echo Running smoke test (3 VU, 30s)...
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\smoke.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\smoke.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    goto :end
)

if "%TEST_TYPE%"=="load" (
    echo Running load test (30 VU, 3.5min)...
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\load.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\load.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    goto :end
)

if "%TEST_TYPE%"=="stress" (
    echo Running stress test (10-120 VU, 10min)...
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\stress.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\stress.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    goto :end
)

if "%TEST_TYPE%"=="endurance" (
    echo Running endurance test (15 VU, 30min)...
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\endurance.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\endurance.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    goto :end
)

if "%TEST_TYPE%"=="spike" (
    echo Running spike test (10-200 VU, 4min)...
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\spike.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\spike.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    goto :end
)

if "%TEST_TYPE%"=="concurrency" (
    echo Running concurrency test (50 VU, 2min)...
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\concurrency.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\concurrency.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    goto :end
)

if "%TEST_TYPE%"=="all" (
    call :run_test smoke
    echo.
    call :run_test load
    echo.
    call :run_test stress
    echo.
    call :run_test spike
    echo.
    call :run_test concurrency
    goto :end
)

if "%TEST_TYPE%"=="dashboard" (
    echo Starting Grafana dashboard (InfluxDB + Grafana)...
    echo Access: http://localhost:3001  admin/admin
    docker compose -f "%~dp0k6\config\docker-compose.yml" up influxdb grafana -d
    goto :end
)

if "%TEST_TYPE%"=="integration" (
    echo Running Spring Boot integration tests...
    cd /d "%~dp0..\backend"
    call mvn test -Dtest=AuthControllerIntegrationTest,PostIntegrationTest,FeedAndStatsIntegrationTest -DfailIfNoTests=false
    goto :end
)

echo [ERROR] Unknown test type: %TEST_TYPE%
exit /b 1

:run_test
    if defined USING_DOCKER (
        type "%K6_SCRIPT_DIR%\%1.js" | docker run --rm -i -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID% grafana/k6:latest run -
    ) else (
        %K6_CMD% "%K6_SCRIPT_DIR%\%1.js" -e BASE_URL=%BASE_URL% -e K6_RUN_ID=%RUN_ID%
    )
    exit /b 0

:end
echo.
echo Done.
endlocal
