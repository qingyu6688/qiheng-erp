#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
foreach ($module in 'erp-server', 'erp-ai') {
    mvn -B -f (Join-Path $root "$module/pom.xml") org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom `
        '-DincludeProvidedScope=false' '-DincludeTestScope=false' '-DincludeSystemScope=false' `
        '-DoutputFormat=json' "-DoutputDirectory=$output" "-DoutputName=$module.cdx"
    if ($LASTEXITCODE) { throw "无法生成 $module 的 Maven 运行依赖清单。" }
}
$frontends = @('erp-web', 'erp-mobile')
if (Test-Path -LiteralPath (Join-Path $root 'qiheng-website/package-lock.json')) {
    $frontends += 'qiheng-website'
}
foreach ($module in $frontends) {
    $bom = npm --prefix (Join-Path $root $module) sbom --sbom-format=cyclonedx --omit=dev --package-lock-only
    if ($LASTEXITCODE -or !$bom) { throw "无法生成 $module 的 npm 生产依赖清单。" }
    $bom | Set-Content -LiteralPath (Join-Path $output "$module.cdx.json") -Encoding utf8NoBOM
}
