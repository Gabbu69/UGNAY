[CmdletBinding()]
param(
    [string]$Destination,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $repositoryRoot 'infra/model/multilingual-e5-small.manifest.json'
if (-not $Destination) {
    $Destination = Join-Path $repositoryRoot 'infra/model/multilingual-e5-small'
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$destinationRoot = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null

Write-Host "Provisioning $($manifest.repository) at pinned revision $($manifest.revision)"
Write-Host "Destination: $destinationRoot"

foreach ($file in $manifest.files) {
    $relativePath = [string]$file.path
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $destinationRoot $relativePath))
    if (-not $targetPath.StartsWith($destinationRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest path escapes the destination: $relativePath"
    }

    $targetDirectory = Split-Path -Parent $targetPath
    New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null

    if (Test-Path -LiteralPath $targetPath) {
        $existingHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existingHash -eq $file.sha256) {
            Write-Host "Verified existing $relativePath"
            continue
        }
        if (-not $Force) {
            throw "Existing file failed checksum validation: $relativePath. Re-run with -Force to replace it."
        }
    }

    $encodedPath = ($relativePath -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
    $downloadUri = "https://huggingface.co/$($manifest.repository)/resolve/$($manifest.revision)/$encodedPath"
    $partialPath = "$targetPath.partial-$([guid]::NewGuid().ToString('N'))"

    try {
        Write-Host "Downloading $relativePath ($([math]::Round($file.size / 1MB, 1)) MiB)..."
        Invoke-WebRequest -UseBasicParsing -Uri $downloadUri -OutFile $partialPath

        $downloaded = Get-Item -LiteralPath $partialPath
        if ($downloaded.Length -ne [long]$file.size) {
            throw "Size mismatch for $relativePath. Expected $($file.size), received $($downloaded.Length)."
        }

        $actualHash = (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $file.sha256) {
            throw "SHA-256 mismatch for $relativePath. Expected $($file.sha256), received $actualHash."
        }

        Move-Item -LiteralPath $partialPath -Destination $targetPath -Force
        Write-Host "Verified $relativePath"
    }
    finally {
        if (Test-Path -LiteralPath $partialPath) {
            Remove-Item -LiteralPath $partialPath -Force
        }
    }
}

Write-Host 'Local semantic model is ready. No research text is sent to Hugging Face at runtime.'
Write-Host 'Set these values in .env to enable checksum-gated semantic inference:'
Write-Host 'UGNAY_EMBEDDING_MODEL_PATH=/opt/ugnay/models/multilingual-e5-small/onnx/model.onnx'
Write-Host 'UGNAY_EMBEDDING_MODEL_SHA256=ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665'
Write-Host 'UGNAY_EMBEDDING_TOKENIZER_PATH=/opt/ugnay/models/multilingual-e5-small/onnx/tokenizer.json'
Write-Host 'UGNAY_EMBEDDING_TOKENIZER_SHA256=0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39'
