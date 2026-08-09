[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')
$install = Read-UgnayInstall
$secrets = Read-UgnaySecrets
$javaExe = Join-Path $install.javaHome 'bin\java.exe'
$pidFile = Join-Path $script:RunRoot 'app.pid'
try {
    Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/system/shutdown' `
        -Headers @{ 'X-UGNAY-Shutdown' = $secrets.shutdownToken } -TimeoutSec 5 | Out-Null
} catch { }
if (Test-Path -LiteralPath $pidFile) {
    $id = [int](Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue)
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ((Get-Process -Id $id -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (Test-UgnayProcess $id $javaExe $install.appJar) { Stop-Process -Id $id -Force }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}
Stop-UgnayMySql $install $secrets
Write-Host 'UGNAY and its portable MySQL instance are stopped.' -ForegroundColor Green
