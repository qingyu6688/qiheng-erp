param([string]$OutputDirectory = '.local/demo-1panel-20261120')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath((Join-Path $workspace $OutputDirectory))
if (-not $output.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw '输出目录必须位于项目目录内。'
}
$website = Join-Path $workspace 'qiheng-website/dist'
$demo = Join-Path $workspace 'erp-web/dist-demo'
foreach ($entry in @((Join-Path $website 'index.html'), (Join-Path $demo 'index.html'))) {
  if (-not (Test-Path -LiteralPath $entry -PathType Leaf)) { throw "尚未构建静态入口：$entry" }
}
$stage = Join-Path $output ('stage-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Get-ChildItem -LiteralPath $website -Force | Copy-Item -Destination $stage -Recurse
$demoTarget = Join-Path $stage 'experience'
New-Item -ItemType Directory -Path $demoTarget | Out-Null
Get-ChildItem -LiteralPath $demo -Force | Copy-Item -Destination $demoTarget -Recurse
Copy-Item -LiteralPath (Join-Path $workspace 'LICENSE') -Destination (Join-Path $demoTarget 'LICENSE.txt')
$manifest = [ordered]@{ version = '2.0.0-demo.3'; snapshotDate = '2026-11-20'; mode = 'readonly'; entry = '/experience/'; files = @() }
$manifest.files = @(Get-ChildItem -LiteralPath $stage -File -Recurse | ForEach-Object {
  [ordered]@{ path = [IO.Path]::GetRelativePath($stage, $_.FullName).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stage 'demo-manifest.json') -Encoding utf8NoBOM
$zip = Join-Path $output 'qiheng-readonly-demo-20261120.zip'
if (Test-Path -LiteralPath $zip) { throw '发布包已存在，请使用新的输出目录保留历史包。' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($zip))" | Set-Content -LiteralPath ($zip + '.sha256') -Encoding ascii
[pscustomobject]@{ Archive = $zip; SHA256 = $hash; Files = $manifest.files.Count }
