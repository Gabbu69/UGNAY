[CmdletBinding()]
param(
    [string]$AdminEmail = 'admin@ugnay.local',
    [string]$AdminPassword,
    [switch]$LoadDemoData,
    [switch]$DoNotStart
)

. (Join-Path $PSScriptRoot 'common.ps1')

function Get-RepositoryRoot { return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')) }
function Get-Asset($Manifest, [string]$Name) {
    $asset = $Manifest.assets.$Name
    if (-not $asset) { throw "Runtime manifest does not define the '$Name' asset." }
    return $asset
}
function Get-VerifiedDownload($Asset, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash.ToLowerInvariant() -eq $Asset.sha256.ToLowerInvariant()) { return }
        Remove-Item -LiteralPath $Destination -Force
    }
    Write-Host "Downloading $($Asset.name)..." -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing -Uri $Asset.url -OutFile $Destination
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash.ToLowerInvariant()
    if ($actual -ne $Asset.sha256.ToLowerInvariant()) {
        Remove-Item -LiteralPath $Destination -Force
        throw "Checksum verification failed for $($Asset.name). The downloaded file was removed."
    }
}
function Expand-VerifiedArchive($Asset, [string]$Archive, [string]$Destination) {
    if (Test-Path -LiteralPath (Join-Path $Destination '.complete') -PathType Leaf) { return }
    $staging = "$Destination.staging"
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    [void](New-Item -ItemType Directory -Force -Path $staging)
    Expand-Archive -LiteralPath $Archive -DestinationPath $staging -Force
    $content = if ($Asset.archiveRoot) { Join-Path $staging $Asset.archiveRoot } else { $staging }
    if (-not (Test-Path -LiteralPath $content -PathType Container)) { throw "Archive root '$($Asset.archiveRoot)' was not found." }
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    if ($Asset.archiveRoot) { Move-Item -LiteralPath $content -Destination $Destination } else { Move-Item -LiteralPath $staging -Destination $Destination }
    Set-Content -LiteralPath (Join-Path $Destination '.complete') -Value $Asset.sha256
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
}
function Assert-HostReady {
    if (-not [Environment]::Is64BitOperatingSystem -or [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'UGNAY Lite supports only 64-bit Windows 10 or 11.'
    }
    if ($PSVersionTable.PSVersion.Major -lt 5) { throw 'PowerShell 5.1 or newer is required.' }
    $computer = Get-CimInstance Win32_ComputerSystem
    if ([long]$computer.TotalPhysicalMemory -lt 3758096384) { throw 'At least 3.5 GB of detected physical RAM is required.' }
    $drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($script:UgnayRoot).TrimEnd('\').TrimEnd(':'))
    if ($drive.Free -lt 5GB) { throw 'At least 5 GB of free disk space is required for setup and safe updates.' }
    if (-not (Get-CimInstance Win32_PageFileUsage -ErrorAction SilentlyContinue)) {
        Write-Warning 'No active Windows page file was detected. Enable a system-managed page file before semantic discovery on a 4 GB laptop.'
    }
    foreach ($port in @(8080, 3307)) {
        if (-not (Test-UgnayPortFree $port)) { throw "Port $port is already in use. Stop the conflicting process and rerun setup." }
    }
}
function Test-VcRuntime {
    foreach ($path in @('HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64')) {
        if ((Get-ItemProperty -Path $path -Name Installed -ErrorAction SilentlyContinue).Installed -eq 1) { return $true }
    }
    return $false
}
function Install-VcRuntime($Manifest, [string]$DownloadRoot) {
    if (Test-VcRuntime) { return }
    $asset = Get-Asset $Manifest 'vcRuntime'
    $installer = Join-Path $DownloadRoot $asset.fileName
    Get-VerifiedDownload $asset $installer
    Write-Host 'Microsoft Visual C++ runtime is required by MySQL. Windows may show one UAC prompt.' -ForegroundColor Yellow
    $process = Start-Process -FilePath $installer -ArgumentList '/install', '/quiet', '/norestart' -Verb RunAs -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1638, 3010) -or -not (Test-VcRuntime)) { throw 'The required Microsoft Visual C++ runtime was not installed.' }
}
function Convert-Secure([Security.SecureString]$Secure) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

