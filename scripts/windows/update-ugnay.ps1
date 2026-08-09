[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'common.ps1')
$install = Read-UgnayInstall
$repository = [IO.Path]::GetFullPath($install.repositoryRoot)
if (-not (Test-Path -LiteralPath (Join-Path $repository '.git') -PathType Container)) { throw 'The recorded UGNAY checkout is not a Git repository.' }
Push-Location $repository
try {
    if (git status --porcelain) { throw 'Commit or discard local source changes before running the safe updater.' }
    $previousCommit = (git rev-parse HEAD).Trim()
    if (-not $previousCommit) { throw 'The current Git revision could not be identified.' }
} finally { Pop-Location }

$backup = & (Join-Path $PSScriptRoot 'backup-ugnay.ps1') -NoRestart
& (Join-Path $PSScriptRoot 'stop-ugnay.ps1')
$installSnapshot = Join-Path $script:RunRoot 'install.before-update.json'
Copy-Item -LiteralPath $script:InstallFile -Destination $installSnapshot -Force

try {
    Push-Location $repository
    try {
        git pull --ff-only
        if ($LASTEXITCODE -ne 0) { throw 'git pull --ff-only failed.' }
        & (Join-Path $repository 'scripts\windows\setup-lite.ps1') -DoNotStart
    } finally { Pop-Location }
    & (Join-Path $repository 'scripts\windows\start-ugnay.ps1') -NoBrowser
    Write-Host 'UGNAY update passed migration and health checks.' -ForegroundColor Green
} catch {
    $failure = $_.Exception.Message
    Write-Warning "Update failed; restoring revision $previousCommit and safety backup $backup."
    Push-Location $repository
    try { git reset --hard $previousCommit | Out-Null } finally { Pop-Location }
    Copy-Item -LiteralPath $installSnapshot -Destination $script:InstallFile -Force
    & (Join-Path $repository 'scripts\windows\restore-ugnay.ps1') -BackupPath $backup -Confirm RESTORE
    throw "Update was rolled back safely: $failure"
}
