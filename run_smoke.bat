@echo off
setlocal
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "NO_PROXY=localhost,127.0.0.1"
k6 run tests\k6\scenarios\smoke.js