Assert-HostReady
Initialize-UgnayFolders
Protect-UgnayFolders
$repositoryRoot = Get-RepositoryRoot
$manifestPath = Join-Path $repositoryRoot 'infra\windows-lite\runtime-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'The tracked Windows runtime manifest is missing.' }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$downloadRoot = Join-Path $script:RuntimeRoot 'downloads'
[void](New-Item -ItemType Directory -Force -Path $downloadRoot)
Install-VcRuntime $manifest $downloadRoot

$javaAsset = Get-Asset $manifest 'java'
$mysqlAsset = Get-Asset $manifest 'mysql'
$appAsset = Get-Asset $manifest 'app'
$modelAsset = Get-Asset $manifest 'model'
$tokenizerAsset = Get-Asset $manifest 'tokenizer'
foreach ($asset in @($javaAsset, $mysqlAsset, $appAsset, $modelAsset, $tokenizerAsset)) {
    Get-VerifiedDownload $asset (Join-Path $downloadRoot $asset.fileName)
}

$javaHome = Join-Path $script:RuntimeRoot "java-$($javaAsset.version)"
$mysqlHome = Join-Path $script:RuntimeRoot "mysql-$($mysqlAsset.version)"
Expand-VerifiedArchive $javaAsset (Join-Path $downloadRoot $javaAsset.fileName) $javaHome
Expand-VerifiedArchive $mysqlAsset (Join-Path $downloadRoot $mysqlAsset.fileName) $mysqlHome
$modelHome = Join-Path $script:RuntimeRoot "model-$($modelAsset.version)"
Expand-VerifiedArchive $tokenizerAsset (Join-Path $downloadRoot $tokenizerAsset.fileName) $modelHome
Copy-Item -LiteralPath (Join-Path $downloadRoot $modelAsset.fileName) -Destination (Join-Path $modelHome 'model.onnx') -Force
$appHome = Join-Path $script:RuntimeRoot "app-$($manifest.releaseVersion)"
[void](New-Item -ItemType Directory -Force -Path $appHome)
$appJar = Join-Path $appHome 'ugnay.jar'
Copy-Item -LiteralPath (Join-Path $downloadRoot $appAsset.fileName) -Destination $appJar -Force

