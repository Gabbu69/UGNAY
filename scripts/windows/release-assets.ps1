function Assert-UgnayReleaseAssets {
    param(
        [Parameter(Mandatory)]$Manifest,
        [scriptblock]$Probe = { param($Uri) Invoke-WebRequest -UseBasicParsing -Method Head -Uri $Uri -TimeoutSec 20 | Out-Null }
    )
    $missing = @()
    foreach ($name in @('app', 'model', 'tokenizer')) {
        $asset = $Manifest.assets.$name
        if (-not $asset -or $asset.sha256 -notmatch '^[a-f0-9]{64}$' -or $asset.url -notmatch '^https://github.com/Gabbu69/UGNAY/releases/download/') {
            throw "Invalid release manifest entry: $name"
        }
        try { & $Probe $asset.url }
        catch { $missing += $asset.fileName }
    }
    if ($missing.Count -gt 0) {
        throw "UGNAY release $($Manifest.releaseVersion) is incomplete or unreachable. Missing: $($missing -join ', '). No runtime was installed. Use the documented source/demo setup until the matching release is published."
    }
}
