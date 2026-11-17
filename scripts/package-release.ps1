#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version='1.0.0',
    [ValidatePattern('^rc\.[1-9][0-9]*$')][string]$Candidate,
    [string]$OutputDirectory=(Join-Path (Split-Path -Parent $PSScriptRoot) '.local/release'),
    [switch]$IncludeAi,
    [switch]$IncludeMobile
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$pom=[xml][IO.File]::ReadAllText((Join-Path $root 'erp-server/pom.xml'))
$web=Get-Content (Join-Path $root 'erp-web/package.json') -Raw -Encoding utf8 | ConvertFrom-Json
$lock=Get-Content (Join-Path $root 'erp-web/package-lock.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
if($pom.project.version -ne $Version -or $web.version -ne $Version -or
   $lock.version -ne $Version -or $lock.packages[''].version -ne $Version){throw '前后端源码或锁文件版本与交付版本不一致。'}
$releaseLabel=$Version
$sourceCommit=$null
if($Candidate){
 $releaseLabel="$Version-$Candidate"
 $sourceCommit=git -C $root rev-parse --verify HEAD
 if($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40,64}$'){throw '无法确认候选包的源码提交。'}
 $pending=@(git --no-optional-locks -C $root status --porcelain=v1)
 if($LASTEXITCODE -ne 0 -or $pending.Count){throw '请提交候选源码和配置后再生成可追溯的候选包。'}
}
$jar=Join-Path $root "erp-server/target/erp-server-$Version.jar"
$aiJar=$null
if($IncludeAi){
 $aiPom=[xml][IO.File]::ReadAllText((Join-Path $root 'erp-ai/pom.xml'))
 if($aiPom.project.version -ne $Version){throw 'AI 源码版本与交付版本不一致。'}
 $aiJar=Join-Path $root "erp-ai/target/erp-ai-$Version.jar"
 if(!(Test-Path -LiteralPath $aiJar -PathType Leaf)){throw '缺少已构建的 AI 服务 JAR，请先构建 erp-ai 模块。'}
}
$dist=Join-Path $root 'erp-web/dist'
if(!(Test-Path -LiteralPath $jar -PathType Leaf) -or !(Test-Path -LiteralPath (Join-Path $dist 'index.html') -PathType Leaf)){throw '缺少已构建的生产JAR或前端dist，请先执行完整构建。'}
$mobileDist = Join-Path $root 'erp-mobile/dist/build/h5'
if($IncludeMobile){
 $mobile = Get-Content (Join-Path $root 'erp-mobile/package.json') -Raw -Encoding utf8 | ConvertFrom-Json
 if($mobile.version -ne $Version){throw '移动端源码版本与交付版本不一致。'}
 if(!(Test-Path -LiteralPath (Join-Path $mobileDist 'index.html') -PathType Leaf)){throw '缺少移动端 H5 生产构建。'}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($productionJar in @($jar,$aiJar) | Where-Object { $_ }){
 $archive=[IO.Compression.ZipFile]::OpenRead($productionJar)
 try{if(@($archive.Entries | Where-Object FullName -match 'BrowserFixture|BOOT-INF/classes/cn/erp/capacity/|test-classes|application-test|application-e2e').Count){throw '生产JAR混入测试配置或浏览器夹具，拒绝打包。'}}finally{$archive.Dispose()}
}
$output=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$zip=Join-Path $output "erp-$releaseLabel-local.zip"
if(Test-Path -LiteralPath $zip){throw '交付压缩包已存在，禁止覆盖；请指定新的输出目录。'}
$stage=Join-Path $output ("stage-$releaseLabel-"+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'erp-server.jar')
if($IncludeAi){Copy-Item -LiteralPath $aiJar -Destination (Join-Path $stage 'erp-ai.jar')}
Copy-Item -LiteralPath $dist -Destination (Join-Path $stage 'web') -Recurse
if($IncludeMobile){Copy-Item -LiteralPath $mobileDist -Destination (Join-Path $stage 'web/mobile') -Recurse}
New-Item -ItemType Directory -Path (Join-Path $stage 'deploy'),(Join-Path $stage 'scripts'),(Join-Path $stage 'docs') | Out-Null
foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'deploy') -File){if($file.Name -eq '.env' -or $file.Extension -in '.pem','.key','.crt'){throw '部署模板目录含私有配置或证书，拒绝打包。'};if($file.Name -ne '.gitignore'){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stage 'deploy')}}
$monitoringTemplates = Join-Path $root 'deploy/observability'
Copy-Item -LiteralPath $monitoringTemplates -Destination (Join-Path $stage 'deploy/observability') -Recurse
foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -Filter '*.ps1' -File){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stage 'scripts')}
Copy-Item -LiteralPath (Join-Path $root 'scripts/verify-observability.py') -Destination (Join-Path $stage 'scripts')
$javaDirectory=Join-Path $root 'scripts/java'
if(Test-Path -LiteralPath $javaDirectory -PathType Container){
 New-Item -ItemType Directory -Path (Join-Path $stage 'scripts/java') | Out-Null
 foreach($file in Get-ChildItem -LiteralPath $javaDirectory -Filter '*.java' -File){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stage 'scripts/java')}
}
$documents=@(
 '运维手册.md','部署说明.md','用户使用手册.md','测试报告.md','容量测试报告.md',
 '接口设计.md','数据库设计.md','开发计划.md','优化文档.md','项目总结.md','环境检查.md',
 '更新日志.md','上线准备清单.md','本机期初导入与业务试运行报告.md','多方式登录配置.md',
 '企业级演进规划.md','UI设计规范.md','可观测性设计.md','M14平台二期使用与验收.md',
 'M14单点登录与MFA使用说明.md','M14开放平台接入手册.md','M14工作流二期使用说明.md','2.0不兼容变更预告.md'
)
foreach($name in $documents) {
 Copy-Item -LiteralPath (Join-Path $root ('docs/'+$name)) -Destination (Join-Path $stage 'docs')
}
if($IncludeAi){
 foreach($name in 'M13人工智能使用与验收.md','第三方依赖说明.md','第三方依赖许可.md'){
  Copy-Item -LiteralPath (Join-Path $root ('docs/'+$name)) -Destination (Join-Path $stage 'docs')
 }
 $aiDesignTarget=Join-Path $stage 'docs/详细设计'
 New-Item -ItemType Directory -Path $aiDesignTarget | Out-Null
 Copy-Item -LiteralPath (Join-Path $root 'docs/详细设计/04-AI.md') -Destination $aiDesignTarget
}
if($IncludeMobile){
 $mobileDocs=Join-Path $stage 'erp-mobile'
 New-Item -ItemType Directory -Path $mobileDocs | Out-Null
 Copy-Item -LiteralPath (Join-Path $root 'erp-mobile/移动端使用说明.md') -Destination $mobileDocs
 $wmsDesignTarget=Join-Path $stage 'docs/详细设计'
 New-Item -ItemType Directory -Path $wmsDesignTarget -Force | Out-Null
 Copy-Item -LiteralPath (Join-Path $root 'docs/详细设计/08-WMS作业与移动端.md') -Destination $wmsDesignTarget
}
$files=@(Get-ChildItem -LiteralPath $stage -Recurse -File)
if(@($files | Where-Object { $_.Name -eq '.env' -or ([IO.Path]::GetRelativePath($stage,$_.FullName) -match '(^|[\\/])(?:\.local|test-results|node_modules|private)([\\/]|$)') }).Count){throw '交付内容混入本机配置、测试数据或私有目录。'}
$manifest=@{version=$Version;kind='本机验收交付包，正式生产上线另行验收';createdAt=[DateTime]::UtcNow.ToString('o');files=@($files | Sort-Object FullName | ForEach-Object {@{path=[IO.Path]::GetRelativePath($stage,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$_.Length}})}
if($Candidate){
 $manifest.candidate=$releaseLabel
 $manifest.sourceCommit=$sourceCommit
 $manifest.kind='本机验证候选包'
}
if($IncludeAi){$manifest.includesAi=$true}
if($IncludeMobile){$manifest.includesMobile=$true}
$manifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $stage 'manifest.json') -Encoding utf8NoBOM
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
$checksum=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($zip+'.sha256',"$checksum  $([IO.Path]::GetFileName($zip))`n",[Text.UTF8Encoding]::new($false))
Write-Output ('本机交付包和SHA-256清单已生成：'+$zip)
