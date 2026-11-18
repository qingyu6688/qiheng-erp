#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$NginxDirectory,
    [string]$WebRoot,
    [string]$OpenSslPath='D:\Git\usr\bin\openssl.exe',
    [ValidateRange(1024,65535)][int]$HttpsPort=18443,
    [ValidateRange(1024,65535)][int]$HttpPort=18081,
    [ValidateRange(1024,65535)][int]$ApiPort=8080,
    [ValidateRange(0,65535)][int]$AiPort=0
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'test-storage.ps1')
$outputRoot=Get-ErpTestRoot @((Join-Path $root '.env'))
if (!$outputRoot) {
    $outputRoot=Join-Path $root '.local'
    if ($IsWindows) {
        try { Assert-ErpTestOutputPath $outputRoot }
        catch { throw '当前目录无法用作非 C 盘测试输出，请在环境变量或项目根 .env 中设置 ERP_TEST_ROOT 后重试。' }
    }
}
$runName='deployment-check-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), [Guid]::NewGuid().ToString('N')
$directory=Join-Path $outputRoot $runName
Assert-ErpTestOutputPath $directory
if([string]::IsNullOrWhiteSpace($WebRoot)){
 foreach($candidate in @((Join-Path $root 'web'),(Join-Path $root 'erp-web/dist'))){if(Test-Path -LiteralPath (Join-Path $candidate 'index.html') -PathType Leaf){$WebRoot=$candidate;break}}
}
if([string]::IsNullOrWhiteSpace($WebRoot) -or !(Test-Path -LiteralPath (Join-Path $WebRoot 'index.html') -PathType Leaf)){throw '找不到前端成品 index.html，请提供 -WebRoot 或先完成前端构建。'}
$WebRoot=(Resolve-Path -LiteralPath $WebRoot).Path
$NginxDirectory=(Resolve-Path -LiteralPath $NginxDirectory).Path
$nginx=(Get-Item -LiteralPath (Join-Path $NginxDirectory 'nginx.exe')).FullName
$openSslExe=(Get-Command -Name $OpenSslPath -CommandType Application -ErrorAction Stop).Source
if((Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $HttpsPort,$HttpPort)){throw '部署检查端口已占用，未停止任何已有服务。'}
New-Item -ItemType Directory -Path $directory | Out-Null
New-Item -ItemType Directory -Path (Join-Path $directory 'logs') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $directory 'temp') | Out-Null
# 证书、配置和日志均使用本次工作目录中的相对路径，避免 Windows 原生工具拆分或转码路径。
Push-Location -LiteralPath $directory
try {
 & $openSslExe req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout localhost-key.pem -out localhost.pem 2> (Join-Path $directory 'certificate.log')
 if($LASTEXITCODE -ne 0){throw '本机临时TLS证书生成失败。'}
 Copy-Item -LiteralPath (Join-Path $NginxDirectory 'conf/mime.types') -Destination (Join-Path $directory 'mime.types')
 $config=[IO.File]::ReadAllText((Join-Path $root 'deploy/nginx.conf'))
 $config=$config.Replace('user nginx;','').Replace('/var/log/nginx/error.log','logs/error.log').Replace('/var/log/nginx/access.log','logs/access.log').Replace('/var/run/nginx.pid','logs/nginx.pid').Replace('/etc/nginx/mime.types','mime.types')
 $config=$config.Replace('server api:8080;',"server 127.0.0.1:$ApiPort;").Replace('listen 80;',"listen 127.0.0.1:$HttpPort;").Replace('listen 443 ssl;',"listen 127.0.0.1:$HttpsPort ssl;").Replace('server_name erp.example.invalid;','server_name localhost;')
 $config=$config.Replace('/etc/nginx/tls/fullchain.pem','localhost.pem').Replace('/etc/nginx/tls/privkey.pem','localhost-key.pem').Replace('/usr/share/nginx/html',('"'+$WebRoot.Replace('\','/')+'"')).Replace('https://$host$request_uri',('https://$host:'+ $HttpsPort +'$request_uri'))
 [void](New-Item -ItemType Directory -Path (Join-Path $directory 'erp-ai-enabled'))
 $config=$config.Replace('/etc/nginx/erp-ai-enabled/*.conf','erp-ai-enabled/*.conf')
 if($AiPort){
  if($AiPort -lt 1024 -or $AiPort -in $ApiPort,$HttpPort,$HttpsPort){throw 'AI 服务端口必须为独立的回环应用端口。'}
  $aiConfig=[IO.File]::ReadAllText((Join-Path $root 'deploy/nginx-ai.conf')).Replace('erp-ai:18081',"127.0.0.1:$AiPort")
  [IO.File]::WriteAllText((Join-Path $directory 'erp-ai-enabled/ai.conf'),$aiConfig,[Text.UTF8Encoding]::new($false))
 }
 [IO.File]::WriteAllText((Join-Path $directory 'nginx.conf'),$config,[Text.UTF8Encoding]::new($false))
 & $nginx -p ./ -c nginx.conf -t 2> (Join-Path $directory 'syntax.log')
 if($LASTEXITCODE -ne 0){throw 'Nginx生产模板语法检查失败。'}
 $process=Start-Process -FilePath $nginx -ArgumentList @('-p','./','-c','nginx.conf') -WorkingDirectory $directory -WindowStyle Hidden -PassThru
 try {
  Start-Sleep -Milliseconds 500
  # curl 只信任本次生成的证书并校验回环主机名，不修改系统信任仓。
  $results=@()
  $aiStatus=if($AiPort){401}else{503}
  foreach($case in @(
   @("http://127.0.0.1:$HttpPort/login",308),
   @("https://127.0.0.1:$HttpsPort/login",200),
   @("https://127.0.0.1:$HttpsPort/actuator/health",404),
   @("https://127.0.0.1:$HttpsPort/private-objects/test",404),
   @("https://127.0.0.1:$HttpsPort/api/v1/companies",401),
   @("https://127.0.0.1:$HttpsPort/ws/v1/events",401),
   @("https://127.0.0.1:$HttpsPort/api/v1/ai-tools",404),
   @("https://127.0.0.1:$HttpsPort/api/v1/ai-tools/context",404),
   @("https://127.0.0.1:$HttpsPort/ai/wiki",200),
   @("https://127.0.0.1:$HttpsPort/ai/v1/assistants",$aiStatus)
  )){
   $status=& curl.exe --silent --show-error --cacert localhost.pem --header 'X-Company-Id: 1' --output NUL --write-out '%{http_code}' $case[0]
   if($LASTEXITCODE -ne 0 -or [int]$status -ne $case[1]){throw ('生产入口检查未通过：'+([uri]$case[0]).AbsolutePath)}
   $results+=@{path=([uri]$case[0]).AbsolutePath;scheme=([uri]$case[0]).Scheme;status=[int]$status}
  }
  @{completedAt=[DateTime]::UtcNow.ToString('o');certificate='仅本机临时自签名证书，不是正式生产证书';configSource='deploy/nginx.conf';checks=$results;result='passed'} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $directory 'deployment-result.json') -Encoding utf8NoBOM
  '生产Nginx模板语法、本机HTTPS入口、HTTP跳转、API代理和敏感路径隔离检查通过。'

 }finally{
  & $nginx -p ./ -c nginx.conf -s quit 2> (Join-Path $directory 'stop.log')
  if(!$process.HasExited){$process.WaitForExit(5000) | Out-Null}
  $process.Dispose()
 }
} finally {
 Pop-Location
}
