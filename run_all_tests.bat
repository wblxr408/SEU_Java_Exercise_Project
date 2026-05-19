@echo off
setlocal
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "NO_PROXY=localhost,127.0.0.1"
set "K6_BIN=C:\Program Files\k6\k6.exe"
set "BASE_URL=http://localhost:8081/api"
set "K6_RUN_ID=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%"
set "K6_RUN_ID=%K6_RUN_ID: =0%"

echo ============================================
echo Running ALL tests: load, stress, spike, concurrency
echo BASE_URL: %BASE_URL%
echo ============================================

echo.
echo [1/4] Running LOAD test...
"%K6_BIN%" run --env BASE_URL=%BASE_URL% --env K6_RUN_ID=%K6_RUN_ID% tests\k6\scenarios\load.js
echo.

echo [2/4] Running STRESS test...
"%K6_BIN%" run --env BASE_URL=%BASE_URL% --env K6_RUN_ID=%K6_RUN_ID% tests\k6\scenarios\stress.js
echo.

echo [3/4] Running SPIKE test...
"%K6_BIN%" run --env BASE_URL=%BASE_URL% --env K6_RUN_ID=%K6_RUN_ID% tests\k6\scenarios\spike.js
echo.

echo [4/4] Running CONCURRENCY test...
"%K6_BIN%" run --env BASE_URL=%BASE_URL% --env K6_RUN_ID=%K6_RUN_ID% tests\k6\scenarios\concurrency.js
echo.

echo ============================================
echo ALL TESTS COMPLETE
echo ============================================
endlocal
