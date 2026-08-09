[CmdletBinding()]
param([switch]$NoBrowser)

. (Join-Path $PSScriptRoot 'common.ps1')
Initialize-UgnayFolders
$install = Read-UgnayInstall
$secrets = Read-UgnaySecrets
$javaExe = Join-Path $install.javaHome 'bin\java.exe'
$pidFile = Join-Path $script:RunRoot 'app.pid'
if (Test-Path -LiteralPath $pidFile) {
    $existing = [int](Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue)
    if (Test-UgnayProcess $existing $javaExe $install.appJar) {
        if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:8080' }
        Write-Host 'UGNAY is already running.' -ForegroundColor Green
        return
    }
    Remove-Item -LiteralPath $pidFile -Force
}
if (-not (Test-UgnayPortFree 8080)) { throw 'Port 8080 is already in use by another process. UGNAY did not stop or replace it.' }
[void](Start-UgnayMySql $install $secrets)

$runtime = @{
    SPRING_PROFILES_ACTIVE = 'lite'
    MYSQL_HOST = '127.0.0.1'; MYSQL_PORT = '3307'; MYSQL_DATABASE = 'ugnay'; MYSQL_USER = 'ugnay'; MYSQL_PASSWORD = $secrets.mysqlApp
    UGNAY_DOCUMENT_ROOT = $install.documentRoot; UGNAY_LOG_FILE = (Join-Path $script:LogRoot 'ugnay.log')
    UGNAY_EMBEDDING_MODEL_PATH = $install.modelPath; UGNAY_EMBEDDING_MODEL_SHA256 = $install.modelSha256
    UGNAY_EMBEDDING_TOKENIZER_PATH = $install.tokenizerPath; UGNAY_EMBEDDING_TOKENIZER_SHA256 = $install.tokenizerSha256
    UGNAY_BOOTSTRAP_ADMIN_EMAIL = $install.adminEmail; UGNAY_BOOTSTRAP_ADMIN_PASSWORD = $secrets.bootstrapPassword
    UGNAY_SHUTDOWN_TOKEN = $secrets.shutdownToken; UGNAY_DATASET_MODE = $install.datasetMode
}
$previous = @{}
try {
    foreach ($entry in $runtime.GetEnumerator()) {
        $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    $jvm = @('-Xms96m', '-Xmx512m', '-Xss512k', '-XX:+UseSerialGC', '-XX:MaxMetaspaceSize=192m',
        '-XX:ReservedCodeCacheSize=96m', '-XX:+ExitOnOutOfMemoryError', '-Dfile.encoding=UTF-8', '-Duser.timezone=UTC',
        '-jar', $install.appJar)
    $process = Start-Process -FilePath $javaExe -ArgumentList $jvm -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $script:LogRoot 'app.stdout.log') `
        -RedirectStandardError (Join-Path $script:LogRoot 'app.stderr.log')
    Set-Content -LiteralPath $pidFile -Value $process.Id
} finally {
    foreach ($entry in $previous.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
}
try { Wait-UgnayHealth 90 }
catch {
    if (Test-UgnayProcess $process.Id $javaExe $install.appJar) { Stop-Process -Id $process.Id -Force }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    Stop-UgnayMySql $install $secrets
    throw
}
Write-Host 'UGNAY is ready at http://127.0.0.1:8080' -ForegroundColor Green
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:8080' }
