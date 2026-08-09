[CmdletBinding()]
param([switch]$NoRestart)

. (Join-Path $PSScriptRoot 'common.ps1')
$install = Read-UgnayInstall
$secrets = Read-UgnaySecrets
$javaExe = Join-Path $install.javaHome 'bin\java.exe'
$appPidFile = Join-Path $script:RunRoot 'app.pid'
$appWasRunning = $false
if (Test-Path -LiteralPath $appPidFile) {
    $appId = [int](Get-Content -LiteralPath $appPidFile -ErrorAction SilentlyContinue)
    $appWasRunning = Test-UgnayProcess $appId $javaExe $install.appJar
}
if ($appWasRunning) {
    try {
        Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/system/shutdown' `
            -Headers @{ 'X-UGNAY-Shutdown' = $secrets.shutdownToken } -TimeoutSec 5 | Out-Null
    } catch { }
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ((Get-Process -Id $appId -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (Test-UgnayProcess $appId $javaExe $install.appJar) { Stop-Process -Id $appId -Force }
    Remove-Item -LiteralPath $appPidFile -Force -ErrorAction SilentlyContinue
}
[void](Start-UgnayMySql $install $secrets)
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$target = Join-Path $script:BackupRoot $stamp
[void](New-Item -ItemType Directory -Force -Path $target)
$dump = Join-Path $target 'database.sql'
$dumpExe = Join-Path $install.mysqlHome 'bin\mysqldump.exe'
$previous = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $secrets.mysqlBackup
    $process = Start-Process -FilePath $dumpExe -ArgumentList @('--protocol=TCP', '--host=127.0.0.1', '--port=3307',
        '--user=ugnay_backup', '--single-transaction', '--no-tablespaces', '--routines', '--triggers', '--events',
        '--hex-blob', '--default-character-set=utf8mb4', 'ugnay') -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput $dump -RedirectStandardError (Join-Path $target 'mysqldump.stderr.log')
    if ($process.ExitCode -ne 0) { throw "mysqldump failed with exit code $($process.ExitCode)." }
} finally { $env:MYSQL_PWD = $previous }
$documents = Join-Path $target 'documents'
if (Test-Path -LiteralPath $install.documentRoot -PathType Container) {
    Copy-Item -LiteralPath $install.documentRoot -Destination $documents -Recurse
} else { [void](New-Item -ItemType Directory -Force -Path $documents) }
$files = Get-ChildItem $target -Recurse -File | Where-Object Name -ne 'manifest.json' | ForEach-Object {
    [ordered]@{ path = $_.FullName.Substring($target.Length + 1); size = $_.Length; sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
[ordered]@{ createdAt=[DateTime]::UtcNow.ToString('o'); releaseVersion=$install.releaseVersion; files=@($files) } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $target 'manifest.json') -Encoding UTF8
Write-Host "UGNAY backup created: $target" -ForegroundColor Green
if ($appWasRunning -and -not $NoRestart) { & (Join-Path $PSScriptRoot 'start-ugnay.ps1') -NoBrowser }
return $target
