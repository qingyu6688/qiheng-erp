#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version='0.8.0',
    [string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) '.local/release')
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$pom=[xml][IO.File]::ReadAllText((Join-Path $root 'erp-server/pom.xml'))
$web=Get-Content (Join-Path $root 'erp-web/package.json') -Raw -Encoding utf8 | ConvertFrom-Json
if($pom.project.version -ne $Version -or $web.version -ne $Version){throw '前后端源码版本与交付版本不一致。'}
$jar=Join-Path $root "erp-server/target/erp-server-$Version.jar"
$dist=Join-Path $root 'erp-web/dist'
if(!(Test-Path -LiteralPath $jar -PathType Leaf) -or !(Test-Path -LiteralPath (Join-Path $dist 'index.html') -PathType Leaf)){throw '缺少已构建的生产JAR或前端dist，请先执行完整构建。'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try{if(@($archive.Entries | Where-Object FullName -match 'BrowserFixture|BOOT-INF/classes/cn/erp/capacity/|test-classes|application-test|application-e2e').Count){throw '生产JAR混入测试配置或浏览器夹具，拒绝打包。'}}finally{$archive.Dispose()}
$output=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$zip=Join-Path $output "erp-$Version-local.zip"
if(Test-Path -LiteralPath $zip){throw '交付压缩包已存在，禁止覆盖；请指定新的输出目录。'}
$stage=Join-Path $output ("stage-$Version-"+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'erp-server.jar')
Copy-Item -LiteralPath $dist -Destination (Join-Path $stage 'web') -Recurse
New-Item -ItemType Directory -Path (Join-Path $stage 'deploy'),(Join-Path $stage 'scripts'),(Join-Path $stage 'docs') | Out-Null
foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'deploy') -File){if($file.Name -eq '.env' -or $file.Extension -in '.pem','.key','.crt'){throw '部署模板目录含私有配置或证书，拒绝打包。'};if($file.Name -ne '.gitignore'){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stage 'deploy')}}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -Filter '*.ps1' -File){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stage 'scripts')}
$javaDirectory=Join-Path $root 'scripts/java'
if(Test-Path -LiteralPath $javaDirectory -PathType Container){
 New-Item -ItemType Directory -Path (Join-Path $stage 'scripts/java') | Out-Null
 foreach($file in Get-ChildItem -LiteralPath $javaDirectory -Filter '*.java' -File){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stage 'scripts/java')}
}
foreach($name in '运维手册.md','部署说明.md','用户使用手册.md','测试报告.md','容量测试报告.md','接口设计.md','数据库设计.md','开发计划.md','优化文档.md','项目总结.md','环境检查.md','更新日志.md') {Copy-Item -LiteralPath (Join-Path $root ('docs/'+$name)) -Destination (Join-Path $stage 'docs')}
$files=@(Get-ChildItem -LiteralPath $stage -Recurse -File)
if(@($files | Where-Object { $_.Name -eq '.env' -or ([IO.Path]::GetRelativePath($stage,$_.FullName) -match '(^|[\\/])(?:\.local|test-results|node_modules|private)([\\/]|$)') }).Count){throw '交付内容混入本机配置、测试数据或私有目录。'}
$manifest=@{version=$Version;kind='本机验收交付包，正式生产上线另行验收';createdAt=[DateTime]::UtcNow.ToString('o');files=@($files | Sort-Object FullName | ForEach-Object {@{path=[IO.Path]::GetRelativePath($stage,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$_.Length}})}
$manifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $stage 'manifest.json') -Encoding utf8NoBOM
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
$checksum=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($zip+'.sha256',"$checksum  $([IO.Path]::GetFileName($zip))`n",[Text.UTF8Encoding]::new($false))
Write-Output ('本机交付包和SHA-256清单已生成：'+$zip)
