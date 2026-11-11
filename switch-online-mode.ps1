# Toggles server/server.properties between:
#   online  - normal: Microsoft accounts only (online-mode=true, enforce-secure-profile=true)
#   offline - bot testing: anyone can join with any name (online-mode=false, enforce-secure-profile=false)
#
# Usage:  .\switch-online-mode.ps1 offline   |   .\switch-online-mode.ps1 online   |   no argument = show current
# Restart the Minecraft server after switching.
param (
    [ValidateSet('online', 'offline', '')]
    [string]$Mode = ''
)

$props = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "server\server.properties"
if (-not (Test-Path $props)) {
    Write-Host "server\server.properties not found." -ForegroundColor Red
    exit 1
}

$content = Get-Content $props -Raw
$current = if ($content -match '(?m)^online-mode=(\w+)') { $matches[1] } else { 'unknown' }

if (-not $Mode) {
    $label = if ($current -eq 'true') { 'online (Microsoft accounts)' } else { 'OFFLINE (any name can join)' }
    Write-Host "Current: $label"
    Write-Host "Usage:   .\switch-online-mode.ps1 offline   or   .\switch-online-mode.ps1 online"
    exit 0
}

$value = if ($Mode -eq 'online') { 'true' } else { 'false' }
$content = $content -replace '(?m)^online-mode=\w+', "online-mode=$value"
$content = $content -replace '(?m)^enforce-secure-profile=\w+', "enforce-secure-profile=$value"
[System.IO.File]::WriteAllText($props, $content)

Write-Host ""
if ($Mode -eq 'offline') {
    Write-Host "  SERVER IS NOW OFFLINE MODE (for bot testing)" -ForegroundColor Yellow
    Write-Host "  - Anyone can join with ANY name. Do not expose this server outside your PC." -ForegroundColor Yellow
    Write-Host "  - Your own account gets a different UUID here, so it shows up as a NEW empty" -ForegroundColor Yellow
    Write-Host "    player (no items/COIN). Your real data is untouched; switch back to see it." -ForegroundColor Yellow
} else {
    Write-Host "  SERVER IS NOW ONLINE MODE (Microsoft accounts only)" -ForegroundColor Green
    Write-Host "  - Test bots can no longer join." -ForegroundColor Green
}
Write-Host "  Restart the Minecraft server for this to take effect."
Write-Host ""
