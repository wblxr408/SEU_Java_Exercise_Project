$body = '{"username":"test_now","password":"Test123","email":"testnow@test.com","nickname":"TestNow"}'
$headers = @{'Content-Type'='application/json'}
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/auth/register' -Method POST -Body $body -Headers $headers
    Write-Host "Status:" $r.StatusCode
    Write-Host "Body:" $r.Content
} catch {
    $resp = $_.Exception.GetResponse()
    $reader = [System.IO.StreamReader]::new($resp.GetResponseStream()).ReadToEnd()
    Write-Host "Error Status:" $resp.StatusCode
    Write-Host "Error Body:" $reader
}
