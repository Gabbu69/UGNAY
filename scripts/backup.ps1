[CmdletBinding()]
param(
    [string]$Destination,
    [switch]$SkipObjectStorage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repositoryRoot 'compose.yaml'
$envFile = Join-Path $repositoryRoot '.env'
if (-not $Destination) {
    $Destination = Join-Path $repositoryRoot '.backups'
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI was not found on PATH.'
}
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Create .env from .env.example before running a backup.'
}
$composeArguments = @('compose', '--env-file', $envFile, '-f', $composeFile)

& docker @composeArguments version | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose is unavailable.'
}

$backupRoot = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$backupName = 'ugnay-{0}' -f (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$backupDirectory = Join-Path $backupRoot $backupName
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
$incompleteMarker = Join-Path $backupDirectory '.incomplete'
New-Item -ItemType File -Path $incompleteMarker | Out-Null

$containerDump = '/tmp/ugnay-backup-{0}.sql' -f [guid]::NewGuid().ToString('N')
$dumpCommand = 'umask 077; mysqldump --single-transaction --no-tablespaces --routines --triggers --events --hex-blob --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" > ' + $containerDump
$runningApp = (& docker @composeArguments ps --status running -q app)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the application service.' }
$applicationWasRunning = -not [string]::IsNullOrWhiteSpace(($runningApp -join ''))

try {
    if ($applicationWasRunning) {
        Write-Host 'Briefly stopping application writes for a cross-store-consistent backup...'
        & docker @composeArguments stop app
        if ($LASTEXITCODE -ne 0) { throw 'Could not quiesce the application.' }
    }

    Write-Host 'Creating a transactionally consistent MySQL dump...'
    & docker @composeArguments exec -T mysql sh -c $dumpCommand
    if ($LASTEXITCODE -ne 0) { throw 'mysqldump failed.' }

    & docker @composeArguments cp "mysql:$containerDump" (Join-Path $backupDirectory 'database.sql')
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the MySQL dump from the container.' }

    if (-not $SkipObjectStorage) {
        Write-Host 'Mirroring the private MinIO bucket...'
        $previousBackupPath = $env:UGNAY_BACKUP_PATH
        $previousOperation = $env:MINIO_OPERATION
        $previousBackupName = $env:MINIO_BACKUP_NAME
        try {
            $env:UGNAY_BACKUP_PATH = $backupRoot.Replace('\', '/')
            $env:MINIO_OPERATION = 'backup'
            $env:MINIO_BACKUP_NAME = $backupName
            & docker @composeArguments --profile tools run --rm minio-tools
            if ($LASTEXITCODE -ne 0) { throw 'MinIO backup failed.' }
        }
        finally {
            $env:UGNAY_BACKUP_PATH = $previousBackupPath
            $env:MINIO_OPERATION = $previousOperation
            $env:MINIO_BACKUP_NAME = $previousBackupName
        }
    }

    $gitCommit = $null
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $gitCommit = (& git -C $repositoryRoot rev-parse HEAD 2>$null)
        if ($LASTEXITCODE -ne 0) { $gitCommit = $null }
    }

    $metadata = [ordered]@{
        formatVersion = 1
        createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        application = 'UGNAY'
        gitCommit = $gitCommit
        applicationQuiesced = $applicationWasRunning
        includesObjectStorage = (-not $SkipObjectStorage)
        restoreCommand = ".\scripts\restore.ps1 -BackupPath `"$backupDirectory`" -ConfirmRestore RESTORE-UGNAY"
    }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $backupDirectory 'manifest.json') -Encoding UTF8

    $checksumLines = Get-ChildItem -LiteralPath $backupDirectory -File -Recurse |
        Where-Object { $_.Name -notin @('SHA256SUMS.txt', '.incomplete') } |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($backupDirectory.Length + 1).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relative"
        }
    $checksumLines | Set-Content -LiteralPath (Join-Path $backupDirectory 'SHA256SUMS.txt') -Encoding ASCII

    Remove-Item -LiteralPath $incompleteMarker -Force
    Write-Host "Backup completed: $backupDirectory"
}
finally {
    & docker @composeArguments exec -T mysql sh -c "rm -f $containerDump" 2>$null | Out-Null
    if ($applicationWasRunning) {
        & docker @composeArguments up -d app | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Backup finished, but the application service could not be restarted.' }
    }
}
