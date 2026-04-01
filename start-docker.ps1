param(
    [switch]$NoOpen,
    [switch]$BuildImages
)

$ErrorActionPreference = "Stop"

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,

        [int]$TimeoutSeconds = 120,

        [string]$Name = $Url
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -Method Get -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                Write-Host "$Name is ready: $Url"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }

    throw "Timed out waiting for ${Name}: $Url"
}

Write-Host "Starting EmotionHub Docker services..."
if ($BuildImages) {
    docker compose up -d --build
} else {
    docker compose up -d
}

Write-Host "Waiting for backend health endpoint..."
Wait-HttpReady -Name "Backend" -Url "http://localhost:8081/api/test/hello"

Write-Host "Waiting for frontend page..."
Wait-HttpReady -Name "Frontend" -Url "http://localhost:3000"

if (-not $NoOpen) {
    Write-Host "Opening http://localhost:3000"
    Start-Process "http://localhost:3000"
}

Write-Host "EmotionHub is ready."
