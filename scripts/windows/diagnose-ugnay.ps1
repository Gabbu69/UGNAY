[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')
$result = [ordered]@{
    checkedAt = [DateTime]::UtcNow.ToString('o')
    installed = Test-Path -LiteralPath $script:InstallFile
    port8080Free = Test-UgnayPortFree 8080
    port3307Free = Test-UgnayPortFree 3307
    health = 'NOT_RUNNING'
    processes = @()
    recentLogs = @()
}
try { $result.health = (Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3).status } catch { }
foreach ($name in @('app.pid', 'mysql.pid')) {
    $path = Join-Path $script:RunRoot $name
    if (Test-Path -LiteralPath $path) {
        $id = [int](Get-Content -LiteralPath $path)
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process) { $result.processes += [ordered]@{ name=$process.ProcessName; id=$id; workingSetMB=[math]::Round($process.WorkingSet64 / 1MB, 1) } }
    }
}
$result.recentLogs = Get-ChildItem $script:LogRoot -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 8 Name,Length,LastWriteTime
$result | ConvertTo-Json -Depth 5
