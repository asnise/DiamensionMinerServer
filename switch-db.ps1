param (
    [string]$Target
)

$rootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverConfig = Join-Path $rootDir "server\plugins\DimensionalMinerCore\config.yml"
$resourcesConfig = Join-Path $rootDir "DimensionalMinerCore\src\main\resources\config.yml"
$webEnvLocal = Join-Path $rootDir "web-console\.env.local"

# Determine current profile
$current = "cloud"
if (Test-Path $serverConfig) {
    $content = Get-Content $serverConfig -Raw
    if ($content -match 'profile:\s*"?(\w+)"?') {
        $current = $matches[1].ToLower()
    }
}

if (-not $Target) {
    # Toggle if no argument provided
    if ($current -eq "cloud") {
        $Target = "local"
    } else {
        $Target = "cloud"
    }
}

$Target = $Target.ToLower()
if ($Target -ne "local" -and $Target -ne "cloud") {
    Write-Host "Invalid target: '$Target'. Use 'local' or 'cloud'." -ForegroundColor Red
    exit 1
}

# Update Server config
if (Test-Path $serverConfig) {
    $c = Get-Content $serverConfig -Raw
    $c = $c -replace 'profile:\s*"?(local|cloud)"?', "profile: `"$Target`""
    Set-Content -Path $serverConfig -Value $c -NoNewline
}

# Update Resources config
if (Test-Path $resourcesConfig) {
    $c = Get-Content $resourcesConfig -Raw
    $c = $c -replace 'profile:\s*"?(local|cloud)"?', "profile: `"$Target`""
    Set-Content -Path $resourcesConfig -Value $c -NoNewline
}

# Update Web Console config
if (Test-Path $webEnvLocal) {
    $c = Get-Content $webEnvLocal -Raw
    $c = $c -replace 'DB_PROFILE=\w+', "DB_PROFILE=$Target"
    Set-Content -Path $webEnvLocal -Value $c -NoNewline
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
if ($Target -eq "cloud") {
    Write-Host "  DATABASE SWITCHED TO: [ CLOUD (Neon AWS Singapore) ]" -ForegroundColor Green
    Write-Host "  Host: ep-broad-dream-b32si870-pooler.c-4.ap-southeast-1.aws.neon.tech" -ForegroundColor Gray
    Write-Host "  SSL : Enabled (require)" -ForegroundColor Gray
} else {
    Write-Host "  DATABASE SWITCHED TO: [ LOCAL (localhost:5432) ]" -ForegroundColor Yellow
    Write-Host "  Host: localhost:5432" -ForegroundColor Gray
    Write-Host "  User: postgres" -ForegroundColor Gray
}
Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
Write-Host "  [OK] Minecraft Server Config : $Target" -ForegroundColor White
Write-Host "  [OK] Web Console Config      : $Target" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
