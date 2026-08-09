[CmdletBinding()]
param(
    [switch]$InstallFrontendDependencies,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$failures = @()
$passed = @()
$skipped = @()

function Find-Command {
    param([string[]]$Names)
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    return $null
}

function Invoke-Gate {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    Write-Host "`n==> $Name"
    try {
        & $Action
        $script:passed += $Name
        Write-Host "PASS: $Name" -ForegroundColor Green
    }
    catch {
        $script:failures += $Name
        Write-Host "FAIL: $Name - $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Skip-Gate {
    param([string]$Name, [string]$Reason)
    $script:skipped += $Name
    Write-Host "SKIP: $Name - $Reason" -ForegroundColor Yellow
}

Push-Location $repositoryRoot
try {
    Invoke-Gate 'PowerShell and infrastructure static checks' {
        $scripts = Get-ChildItem scripts -Recurse -Filter *.ps1 -File
        foreach ($script in $scripts) {
            $tokens = $null
            $parseErrors = $null
            [void][System.Management.Automation.Language.Parser]::ParseFile(
                $script.FullName,
                [ref]$tokens,
                [ref]$parseErrors
            )
            if ($parseErrors.Count -gt 0) {
                throw "$($script.Name): $($parseErrors -join '; ')"
            }
        }

        Get-ChildItem infra -Recurse -Filter *.json -File | ForEach-Object {
            Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json | Out-Null
        }

        $markdown = @((Get-Item README.md), (Get-Item AGENTS.md)) + @(Get-ChildItem docs -Filter *.md -File)
        $brokenLinks = @()
        foreach ($file in $markdown) {
            $content = Get-Content -Raw -LiteralPath $file.FullName
            foreach ($match in [regex]::Matches($content, '!?(?:\[[^\]]*\])\((?<target>[^)]+)\)')) {
                $target = $match.Groups['target'].Value.Trim().Trim('<', '>')
                if ($target -match '^(https?://|mailto:|#)') { continue }
                $pathPart = ($target -split '#', 2)[0]
                if ([string]::IsNullOrWhiteSpace($pathPart)) { continue }
                $decoded = [uri]::UnescapeDataString($pathPart)
                $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $decoded))
                if (-not (Test-Path -LiteralPath $resolved)) {
                    $brokenLinks += "$($file.FullName): $target"
                }
            }
        }
        if ($brokenLinks.Count -gt 0) {
            throw "Broken local Markdown links:`n$($brokenLinks -join "`n")"
        }

        $textFiles = @(
            Get-Item README.md, AGENTS.md, compose.yaml, Dockerfile, .env.example, .gitignore, .dockerignore
            Get-ChildItem docs -Filter *.md -File
            Get-ChildItem scripts -Recurse -Include *.ps1, *.cmd -File
            Get-ChildItem .github -Recurse -Include *.yml, *.yaml -File
            Get-ChildItem infra -Recurse -Include *.json, Caddyfile -File
        )
        $whitespace = @()
        foreach ($file in $textFiles) {
            $lineNumber = 0
            foreach ($line in Get-Content -LiteralPath $file.FullName) {
                $lineNumber++
                if ($line -match '[ \t]+$') { $whitespace += "$($file.FullName):$lineNumber" }
            }
        }
        if ($whitespace.Count -gt 0) {
            throw "Trailing whitespace:`n$($whitespace -join "`n")"
        }
    }

    $npx = Find-Command @('npx.cmd', 'npx')
    if ($npx) {
        Invoke-Gate 'Compose YAML syntax' {
            & $npx --yes yaml valid compose.yaml
            if ($LASTEXITCODE -ne 0) { throw "yaml exited with code $LASTEXITCODE" }
        }
        Invoke-Gate 'OpenAPI structural lint' {
            & $npx --yes '@redocly/cli' lint backend/src/main/resources/static/openapi.yaml `
                --format stylish `
                --skip-rule info-license `
                --skip-rule operation-operationId `
                --skip-rule operation-4xx-response `
                --skip-rule no-required-schema-properties-undefined
            if ($LASTEXITCODE -ne 0) { throw "Redocly exited with code $LASTEXITCODE" }
        }
    }
    else {
        Skip-Gate 'Compose YAML syntax' 'npx is not available.'
        Skip-Gate 'OpenAPI structural lint' 'npx is not available.'
    }

    if ($SkipFrontend) {
        Skip-Gate 'Frontend tests, lint, and build' 'Skipped by parameter.'
    }
    else {
        $npm = Find-Command @('npm.cmd', 'npm')
        if (-not $npm) {
            Skip-Gate 'Frontend tests, lint, and build' 'npm is not available.'
        }
        elseif (-not (Test-Path frontend/node_modules) -and -not $InstallFrontendDependencies) {
            Skip-Gate 'Frontend tests, lint, and build' 'frontend/node_modules is absent; rerun with -InstallFrontendDependencies.'
        }
        else {
            Invoke-Gate 'Frontend tests, lint, and build' {
                Push-Location frontend
                try {
                    if ($InstallFrontendDependencies) {
                        & $npm ci
                        if ($LASTEXITCODE -ne 0) { throw "npm ci exited with code $LASTEXITCODE" }
                    }
                    & $npm test
                    if ($LASTEXITCODE -ne 0) { throw "npm test exited with code $LASTEXITCODE" }
                    & $npm run lint
                    if ($LASTEXITCODE -ne 0) { throw "npm run lint exited with code $LASTEXITCODE" }
                    & $npm run build
                    if ($LASTEXITCODE -ne 0) { throw "npm run build exited with code $LASTEXITCODE" }
                }
                finally {
                    Pop-Location
                }
            }
        }
    }

    if ($SkipBackend) {
        Skip-Gate 'Backend Maven tests' 'Skipped by parameter.'
    }
    elseif (-not (Find-Command @('java.exe', 'java'))) {
        Skip-Gate 'Backend Maven tests' 'Java is not available; the Docker build remains the toolchain-supplied path.'
    }
    elseif (-not (Test-Path backend/mvnw.cmd -PathType Leaf)) {
        Skip-Gate 'Backend Maven tests' 'backend/mvnw.cmd is absent.'
    }
    else {
        Invoke-Gate 'Backend Maven tests' {
            Push-Location backend
            try {
                & .\mvnw.cmd test
                if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE" }
            }
            finally {
                Pop-Location
            }
        }
    }

    if ($SkipDocker) {
        Skip-Gate 'Docker Compose resolved configuration' 'Skipped by parameter.'
    }
    else {
        $docker = Find-Command @('docker.exe', 'docker')
        if (-not $docker) {
            Skip-Gate 'Docker Compose resolved configuration' 'Docker is not available on PATH.'
        }
        else {
            Invoke-Gate 'Docker Compose resolved configuration' {
                & $docker compose --env-file .env.example config --quiet
                if ($LASTEXITCODE -ne 0) { throw "docker compose config exited with code $LASTEXITCODE" }
            }
        }
    }
}
finally {
    Pop-Location
}

Write-Host "`nVerification summary: $($passed.Count) passed, $($skipped.Count) skipped, $($failures.Count) failed."
if ($skipped.Count -gt 0) { Write-Host "Skipped: $($skipped -join '; ')" -ForegroundColor Yellow }
if ($failures.Count -gt 0) {
    throw "Verification failed: $($failures -join '; ')"
}
