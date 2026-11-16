#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$NginxDirectory,
    [string]$WebRoot,
    [string]$OpenSslPath='D:\Git\usr\bin\openssl.exe',
    [ValidateRange(1024,65535)][int]$HttpsPort=18443,
    [ValidateRange(1024,65535)][int]$HttpPort=18081,
    [ValidateRange(1024,65535)][int]$ApiPort=8080
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if([string]::IsNullOrWhiteSpace($WebRoot)){
 foreach($candidate in @((Join-Path $root 'web'),(Join-Path $root 'erp-web/dist'))){if(Test-Path -LiteralPath (Join-Path $candidate 'index.html') -PathType Leaf){$WebRoot=$candidate;break}}
}
if([string]::IsNullOrWhiteSpace($WebRoot) -or !(Test-Path -LiteralPath (Join-Path $WebRoot 'index.html') -PathType Leaf)){throw '找不到前端成品 index.html，请提供 -WebRoot 或先完成前端构建。'}
$WebRoot=(Resolve-Path -LiteralPath $WebRoot).Path
$directory=Join-Path $root ('.local/deployment-check-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
if((Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in $HttpsPort,$HttpPort)){throw '部署检查端口已占用，未停止任何已有服务。'}
New-Item -ItemType Directory -Path $directory | Out-Null
New-Item -ItemType Directory -Path (Join-Path $directory 'logs') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $directory 'temp') | Out-Null
$nativeDirectory=$directory
if($directory -match '[^\x00-\x7F]'){
 $alias='F:\projects\erp';$link=Get-Item -LiteralPath $alias
 if($link.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath(@($link.Target)[0]).TrimEnd('\') -ne [IO.Path]::GetFullPath($root).TrimEnd('\')){throw '需要已核验的英文项目联接运行Windows Nginx。'}
 $nativeDirectory=Join-Path $alias ([IO.Path]::GetRelativePath($root,$directory))
}
$prefix=$nativeDirectory.Replace('\','/')+'/'
$certificate=Join-Path $nativeDirectory 'localhost.pem';$key=Join-Path $nativeDirectory 'localhost-key.pem'
& $OpenSslPath req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout $key -out $certificate 2> (Join-Path $directory 'certificate.log')
if($LASTEXITCODE -ne 0){throw '本机临时TLS证书生成失败。'}
Copy-Item -LiteralPath (Join-Path $NginxDirectory 'conf/mime.types') -Destination (Join-Path $directory 'mime.types')
$config=[IO.File]::ReadAllText((Join-Path $root 'deploy/nginx.conf'))
$config=$config.Replace('user nginx;','').Replace('/var/log/nginx/error.log','logs/error.log').Replace('/var/log/nginx/access.log','logs/access.log').Replace('/var/run/nginx.pid','logs/nginx.pid').Replace('/etc/nginx/mime.types','mime.types')
$config=$config.Replace('server api:8080;',"server 127.0.0.1:$ApiPort;").Replace('listen 80;',"listen 127.0.0.1:$HttpPort;").Replace('listen 443 ssl;',"listen 127.0.0.1:$HttpsPort ssl;").Replace('server_name erp.example.invalid;','server_name localhost;')
$config=$config.Replace('/etc/nginx/tls/fullchain.pem',$certificate.Replace('\','/')).Replace('/etc/nginx/tls/privkey.pem',$key.Replace('\','/')).Replace('/usr/share/nginx/html',('"'+$WebRoot.Replace('\','/')+'"')).Replace('https://$host$request_uri',('https://$host:'+ $HttpsPort +'$request_uri'))
[IO.File]::WriteAllText((Join-Path $directory 'nginx.conf'),$config,[Text.UTF8Encoding]::new($false))
$nginx=Join-Path $NginxDirectory 'nginx.exe'
& $nginx -p $prefix -c nginx.conf -t 2> (Join-Path $directory 'syntax.log')
if($LASTEXITCODE -ne 0){throw 'Nginx生产模板语法检查失败。'}
$process=Start-Process -FilePath $nginx -ArgumentList @('-p',('"'+$prefix+'"'),'-c','nginx.conf') -WorkingDirectory $nativeDirectory -WindowStyle Hidden -PassThru
try {
 Start-Sleep -Milliseconds 500
 # curl 只信任本次生成的证书并校验回环主机名，不修改系统信任仓。
 $results=@()
 foreach($case in @(@("http://127.0.0.1:$HttpPort/login",308),@("https://127.0.0.1:$HttpsPort/login",200),@("https://127.0.0.1:$HttpsPort/actuator/health",404),@("https://127.0.0.1:$HttpsPort/private-objects/test",404),@("https://127.0.0.1:$HttpsPort/api/v1/companies",401),@("https://127.0.0.1:$HttpsPort/ws/v1/events",401))){
  $status=& curl.exe --silent --show-error --cacert $certificate --output NUL --write-out '%{http_code}' $case[0]
  if($LASTEXITCODE -ne 0 -or [int]$status -ne $case[1]){throw ('生产入口检查未通过：'+([uri]$case[0]).AbsolutePath)}
  $results+=@{path=([uri]$case[0]).AbsolutePath;scheme=([uri]$case[0]).Scheme;status=[int]$status}
 }
 @{completedAt=[DateTime]::UtcNow.ToString('o');certificate='仅本机临时自签名证书，不是正式生产证书';configSource='deploy/nginx.conf';checks=$results;result='passed'} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $directory 'deployment-result.json') -Encoding utf8NoBOM
 '生产Nginx模板语法、本机HTTPS入口、HTTP跳转、API代理和敏感路径隔离检查通过。'

}finally{
 & $nginx -p $prefix -c nginx.conf -s quit 2> (Join-Path $directory 'stop.log')
 if(!$process.HasExited){$process.WaitForExit(5000) | Out-Null}
 $process.Dispose()
}
