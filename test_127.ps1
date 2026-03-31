$body = '{"username":"test_direct","password":"Test123","email":"testdirect@test.com","nickname":"TestDirect"}'
$headers = @{'Content-Type'='application/json'}
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/auth/register' -Method POST -Body $body -Headers $headers
    Write-Host "Status:" $r.StatusCode
    Write-Host "Body:" $r.Content
} catch {
    Write-Host "Error Status:" $_.Exception.Response.StatusCode
    Write-Host "Error Body:" $_.Exception.GetResponseStream().ReadToEnd()
}
