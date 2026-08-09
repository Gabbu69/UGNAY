[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupPath,
    [Parameter(Mandatory)][ValidateSet('RESTORE')][string]$Confirm
)

. (Join-Path $PSScriptRoot 'common.ps1')
$target = [IO.Path]::GetFullPath($BackupPath)
if (-not (Test-Path -LiteralPath $target -PathType Container)) { throw 'The selected UGNAY backup folder does not exist.' }
$manifestPath = Join-Path $target 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'The selected folder has no UGNAY backup manifest.' }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
foreach ($file in $manifest.files) {
    $path = [IO.Path]::GetFullPath((Join-Path $target $file.path))
    if (-not $path.StartsWith($target, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Backup file is missing or escapes the backup root: $($file.path)"
    }
    if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256) {
        throw "Backup checksum failed: $($file.path)"
    }
}
$install = Read-UgnayInstall
$secrets = Read-UgnaySecrets
& (Join-Path $PSScriptRoot 'stop-ugnay.ps1')
[void](Start-UgnayMySql $install $secrets)
$safety = & (Join-Path $PSScriptRoot 'backup-ugnay.ps1') -NoRestart
$mysql = Join-Path $install.mysqlHome 'bin\mysql.exe'
$dump = Join-Path $target 'database.sql'
$previous = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $secrets.mysqlRoot
    & $mysql --protocol=TCP --host=127.0.0.1 --port=3307 --user=root --execute="DROP DATABASE IF EXISTS ugnay; CREATE DATABASE ugnay CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Could not recreate the UGNAY database.' }
    $process = Start-Process -FilePath $mysql -ArgumentList @('--protocol=TCP', '--host=127.0.0.1', '--port=3307', '--user=root', 'ugnay') `
        -Wait -PassThru -NoNewWindow -RedirectStandardInput $dump -RedirectStandardError (Join-Path $script:LogRoot 'restore.stderr.log')
    if ($process.ExitCode -ne 0) { throw "Database restore failed. Safety backup: $safety" }
} finally { $env:MYSQL_PWD = $previous }
$documentsSource = Join-Path $target 'documents'
$documentsParent = Split-Path -Parent $install.documentRoot
$previousDocuments = Join-Path $documentsParent ("documents.pre-restore-" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
if (Test-Path -LiteralPath $install.documentRoot) { Move-Item -LiteralPath $install.documentRoot -Destination $previousDocuments }
Copy-Item -LiteralPath $documentsSource -Destination $install.documentRoot -Recurse
& (Join-Path $PSScriptRoot 'start-ugnay.ps1')
Write-Host "Restore completed. Safety backup: $safety" -ForegroundColor Green
