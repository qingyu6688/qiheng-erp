#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][string]$ObjectRoot,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][switch]$SourceStopped,
    [string]$MetricsDirectory,
    [string]$MySqlDirectory = 'D:\mysql-8.0.46-winx64\bin'
)
$ErrorActionPreference = 'Stop'
if (!$SourceStopped) { throw '请先停止全部 API 和 Worker 写入，再提供 -SourceStopped。' }
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $SettingsFile))) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$matches[1]] = $matches[2].TrimEnd("`r") }
}
foreach ($key in 'DB_HOST','DB_PORT','DB_NAME','DB_USER','DB_PASSWORD') { if (!$settings[$key]) { throw "备份配置缺少 $key。" } }
if ($settings.DB_NAME -notmatch '^[a-z][a-z0-9_]{1,63}$') { throw '备份库名无效。' }
$source = (Resolve-Path -LiteralPath $ObjectRoot).Path
if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '源对象根目录不能是符号链接或目录联接。' }
foreach ($entry in Get-ChildItem -LiteralPath $source -Recurse -Force) { if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '源对象仓不能包含符号链接或目录联接。' } }
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw '备份输出目录必须不存在，禁止覆盖已有备份。' }
if ($output.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '备份目录不能位于对象仓内部。' }
New-Item -ItemType Directory -Path $output | Out-Null
$mysql = Join-Path $MySqlDirectory 'mysql.exe'
$dump = Join-Path $MySqlDirectory 'mysqldump.exe'
$previousPassword = $env:MYSQL_PWD
$lock = $null
$attemptedAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$previousSuccess = 0L
if ($MetricsDirectory) {
    $metricsRoot = (Resolve-Path -LiteralPath $MetricsDirectory).Path
    $metricsPath = Join-Path $metricsRoot 'erp-backup.prom'
    if (Test-Path -LiteralPath $metricsPath) {
        foreach ($metricLine in [IO.File]::ReadAllLines($metricsPath)) {
            if ($metricLine -match '^erp_backup_last_success_timestamp_seconds ([0-9]+)$') { $previousSuccess = [long]$matches[1] }
        }
    }
}
function Write-BackupMetrics([bool]$Succeeded, [bool]$InProgress) {
    if (!$MetricsDirectory) { return }
    $lastSuccess = if ($Succeeded) { [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() } else { $previousSuccess }
    $successValue = if ($Succeeded) { 1 } else { 0 }
    $progressValue = if ($InProgress) { 1 } else { 0 }
    $content = @(
        "erp_backup_last_attempt_timestamp_seconds $attemptedAt"
        "erp_backup_last_success_timestamp_seconds $lastSuccess"
        "erp_backup_last_attempt_success $successValue"
        "erp_backup_in_progress $progressValue"
    ) -join "`n"
    $temporary = $metricsPath + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    [IO.File]::WriteAllText($temporary, $content + "`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary, $metricsPath, $true)
}
try {
    Write-BackupMetrics $false $true
    $env:MYSQL_PWD = $settings.DB_PASSWORD
    $connection = @('--no-defaults', ('--host=' + $settings.DB_HOST), ('--port=' + $settings.DB_PORT), ('--user=' + $settings.DB_USER), '--default-character-set=utf8mb4')
    $version = & $mysql @connection --batch --skip-column-names '--execute=SELECT VERSION();'
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^8\.0\.46(?:\D|$)') { throw '备份连接必须为项目指定的 MySQL 8.0.46。' }
    # 停写声明之外再持有服务端全局读锁，使数据库与私有对象副本处在同一冻结窗口。
    $start = [Diagnostics.ProcessStartInfo]::new($mysql)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in ($connection + @('--batch','--skip-column-names','--unbuffered','--execute=FLUSH TABLES WITH READ LOCK; SELECT "ERP_BACKUP_LOCKED"; DO SLEEP(600);'))) { $start.ArgumentList.Add($argument) }
    $lock = [Diagnostics.Process]::Start($start)
    $lineTask = $lock.StandardOutput.ReadLineAsync()
    if (!$lineTask.Wait(10000) -or $lineTask.Result -ne 'ERP_BACKUP_LOCKED') { throw '无法取得备份读锁，请检查备份账号 RELOAD 权限和未结束的长事务。' }
    $position = & $mysql @connection --batch --skip-column-names '--execute=SHOW MASTER STATUS;'
    if ($LASTEXITCODE -ne 0) { throw '无法保存 binlog 备份水位。' }
    $dumpPath = Join-Path $output 'database.sql'
    & $dump @connection --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --hex-blob --routines --events --triggers --result-file=$dumpPath $settings.DB_NAME
    if ($LASTEXITCODE -ne 0) { throw '完整数据库备份失败。' }
    $objects = Join-Path $output 'objects'
    New-Item -ItemType Directory -Path $objects | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $source -Force) { Copy-Item -LiteralPath $item.FullName -Destination $objects -Recurse -Force }
    $hashes = @(Get-ChildItem -LiteralPath $objects -Recurse -File | ForEach-Object {
        if ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '对象副本不能包含目录联接或符号链接。' }
        @{ path = [IO.Path]::GetRelativePath($objects, $_.FullName).Replace('\','/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = $_.Length }
    })
    if ($lock.HasExited) { throw '备份冻结锁已释放，不能保证当前副本一致；请缩短备份窗口后重新备份。' }
    $manifest = @{
        formatVersion = 1; createdAt = [DateTime]::UtcNow.ToString('o'); mysqlVersion = $version.Trim(); sourceDatabase = $settings.DB_NAME
        binlogPosition = if ($position) { $position.Trim() } else { $null }; databaseSha256 = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
        sourceStopped = $true; globalReadLock = $true; objects = $hashes
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8NoBOM
    Write-BackupMetrics $true $false
    '完整数据库和对象仓已在冻结窗口备份，摘要清单已保存。'
} catch {
    $backupFailure = $_
    try { Write-BackupMetrics $false $false }
    catch { Write-Warning '备份失败状态写入失败，请同时检查 textfile 目录权限。' }
    throw $backupFailure
} finally {
    if ($lock -and !$lock.HasExited) { $lock.Kill(); $lock.WaitForExit() }
    if ($lock) { $lock.Dispose() }
    $env:MYSQL_PWD = $previousPassword
}
