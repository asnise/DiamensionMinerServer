$env:PORT = "3001"
$dir = $PSScriptRoot
Set-Location -LiteralPath $dir
while ($true) {
  try {
    & node "$dir\server.js" 1>> "$dir\server.log" 2>> "$dir\server.err.log"
  } catch {}
  Start-Sleep -Seconds 2
}