Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:UgnayRoot = Join-Path $env:LOCALAPPDATA 'UGNAY'
$script:RuntimeRoot = Join-Path $script:UgnayRoot 'runtime'
$script:DataRoot = Join-Path $script:UgnayRoot 'data'
$script:LogRoot = Join-Path $script:UgnayRoot 'logs'
$script:RunRoot = Join-Path $script:UgnayRoot 'run'
$script:BackupRoot = Join-Path $script:UgnayRoot 'backups'
$script:SecretFile = Join-Path $script:UgnayRoot 'secrets.json'
$script:InstallFile = Join-Path $script:UgnayRoot 'install.json'

function Initialize-UgnayFolders {
    foreach ($path in @($script:UgnayRoot, $script:RuntimeRoot, $script:DataRoot, $script:LogRoot, $script:RunRoot, $script:BackupRoot)) {
        [void](New-Item -ItemType Directory -Force -Path $path)
    }
}

function Protect-UgnayFolders {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $script:UgnayRoot /inheritance:r /grant:r "${identity}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' /T /C | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'UGNAY could not apply user-only permissions to its local data folder.' }
}

function Protect-UgnayValue([Parameter(Mandatory)][string]$Value) {
    $plain = [Text.Encoding]::UTF8.GetBytes($Value)
    $protected = [Security.Cryptography.ProtectedData]::Protect(
        $plain, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    return [Convert]::ToBase64String($protected)
}

function Unprotect-UgnayValue([Parameter(Mandatory)][string]$Value) {
    $protected = [Convert]::FromBase64String($Value)
    $plain = [Security.Cryptography.ProtectedData]::Unprotect(
        $protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    return [Text.Encoding]::UTF8.GetString($plain)
}

function Read-UgnayInstall {
    if (-not (Test-Path -LiteralPath $script:InstallFile -PathType Leaf)) {
        throw "UGNAY is not installed for this Windows account. Run scripts\windows\setup-lite.ps1 first."
    }
    return Get-Content -Raw -LiteralPath $script:InstallFile | ConvertFrom-Json
}

function Read-UgnaySecrets {
    if (-not (Test-Path -LiteralPath $script:SecretFile -PathType Leaf)) {
        throw 'UGNAY credentials are missing. Rerun setup-lite.ps1 to repair the local installation.'
    }
    $encrypted = Get-Content -Raw -LiteralPath $script:SecretFile | ConvertFrom-Json
    return [pscustomobject]@{
        mysqlRoot = Unprotect-UgnayValue $encrypted.mysqlRoot
        mysqlApp = Unprotect-UgnayValue $encrypted.mysqlApp
        mysqlBackup = Unprotect-UgnayValue $encrypted.mysqlBackup
        bootstrapPassword = Unprotect-UgnayValue $encrypted.bootstrapPassword
        shutdownToken = Unprotect-UgnayValue $encrypted.shutdownToken
    }
}

function New-UgnaySecret([int]$Bytes = 32) {
    $buffer = New-Object byte[] $Bytes
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Test-UgnayPortFree([int]$Port) {
    return -not ([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port -contains $Port)
}

function Test-UgnayProcess([int]$ProcessId, [string]$ExpectedExecutable, [string]$CommandContains) {
    if ($ProcessId -le 0) { return $false }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $process) { return $false }
    $actual = [IO.Path]::GetFullPath([string]$process.ExecutablePath)
    $expected = [IO.Path]::GetFullPath($ExpectedExecutable)
    return $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase) -and
        ([string]$process.CommandLine).IndexOf($CommandContains, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Wait-UgnayHealth([int]$Seconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { Start-Sleep -Milliseconds 750 }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "UGNAY did not become healthy within $Seconds seconds. Review $script:LogRoot."
}

function Start-UgnayMySql($Install, $Secrets, [switch]$InitialInsecure) {
    $mysqlExe = Join-Path $Install.mysqlHome 'bin\mysqld.exe'
    $mysqlAdmin = Join-Path $Install.mysqlHome 'bin\mysqladmin.exe'
    $pidFile = Join-Path $script:RunRoot 'mysql.pid'
    if (Test-Path -LiteralPath $pidFile) {
        $existing = [int](Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue)
        if (Test-UgnayProcess $existing $mysqlExe '--defaults-file') { return $existing }
        Remove-Item -LiteralPath $pidFile -Force
    }
    if (-not (Test-UgnayPortFree 3307)) { throw 'Port 3307 is already in use by another process. UGNAY did not stop or replace it.' }
    $process = Start-Process -FilePath $mysqlExe -ArgumentList @("--defaults-file=$($Install.mysqlConfig)", '--console', '--no-monitor') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:LogRoot 'mysql.stdout.log') `
        -RedirectStandardError (Join-Path $script:LogRoot 'mysql.stderr.log')
    Set-Content -LiteralPath $pidFile -Value $process.Id
    $previous = $env:MYSQL_PWD
    try {
        if ($InitialInsecure) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        else { $env:MYSQL_PWD = $Secrets.mysqlRoot }
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        do {
            $arguments = @('--protocol=TCP', '--host=127.0.0.1', '--port=3307', '--user=root', 'ping', '--silent')
            if ($InitialInsecure) { $arguments = @('--protocol=TCP', '--host=127.0.0.1', '--port=3307', '--user=root', '--skip-password', 'ping', '--silent') }
            & $mysqlAdmin @arguments 2>$null
            if ($LASTEXITCODE -eq 0) { return $process.Id }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        throw 'Portable MySQL did not become ready within 45 seconds.'
    } finally { $env:MYSQL_PWD = $previous }
}

function Stop-UgnayMySql($Install, $Secrets) {
    $mysqlAdmin = Join-Path $Install.mysqlHome 'bin\mysqladmin.exe'
    $mysqlExe = Join-Path $Install.mysqlHome 'bin\mysqld.exe'
    $pidFile = Join-Path $script:RunRoot 'mysql.pid'
    $previous = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $Secrets.mysqlRoot
        & $mysqlAdmin --protocol=TCP --host=127.0.0.1 --port=3307 --user=root shutdown 2>$null
    } finally { $env:MYSQL_PWD = $previous }
    if (Test-Path -LiteralPath $pidFile) {
        $id = [int](Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue)
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while ((Get-Process -Id $id -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 300 }
        if (Test-UgnayProcess $id $mysqlExe '--defaults-file') { Stop-Process -Id $id -Force }
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
}
