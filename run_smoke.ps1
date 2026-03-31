$env:HTTP_PROXY = ""
$env:HTTPS_PROXY = ""
$env:NO_PROXY = "localhost,127.0.0.1"
k6 run tests/k6/scenarios/smoke.js
