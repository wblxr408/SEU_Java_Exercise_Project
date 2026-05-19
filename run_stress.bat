@echo off
setlocal
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "NO_PROXY=localhost,127.0.0.1"
set "K6_BIN=C:\Program Files\k6\k6.exe"
set "BASE_URL=http://localhost:8081/api"
set "K6_RUN_ID=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%"
set "K6_RUN_ID=%K6_RUN_ID: =0%"

echo Running STRESS test...
"%K6_BIN%" run --env BASE_URL=%BASE_URL% --env K6_RUN_ID=%K6_RUN_ID% tests\k6\scenarios\stress.js
echo.
endlocal
