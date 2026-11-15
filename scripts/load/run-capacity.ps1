[CmdletBinding()]
param(
    [ValidateSet('server','run','inspect')][string]$Action='run',
    [string]$Database,
    [string]$SettingsFile,
    [string]$RunFile,
    [switch]$Resume,
    [ValidatePattern('^[a-z0-9]{1,16}$')][string]$Label='baseline',
    [ValidateSet('baseline','explore')][string]$Mode='baseline',
    [string[]]$Scenarios=@('query','write','hotspot','duplicate','refund','mixed','burst'),
    [ValidateSet(1,20,100)][int[]]$LineCounts=@(1,20,100),
    [switch]$HotPreconnect,
    [int]$SkuCount=20000,
    [string]$Duration='30s'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if(!$RunFile){$RunFile=Join-Path $root '.local\capacity-run.json'}
if($Action -eq 'server') {
    if($Database -notmatch '^erp_load_[a-z0-9]+(?:_[a-z0-9]+)*$'){throw '容量数据库必须使用独立 erp_load_ 前缀'}
    if((Test-Path -LiteralPath $RunFile) -and !$Resume){throw '运行文件已经存在，请明确指定Resume复用既有隔离数据'}
    if($Resume -and !(Test-Path -LiteralPath $RunFile)){throw '恢复容量进程必须提供既有运行文件'}
    $settings=@{}
    $files=@((Join-Path $root '.env'))
    if($SettingsFile){$files+=@($SettingsFile)}
    foreach($file in $files){
        if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw '容量数据库私有配置文件不存在'}
        foreach($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $file))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
    }

    if($settings['DB_HOST'] -notin @('127.0.0.1','localhost')){throw '容量验收仅允许本机 MySQL'}
    foreach($entry in $settings.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
    if($Resume){
        $state=Get-Content -LiteralPath $RunFile -Raw | ConvertFrom-Json
        if($state.database -ne $Database -or $state.runId -notmatch '^[a-f0-9]{12}$' -or $state.password.Length -lt 20 -or $state.key.Length -lt 24){throw '容量恢复文件与指定隔离数据库不匹配'}
    }else{
        $state=@{runId=[Guid]::NewGuid().ToString('N').Substring(0,12);database=$Database;password=[Guid]::NewGuid().ToString('N')+'L!';key=[Guid]::NewGuid().ToString('N');createdAt=[DateTime]::UtcNow.ToString('o')}
        [IO.File]::WriteAllText($RunFile,($state|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    }
    $env:ERP_LOAD_RESUME=if($Resume){'true'}else{'false'}
    $env:ERP_LOAD_RUN_ID=$state.runId;$env:ERP_LOAD_PASSWORD=$state.password;$env:ERP_LOAD_KEY=$state.key;$env:ERP_LOAD_SKUS=[string]$SkuCount
    $env:DB_NAME=$Database;$env:DB_USER=$settings['TEST_DB_USER'];$env:DB_PASSWORD=$settings['TEST_DB_PASSWORD']
    $env:STORAGE_ENDPOINT='';$env:STORAGE_BUCKET='';$env:STORAGE_ACCESS_KEY='';$env:STORAGE_SECRET_KEY=''
    $env:SPRING_PROFILES_ACTIVE='dev,load';$env:APP_ENV='load-'+$state.runId;$env:SERVER_PORT='19080';$env:ERP_NODE_ID='10';$env:ERP_DEV_ADMIN_PASSWORD=''
    $env:SPRING_SESSION_REDIS_NAMESPACE='erp:session:load:'+$state.runId
    $env:ERP_STORAGE_LOCAL_ROOT=Join-Path $root ('.local\load-objects-'+$state.runId)
    $env:ERP_WORKER_ENABLED='true';$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='30'
    $env:SERVER_TOMCAT_ACCEPT_COUNT='2000';$env:SERVER_TOMCAT_MAX_CONNECTIONS='8192';$env:SERVER_TOMCAT_THREADS_MAX='200'
    $env:Path="$env:JAVA_HOME\bin;$env:Path"
    Push-Location (Join-Path $root 'erp-server')
    try{mvn spring-boot:test-run '-Dspring-boot.run.main-class=cn.erp.ErpApplication';$status=$LASTEXITCODE}finally{Pop-Location}
    exit $status
}
$k6=Get-ChildItem -LiteralPath (Join-Path $root '.local\tools\k6') -Recurse -Filter k6.exe | Select-Object -First 1
if(!$k6){throw '请先执行 scripts/load/install-k6.ps1 准备经过摘要校验的容量工具'}
if(!(Test-Path -LiteralPath $RunFile)){throw '容量运行文件不存在，请先启动隔离容量服务'}
$state=Get-Content -LiteralPath $RunFile -Raw | ConvertFrom-Json
if($state.database -notmatch '^erp_load_[a-z0-9]+(?:_[a-z0-9]+)*$' -or $state.runId -notmatch '^[a-f0-9]{12}$'){throw '容量运行文件无效'}
$env:ERP_LOAD_RUN_ID=$state.runId;$env:ERP_LOAD_PASSWORD=$state.password;$env:ERP_LOAD_KEY=$state.key;$env:ERP_LOAD_URL='http://127.0.0.1:19080'
$env:ERP_LOAD_PHASE=$Label
$env:ERP_LOAD_HOT_PRECONNECT=if($HotPreconnect){'true'}else{'false'}
$output=Join-Path $root ('.local\capacity-results-'+$state.runId+'\'+$Label)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$failed=@()
try {
 foreach($scenario in $Scenarios) {
    if($scenario -notin @('query','write','hotspot','duplicate','refund','mixed','burst','recovery')){throw '容量场景无效'}
    $rates=if($Mode -eq 'explore' -and $scenario -eq 'query'){@(200,500,1000)}elseif($Mode -eq 'explore' -and $scenario -eq 'write'){@(20,50,100,200)}else{@($(if($scenario -in @('write','mixed','burst')){2}else{20}))}
    $lines=if($scenario -eq 'write'){$LineCounts}else{@(1)}
    foreach($rate in $rates){foreach($lineCount in $lines){
        $name=$scenario+'-r'+$rate+'-l'+$lineCount
        $env:SCENARIO=$scenario;$env:RATE=[string]$rate;$env:LINES=[string]$lineCount;$env:DURATION=$Duration;$env:SUMMARY_PATH=Join-Path $output ($name+'.json')
        if($Action -eq 'inspect'){& $k6.FullName inspect --include-system-env-vars (Join-Path $PSScriptRoot 'capacity.js');if($LASTEXITCODE -ne 0){throw '容量脚本检查失败'};continue}
        & $k6.FullName run --include-system-env-vars --no-color (Join-Path $PSScriptRoot 'capacity.js') *> (Join-Path $output ($name+'.log'))
        if($LASTEXITCODE -ne 0){$failed+=$name}
        [PSCustomObject]@{Scenario=$name;ExitCode=$LASTEXITCODE;Summary=$env:SUMMARY_PATH}
    }}
 }
} finally {Remove-Item Env:ERP_LOAD_PASSWORD,Env:ERP_LOAD_KEY -ErrorAction SilentlyContinue}
if($failed.Count){throw ('以下场景未达到阈值，须检查真实数据并记录，不能忽略正确性错误：'+($failed -join '、'))}
