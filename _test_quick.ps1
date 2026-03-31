$body = '{"username":"alice_chen","password":"password123"}'
$hdrs = @{ 'Content-Type' = 'application/json' }
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method POST -Body $body -Headers $hdrs
    Write-Host "Status: Success"
    Write-Host "Response: $($resp | ConvertTo-Json -Depth 10)"
} catch {
    Write-Host "Status: Failed"
    Write-Host "StatusCode: $($_.Exception.Response.StatusCode)"
    Write-Host "Error: $($_.Exception.Message)"
}
