[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$version='v2.4.0'
$release=Invoke-RestMethod "https://api.github.com/repos/grafana/k6/releases/tags/$version"
$asset=$release.assets | Where-Object name -eq "k6-$version-windows-amd64.zip"
if(!$asset -or $asset.digest -notmatch '^sha256:[a-f0-9]{64}$'){throw '官方发布缺少可核验的容量工具摘要'}
$directory=Join-Path $root '.local\tools\k6'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$archive=Join-Path $directory $asset.name
if(!(Test-Path -LiteralPath $archive)){Invoke-WebRequest $asset.browser_download_url -OutFile $archive}
$actual=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if(('sha256:'+$actual) -ne $asset.digest){throw 'k6 官方发布 SHA-256 校验失败'}
Expand-Archive -LiteralPath $archive -DestinationPath $directory -Force
$binary=Get-ChildItem -LiteralPath $directory -Recurse -Filter k6.exe | Select-Object -First 1
& $binary.FullName version
if($LASTEXITCODE -ne 0){throw '容量工具执行失败'}
