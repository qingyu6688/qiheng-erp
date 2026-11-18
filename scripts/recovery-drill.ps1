#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$SourceSettingsFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [string]$SourceObjectRoot = (Join-Path $env:USERPROFILE '.erp\private-objects'),
    [ValidateRange(1024,65535)][int]$Port = 13308,
    [switch]$KeepInstance,
    [string]$MySqlDirectory = 'D:\mysql-8.0.46-winx64\bin',
    [string]$WorkDirectory
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'test-storage.ps1')
$testRoot = if (!$WorkDirectory) { Get-ErpTestRoot $SourceSettingsFile }
$runName = 'm8-recovery-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
if ($WorkDirectory) {
    if (![IO.Path]::IsPathFullyQualified($WorkDirectory)) { throw '-WorkDirectory 必须使用绝对路径。' }
    $workspace = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($WorkDirectory))
} elseif ($testRoot) {
    $workspace = Join-Path $testRoot $runName
} elseif ($IsWindows) {
    throw '恢复演练会写入数据库副本，请先设置 ERP_TEST_ROOT 或指定非 C 盘的 -WorkDirectory。'
} else {
    $workspace = Join-Path (Join-Path $root '.local') $runName
}
Assert-ErpTestOutputPath $workspace
if ($IsWindows -and $workspace -match '[^\x00-\x7F]') { throw 'MySQL 恢复演练目录请使用非 C 盘英文路径。' }
if (Test-Path -LiteralPath $workspace) { throw '演练目录已存在，禁止覆盖。' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw '恢复演练端口被占用，未停止任何现有服务。' }
$mysql = Join-Path $MySqlDirectory 'mysql.exe'; $mysqld = Join-Path $MySqlDirectory 'mysqld.exe'; $dump = Join-Path $MySqlDirectory 'mysqldump.exe'; $binlog = Join-Path $MySqlDirectory 'mysqlbinlog.exe'
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $SourceSettingsFile))) { if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {$settings[$matches[1]]=$matches[2].TrimEnd("`r")} }
if($settings.TEST_DB_NAME -notmatch '^erp_test(?:_[a-z0-9]+)*$' -or $settings.DB_HOST -notin '127.0.0.1','localhost') { throw '源数据只允许本机项目隔离测试库。' }
$previousEnvironment = @{}
foreach ($key in 'MYSQL_PWD','ERP_TEST_ROOT','TEMP','TMP','TMPDIR','ERP_HTTP_LOG_FILE','ERP_STORAGE_LOCAL_ROOT','ERP_AI_KB_STORAGE') {
    $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key)
}
$instance=$null
$timer=[Diagnostics.Stopwatch]::StartNew()
function Invoke-SandboxSql([string]$Sql) {
    $value=$Sql | & $mysql --no-defaults --host=127.0.0.1 --port=$Port --user=root --default-character-set=utf8mb4 --batch --skip-column-names
    if($LASTEXITCODE -ne 0){throw '独立恢复实例 SQL 操作失败。'}
    return $value
}
function Import-SandboxFile([string]$Path,[string]$Database) {
    $start=[Diagnostics.ProcessStartInfo]::new($mysql);$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardInput=$true;$start.RedirectStandardError=$true
    foreach($argument in @('--no-defaults','--host=127.0.0.1',"--port=$Port",'--user=root','--default-character-set=utf8mb4',"--database=$Database")){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($start)
    $errorTask=$process.StandardError.ReadToEndAsync()
    try{$input=[IO.File]::OpenRead($Path);try{$input.CopyTo($process.StandardInput.BaseStream);$process.StandardInput.Close()}finally{$input.Dispose()};$process.WaitForExit();if($process.ExitCode -ne 0){throw '独立恢复实例导入失败。'}}finally{$process.Dispose()}
}
try {
    $workspaceParent = Split-Path -Parent $workspace
    [IO.Directory]::CreateDirectory($workspaceParent) | Out-Null
    New-Item -ItemType Directory -Path $workspace | Out-Null
    Set-ErpTestStorage $workspaceParent (Split-Path -Leaf $workspace)
    $temp = Join-Path $workspace 'tmp'
    $seed=Join-Path $workspace 'seed.sql'
    $env:MYSQL_PWD=$settings.TEST_DB_PASSWORD
    & $dump --no-defaults --host=$($settings.DB_HOST) --port=$($settings.DB_PORT) --user=$($settings.TEST_DB_USER) --single-transaction --no-tablespaces --set-gtid-purged=OFF --hex-blob --result-file=$seed $settings.TEST_DB_NAME
    if($LASTEXITCODE -ne 0){throw '测试库只读快照导出失败。'}
    $active=@("SELECT storage_key FROM attachment WHERE status='active';" | & $mysql --no-defaults --host=$($settings.DB_HOST) --port=$($settings.DB_PORT) --user=$($settings.TEST_DB_USER) --database=$($settings.TEST_DB_NAME) --batch --skip-column-names)
    if($LASTEXITCODE -ne 0){throw '无法核对测试对象仓。'}
    $objects=Join-Path $workspace 'source-objects';New-Item -ItemType Directory -Path $objects | Out-Null
    foreach($key in $active){if($key -notmatch '^[A-Za-z0-9][A-Za-z0-9/_-]*$'){throw '源对象键格式无效。'};$source=Join-Path $SourceObjectRoot $key;if(!(Test-Path -LiteralPath $source -PathType Leaf)){throw '测试库存在缺失的有效附件，先修复测试数据再演练。'};$destination=Join-Path $objects $key;New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null;Copy-Item -LiteralPath $source -Destination $destination}
    $datadir=Join-Path $workspace 'data'
    $initialize=@('--no-defaults','--initialize-insecure',('--basedir=' + (Split-Path -Parent $MySqlDirectory)),"--datadir=$datadir","--tmpdir=$temp",'--innodb-buffer-pool-size=64M','--innodb-redo-log-capacity=32M',('--log-error=' + (Join-Path $workspace 'initialize.log')))
    & $mysqld @initialize
    if($LASTEXITCODE -ne 0){throw '独立 MySQL 数据目录初始化失败。'}
    $arguments=@('--no-defaults','--console',('--basedir=' + (Split-Path -Parent $MySqlDirectory)),"--datadir=$datadir","--tmpdir=$temp",'--bind-address=127.0.0.1',"--port=$Port",'--mysqlx=OFF','--server-id=108','--log-bin=mysql-bin','--binlog-format=ROW','--gtid-mode=ON','--enforce-gtid-consistency=ON','--max-connections=40','--performance-schema=OFF','--innodb-buffer-pool-size=64M','--innodb-redo-log-capacity=32M','--sync-binlog=1','--innodb-flush-log-at-trx-commit=1')
    # Start-Process 会将参数数组拼成命令行，逐个引用以保留路径中的空格。
    $quoted=$arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}
    $instance=Start-Process -FilePath $mysqld -ArgumentList $quoted -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $workspace 'server.log') -RedirectStandardError (Join-Path $workspace 'server.err.log') -PassThru
    $env:MYSQL_PWD=$null
    $ready=$false
    for($attempt=0;$attempt -lt 120;$attempt++){Start-Sleep -Milliseconds 500;$result='SELECT 1;' | & $mysql --no-defaults --host=127.0.0.1 --port=$Port --user=root --batch --skip-column-names 2>$null;if($LASTEXITCODE -eq 0 -and $result -eq '1'){$ready=$true;break};if($instance.HasExited){break}}
    if(!$ready){throw '独立 MySQL 没有就绪。'}
    $secret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    Invoke-SandboxSql "ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '$secret';" | Out-Null
    $env:MYSQL_PWD=$secret
    $privateSettings=Join-Path $workspace 'sandbox.env'
    [IO.File]::WriteAllText($privateSettings,"DB_HOST=127.0.0.1`nDB_PORT=$Port`nDB_NAME=erp_restore_source`nDB_USER=root`nDB_PASSWORD=$secret`n",[Text.UTF8Encoding]::new($false))
    Invoke-SandboxSql 'CREATE DATABASE erp_restore_source CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' | Out-Null
    Import-SandboxFile $seed 'erp_restore_source'
    # 标记表仅属于演练库，用于验证选定水位之前提交被恢复、之后提交被排除。
    Invoke-SandboxSql "CREATE TABLE erp_restore_source.recovery_marker(id INT PRIMARY KEY,value VARCHAR(100) NOT NULL); INSERT INTO erp_restore_source.recovery_marker VALUES(1,'备份前'); FLUSH BINARY LOGS;" | Out-Null
    $backup=Join-Path $workspace 'backup'
    & (Join-Path $PSScriptRoot 'backup-local.ps1') -SettingsFile $privateSettings -ObjectRoot $objects -OutputDirectory $backup -SourceStopped -MySqlDirectory $MySqlDirectory
    $manifest=Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $startPosition=$manifest.binlogPosition -split '\s+'
    Invoke-SandboxSql "INSERT INTO erp_restore_source.recovery_marker VALUES(2,'恢复目标前已确认');" | Out-Null
    $endPosition=(Invoke-SandboxSql 'SHOW MASTER STATUS;') -split '\s+'
    Invoke-SandboxSql "INSERT INTO erp_restore_source.recovery_marker VALUES(3,'恢复目标后必须排除');" | Out-Null
    if($startPosition[0] -ne $endPosition[0]){throw '演练 binlog 出现轮换，需按多文件水位重放，拒绝猜测。'}
    $target='erp_restore_pitr'
    $restoredObjects=Join-Path $workspace 'restored-objects'
    $restoreTimer=[Diagnostics.Stopwatch]::StartNew()
    & (Join-Path $PSScriptRoot 'restore-local.ps1') -SettingsFile $privateSettings -BackupDirectory $backup -TargetDatabase $target -ObjectRoot $restoredObjects -MySqlDirectory $MySqlDirectory
    @{file=$startPosition[0];start=[long]$startPosition[1];stop=[long]$endPosition[1]} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $workspace 'replay-position.json') -Encoding utf8NoBOM
    $replay=Join-Path $workspace 'replay.sql'
    & $binlog --no-defaults --skip-gtids --database=$target "--rewrite-db=erp_restore_source->$target" --start-position=$($startPosition[1]) --stop-position=$($endPosition[1]) --result-file=$replay (Join-Path $datadir $startPosition[0])
    if($LASTEXITCODE -ne 0){throw '无法按确定的 binlog 终点生成重放。'}
    Import-SandboxFile $replay $target
    $markers=@(Invoke-SandboxSql "SELECT id FROM $target.recovery_marker ORDER BY id;")
    if(($markers -join ',') -ne '1,2'){throw '指定恢复点验证失败，目标前提交缺失或目标后提交被错误重放。'}
    $tables=@(Invoke-SandboxSql "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='$target' ORDER BY TABLE_NAME;")
    $checks=@()
    foreach($table in $tables){if($table -eq 'recovery_marker'){continue};$before=Invoke-SandboxSql "CHECKSUM TABLE erp_restore_source.``$table``;";$after=Invoke-SandboxSql "CHECKSUM TABLE $target.``$table``;";if(($before -split '\s+')[-1] -ne ($after -split '\s+')[-1]){throw "恢复表 $table 校验不一致。"};$checks+=$table}
    $restoreTimer.Stop()
    @{completedAt=[DateTime]::UtcNow.ToString('o');mysqlVersion='8.0.46';port=$Port;sourceDatabase='erp_restore_source';targetDatabase=$target;sourceBinlog=$startPosition[0];startPosition=[long]$startPosition[1];stopPosition=[long]$endPosition[1];markerIds=$markers;tablesVerified=$checks.Count;objectsVerified=@($manifest.objects).Count;rpoAtSelectedPositionSeconds=0;restoreSeconds=[Math]::Round($restoreTimer.Elapsed.TotalSeconds,3);totalSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3);result='passed';notes='完整表校验含业务事实、账本、幂等、Outbox和任务；全部业务对账须通过隔离应用另行执行。'} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $workspace 'recovery-result.json') -Encoding utf8NoBOM
    Write-Output ('指定 binlog 恢复点和完整表/附件摘要验证通过；结果位于 ' + (Join-Path $workspace 'recovery-result.json'))
} finally {
    try {
        if($instance -and !$instance.HasExited -and !$KeepInstance){$current=Get-CimInstance Win32_Process -Filter "ProcessId=$($instance.Id)";if(!$current.CommandLine.Contains($workspace)){throw '实例归属核验失败，未停止其他进程。'};$instance.Kill();$instance.WaitForExit()}
    } finally {
        if($instance){$instance.Dispose()}
        foreach ($key in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    }
}
