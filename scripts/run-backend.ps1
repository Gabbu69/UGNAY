[CmdletBinding()]
param(
    [string]$EnvFile,
    [string[]]$MavenArguments = @('spring-boot:run')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $repositoryRoot '.env' }
$envPath = [System.IO.Path]::GetFullPath($EnvFile)
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw 'Create .env from .env.example before starting the host-side backend.'
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java 21 was not found on PATH. Use the Docker Compose path or install a Java 21 JDK.'
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }
    $name = $Matches[1]
    $value = $Matches[2].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $settings[$name] = $value
}

$required = @('MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MINIO_APP_ACCESS_KEY', 'MINIO_APP_SECRET_KEY', 'MINIO_BUCKET')
foreach ($name in $required) {
    if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
        throw "Required value $name is missing from $envPath"
    }
}

$runtime = [ordered]@{
    SPRING_PROFILES_ACTIVE = 'prod'
    MYSQL_HOST = '127.0.0.1'
    MYSQL_PORT = if ($settings['MYSQL_PUBLISHED_PORT']) { $settings['MYSQL_PUBLISHED_PORT'] } else { '3307' }
    MYSQL_DATABASE = $settings['MYSQL_DATABASE']
    MYSQL_USER = $settings['MYSQL_USER']
    MYSQL_PASSWORD = $settings['MYSQL_PASSWORD']
    UGNAY_STORAGE_ENDPOINT = 'http://127.0.0.1:' + $(if ($settings['MINIO_API_PORT']) { $settings['MINIO_API_PORT'] } else { '9000' })
    UGNAY_STORAGE_ACCESS_KEY = $settings['MINIO_APP_ACCESS_KEY']
    UGNAY_STORAGE_SECRET_KEY = $settings['MINIO_APP_SECRET_KEY']
    UGNAY_STORAGE_BUCKET = $settings['MINIO_BUCKET']
    UGNAY_CLAMAV_HOST = '127.0.0.1'
    UGNAY_CLAMAV_PORT = if ($settings['CLAMAV_PUBLISHED_PORT']) { $settings['CLAMAV_PUBLISHED_PORT'] } else { '3310' }
    UGNAY_COOKIE_SECURE = 'false'
    CORS_ALLOWED_ORIGINS = 'http://localhost:5173'
}

foreach ($optional in @('UGNAY_BOOTSTRAP_ADMIN_EMAIL', 'UGNAY_BOOTSTRAP_ADMIN_PASSWORD', 'UGNAY_PUBLIC_DEMO_READ', 'UGNAY_MAX_UPLOAD_SIZE', 'UGNAY_TIKA_MAX_CHARACTERS', 'UGNAY_TIKA_TIMEOUT_SECONDS')) {
    if ($settings.ContainsKey($optional) -and -not [string]::IsNullOrWhiteSpace($settings[$optional])) {
        $runtime[$optional] = $settings[$optional]
    }
}

if ($settings['UGNAY_EMBEDDING_MODEL_SHA256'] -and $settings['UGNAY_EMBEDDING_TOKENIZER_SHA256']) {
    $runtime['UGNAY_EMBEDDING_MODEL_PATH'] = Join-Path $repositoryRoot 'infra/model/multilingual-e5-small/onnx/model.onnx'
    $runtime['UGNAY_EMBEDDING_MODEL_SHA256'] = $settings['UGNAY_EMBEDDING_MODEL_SHA256']
    $runtime['UGNAY_EMBEDDING_TOKENIZER_PATH'] = Join-Path $repositoryRoot 'infra/model/multilingual-e5-small/onnx/tokenizer.json'
    $runtime['UGNAY_EMBEDDING_TOKENIZER_SHA256'] = $settings['UGNAY_EMBEDDING_TOKENIZER_SHA256']
}

$previous = @{}
try {
    foreach ($entry in $runtime.GetEnumerator()) {
        $previous[$entry.Key] = [System.Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [System.Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    Push-Location (Join-Path $repositoryRoot 'backend')
    try {
        & .\mvnw.cmd @MavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE." }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($entry in $previous.GetEnumerator()) {
        [System.Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
