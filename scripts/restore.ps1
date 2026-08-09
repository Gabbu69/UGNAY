[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,

    [Parameter(Mandatory = $true)]
    [ValidateSet('RESTORE-UGNAY')]
    [string]$ConfirmRestore,

    [switch]$DatabaseOnly,
    [switch]$SkipSafetyBackup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repositoryRoot 'compose.yaml'
$envFile = Join-Path $repositoryRoot '.env'
$backupDirectory = [System.IO.Path]::GetFullPath($BackupPath)
$databaseDump = Join-Path $backupDirectory 'database.sql'
$checksumFile = Join-Path $backupDirectory 'SHA256SUMS.txt'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI was not found on PATH.'
}
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Create .env from .env.example before running a restore.'
}
$composeArguments = @('compose', '--env-file', $envFile, '-f', $composeFile)
if (-not (Test-Path -LiteralPath $databaseDump -PathType Leaf)) {
    throw "Missing database.sql in $backupDirectory"
}
if (Test-Path -LiteralPath (Join-Path $backupDirectory '.incomplete')) {
    throw 'The selected backup is marked incomplete.'
}
if (-not $DatabaseOnly -and -not (Test-Path -LiteralPath (Join-Path $backupDirectory 'object-storage') -PathType Container)) {
    throw 'The backup has no object-storage directory. Use -DatabaseOnly only if broken document references are acceptable.'
}

if (Test-Path -LiteralPath $checksumFile) {
    Write-Host 'Verifying backup checksums...'
    foreach ($line in Get-Content -LiteralPath $checksumFile) {
        if ($line -notmatch '^([a-fA-F0-9]{64})  (.+)$') { throw "Invalid checksum line: $line" }
        $expectedHash = $Matches[1].ToLowerInvariant()
        $relativePath = $Matches[2].Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $candidate = [System.IO.Path]::GetFullPath((Join-Path $backupDirectory $relativePath))
        if (-not $candidate.StartsWith($backupDirectory + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Checksum entry escapes the backup directory: $relativePath"
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "Backup file is missing: $relativePath" }
        $actualHash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash) { throw "Checksum mismatch: $relativePath" }
    }
}
else {
    throw 'SHA256SUMS.txt is required before restoring.'
}

if (-not $SkipSafetyBackup) {
    Write-Host 'Creating a pre-restore safety backup...'
    & (Join-Path $PSScriptRoot 'backup.ps1') -Destination (Join-Path $repositoryRoot '.backups/pre-restore')
}

Write-Warning 'Replacing the active UGNAY database and object bucket with the selected backup.'
& docker @composeArguments stop app
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the application service.' }

$containerDump = '/tmp/ugnay-restore-{0}.sql' -f [guid]::NewGuid().ToString('N')
try {
    & docker @composeArguments cp $databaseDump "mysql:$containerDump"
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy database.sql into the MySQL container.' }

    $restoreCommand = 'set -eu; case "$MYSQL_DATABASE" in *[!A-Za-z0-9_]*) echo "Unsafe database name" >&2; exit 64;; esac; mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"; mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < ' + $containerDump + '; mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "DELETE FROM SPRING_SESSION;"'
    & docker @composeArguments exec -T mysql sh -c $restoreCommand
    if ($LASTEXITCODE -ne 0) { throw 'MySQL restore failed.' }

    if (-not $DatabaseOnly) {
        $backupRoot = Split-Path -Parent $backupDirectory
        $backupName = Split-Path -Leaf $backupDirectory
        $previousBackupPath = $env:UGNAY_BACKUP_PATH
        $previousOperation = $env:MINIO_OPERATION
        $previousBackupName = $env:MINIO_BACKUP_NAME
        try {
            $env:UGNAY_BACKUP_PATH = $backupRoot.Replace('\', '/')
            $env:MINIO_OPERATION = 'restore'
            $env:MINIO_BACKUP_NAME = $backupName
            & docker @composeArguments --profile tools run --rm minio-tools
            if ($LASTEXITCODE -ne 0) { throw 'MinIO restore failed.' }
        }
        finally {
            $env:UGNAY_BACKUP_PATH = $previousBackupPath
            $env:MINIO_OPERATION = $previousOperation
            $env:MINIO_BACKUP_NAME = $previousBackupName
        }
    }

    & docker @composeArguments up -d app
    if ($LASTEXITCODE -ne 0) { throw 'Data was restored, but the application did not restart.' }
    Write-Host 'Restore completed. Existing API sessions must authenticate again.'
}
finally {
    & docker @composeArguments exec -T mysql sh -c "rm -f $containerDump" 2>$null | Out-Null
}
