$hdrs = @{ "Content-Type" = "application/json" }
$json = '{"username":"alice_chen","password":"password123"}'
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method POST -Body $json -Headers $hdrs
    Write-Host "SUCCESS - Token:" $resp.data.token
} catch {
    Write-Host "FAIL - Status:" $_.Exception.Response.StatusCode
    Write-Host "Error:" $_.Exception.Message
}
