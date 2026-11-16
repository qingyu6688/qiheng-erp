#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$DestinationDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) '.local/tools/observability')
)
$ErrorActionPreference = 'Stop'
if (![OperatingSystem]::IsWindows()) { throw '本脚本安装 Windows 演练工具，Linux 使用部署文档中的容器镜像。' }
$destination = [IO.Path]::GetFullPath($DestinationDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$releases = @(
    @{ repository = 'open-telemetry/opentelemetry-java-instrumentation'; version = 'v2.33.1'; file = 'opentelemetry-javaagent.jar'; checksums = 'checksums.txt' },
    @{ repository = 'prometheus/prometheus'; version = 'v3.16.0'; file = 'prometheus-3.16.0.windows-amd64.zip'; checksums = 'sha256sums.txt' },
    @{ repository = 'prometheus/alertmanager'; version = 'v0.35.0'; file = 'alertmanager-0.35.0.windows-amd64.zip'; checksums = 'sha256sums.txt' }
)
foreach ($release in $releases) {
    $base = 'https://github.com/' + $release.repository + '/releases/download/' + $release.version + '/'
    $checksumPath = Join-Path $destination ($release.version + '-' + $release.checksums)
    Invoke-WebRequest -Uri ($base + $release.checksums) -OutFile $checksumPath
    $pattern = '^([0-9a-fA-F]{64})\s+\*?(?:\./)?' + [regex]::Escape($release.file) + '$'
    $matched = @(Get-Content -LiteralPath $checksumPath | Where-Object { $_ -match $pattern })
    if ($matched.Count -ne 1 -or $matched[0] -notmatch $pattern) { throw '官方校验文件未包含唯一的工具摘要。' }
    $expected = $matches[1]
    $archive = Join-Path $destination $release.file
    if (!(Test-Path -LiteralPath $archive -PathType Leaf)) {
        Invoke-WebRequest -Uri ($base + $release.file) -OutFile ($archive + '.download')
        if ((Get-FileHash -LiteralPath ($archive + '.download') -Algorithm SHA256).Hash -ne $expected) {
            throw '下载的监控工具摘要与官方记录不符。'
        }
        Move-Item -LiteralPath ($archive + '.download') -Destination $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw '已有工具摘要与官方记录不符。' }
    if ($release.file.EndsWith('.zip')) { Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force }
    Write-Output ('已校验 ' + $release.file)
}
