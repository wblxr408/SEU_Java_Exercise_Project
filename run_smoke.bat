@echo off
setlocal
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "NO_PROXY=localhost,127.0.0.1"
set "K6_BIN=C:\Program Files\k6\k6.exe"
"%K6_BIN%" run tests\k6\scenarios\smoke.js
endlocal