$newInstall = -not (Test-Path -LiteralPath $script:SecretFile -PathType Leaf)
$datasetMode = 'EMPTY'
if ($newInstall) {
    if (-not $AdminPassword) {
        $AdminPassword = Convert-Secure (Read-Host 'Create the UGNAY administrator password (12-128 characters)' -AsSecureString)
    }
    if ($AdminPassword.Length -lt 12 -or $AdminPassword.Length -gt 128) { throw 'Administrator password must contain 12 to 128 characters.' }
    if ($AdminEmail -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') { throw 'Administrator email is invalid.' }
    $secrets = [ordered]@{
        mysqlRoot = Protect-UgnayValue (New-UgnaySecret)
        mysqlApp = Protect-UgnayValue (New-UgnaySecret)
        mysqlBackup = Protect-UgnayValue (New-UgnaySecret)
        bootstrapPassword = Protect-UgnayValue $AdminPassword
        shutdownToken = Protect-UgnayValue (New-UgnaySecret)
    }
    $secrets | ConvertTo-Json | Set-Content -LiteralPath $script:SecretFile -Encoding UTF8
    if (-not $LoadDemoData) {
        $choice = Read-Host 'Load the clearly labelled synthetic professor-demo dataset? [y/N]'
        $LoadDemoData = $choice -match '^(y|yes)$'
    }
    if ($LoadDemoData) { $datasetMode = 'SYNTHETIC_DEMO' }
} elseif (Test-Path -LiteralPath $script:InstallFile -PathType Leaf) {
    $previousInstall = Read-UgnayInstall
    $AdminEmail = $previousInstall.adminEmail
    $datasetMode = if ($previousInstall.datasetMode) { $previousInstall.datasetMode } else { 'EMPTY' }
}
$plain = Read-UgnaySecrets
$mysqlData = Join-Path $script:DataRoot 'mysql'
$documentRoot = Join-Path $script:DataRoot 'documents'
[void](New-Item -ItemType Directory -Force -Path $documentRoot)
$mysqlConfig = Join-Path $script:UgnayRoot 'my.ini'
$basedir = $mysqlHome.Replace('\', '/')
$datadir = $mysqlData.Replace('\', '/')
$logdir = $script:LogRoot.Replace('\', '/')
@"
[mysqld]
basedir=$basedir
datadir=$datadir
port=3307
bind-address=127.0.0.1
mysqlx=OFF
performance_schema=OFF
max_connections=10
innodb_buffer_pool_size=128M
innodb_log_buffer_size=8M
temptable_max_ram=32M
tmp_table_size=16M
max_heap_table_size=16M
table_open_cache=256
thread_cache_size=4
local_infile=OFF
skip-name-resolve=ON
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
log-error=$logdir/mysql-error.log

[client]
port=3307
host=127.0.0.1
default-character-set=utf8mb4
"@ | Set-Content -LiteralPath $mysqlConfig -Encoding ASCII

$install = [ordered]@{
    releaseVersion = $manifest.releaseVersion
    installedAt = [DateTime]::UtcNow.ToString('o')
    repositoryRoot = $repositoryRoot
    javaHome = $javaHome
    mysqlHome = $mysqlHome
    mysqlConfig = $mysqlConfig
    appJar = $appJar
    modelPath = (Join-Path $modelHome 'model.onnx')
    tokenizerPath = (Join-Path $modelHome 'tokenizer.json')
    modelSha256 = $modelAsset.sha256
    tokenizerSha256 = $tokenizerAsset.tokenizerSha256
    adminEmail = $AdminEmail.ToLowerInvariant()
    documentRoot = $documentRoot
    datasetMode = $datasetMode
}
$install | ConvertTo-Json | Set-Content -LiteralPath $script:InstallFile -Encoding UTF8

if (-not (Test-Path -LiteralPath $mysqlData -PathType Container)) {
    & (Join-Path $mysqlHome 'bin\mysqld.exe') "--defaults-file=$mysqlConfig" --initialize-insecure --console
    if ($LASTEXITCODE -ne 0) { throw 'MySQL data-directory initialization failed.' }
    [void](Start-UgnayMySql ([pscustomobject]$install) $plain -InitialInsecure)
    $mysql = Join-Path $mysqlHome 'bin\mysql.exe'
    $root = $plain.mysqlRoot.Replace("'", "''")
    $app = $plain.mysqlApp.Replace("'", "''")
    $backup = $plain.mysqlBackup.Replace("'", "''")
    $sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$root'; CREATE DATABASE IF NOT EXISTS ugnay CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS 'ugnay'@'127.0.0.1' IDENTIFIED BY '$app'; CREATE USER IF NOT EXISTS 'ugnay_backup'@'127.0.0.1' IDENTIFIED BY '$backup'; GRANT ALL PRIVILEGES ON ugnay.* TO 'ugnay'@'127.0.0.1'; GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON ugnay.* TO 'ugnay_backup'@'127.0.0.1'; FLUSH PRIVILEGES;"
    & $mysql --protocol=TCP --host=127.0.0.1 --port=3307 --user=root --skip-password --execute=$sql
    if ($LASTEXITCODE -ne 0) { throw 'MySQL account initialization failed.' }
    Stop-UgnayMySql ([pscustomobject]$install) $plain
}

Write-Host "UGNAY $($manifest.releaseVersion) is installed at $script:UgnayRoot" -ForegroundColor Green
if (-not $DoNotStart) { & (Join-Path $PSScriptRoot 'start-ugnay.ps1') }
