[CmdletBinding()]
param([switch]$SkipTests)

$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$frontend = Join-Path $repository 'frontend'
$backend = Join-Path $repository 'backend'
$generated = Join-Path $backend 'target\generated-static'

Push-Location $frontend
try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) { npm ci }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed.' }
} finally { Pop-Location }

[void](New-Item -ItemType Directory -Force -Path $generated)
Copy-Item -Path (Join-Path $frontend 'dist\*') -Destination $generated -Recurse -Force

Push-Location $backend
try {
    if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to Java 21 for the release build.' }
    $arguments = @('-q', 'package')
    if ($SkipTests) { $arguments = @('-q', '-DskipTests', 'package') }
    & (Join-Path $backend 'mvnw.cmd') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend package failed.' }
} finally { Pop-Location }

$jar = Join-Path $backend 'target\ugnay-backend-0.2.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Expected release JAR was not produced.' }
Write-Host "Release JAR: $jar" -ForegroundColor Green
Get-FileHash -Algorithm SHA256 -LiteralPath $jar
