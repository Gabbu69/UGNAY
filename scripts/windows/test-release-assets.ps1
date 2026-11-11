$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-assets.ps1')
$manifestPath = Join-Path $PSScriptRoot '..\..\infra\windows-lite\runtime-manifest.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json

$script:releaseProbeCount = 0
Assert-UgnayReleaseAssets -Manifest $manifest -Probe {
    param($Uri)
    $script:releaseProbeCount += 1
}
if ($script:releaseProbeCount -ne 3) { throw 'The complete asset set must probe all three files.' }

$missingRejected = $false
try {
    Assert-UgnayReleaseAssets -Manifest $manifest -Probe {
        param($Uri)
        if ($Uri -like '*.onnx') { throw 'Mock asset unavailable.' }
    }
} catch {
    $missingRejected = $_.Exception.Message -like '*multilingual-e5-small-int8.onnx*No runtime was installed*'
}
if (-not $missingRejected) { throw 'A missing model must fail before runtime installation with an actionable message.' }

$invalidManifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$invalidManifest.assets.app.sha256 = 'not-a-checksum'
$invalidRejected = $false
try {
    Assert-UgnayReleaseAssets -Manifest $invalidManifest -Probe { throw 'Invalid metadata must not reach a network probe.' }
} catch {
    $invalidRejected = $_.Exception.Message -like 'Invalid release manifest entry: app'
}
if (-not $invalidRejected) { throw 'An invalid checksum must be rejected before probing.' }

Write-Output 'Release preflight: complete, missing, and malformed asset cases passed; no runtimes or network calls were used.'
