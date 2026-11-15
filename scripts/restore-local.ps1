#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][string]$BackupDirectory,
    [Parameter(Mandatory)][ValidatePattern('^erp_restore_[a-z0-9_]{1,40}$')][string]$TargetDatabase,
    [Parameter(Mandatory)][string]$ObjectRoot,
    [string]$MySqlDirectory = 'D:\mysql-8.0.46-winx64\bin'
)
$ErrorActionPreference = 'Stop'
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $SettingsFile))) { if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$matches[1]] = $matches[2].TrimEnd("`r") } }
foreach ($key in 'DB_HOST','DB_PORT','DB_USER','DB_PASSWORD') { if (!$settings[$key]) { throw "恢复配置缺少 $key。" } }
if ($settings.DB_HOST -notin '127.0.0.1','localhost') { throw '本机恢复脚本只允许回环地址，生产恢复请使用经过审阅的运维流程。' }
$backup = (Resolve-Path -LiteralPath $BackupDirectory).Path
$target = [IO.Path]::GetFullPath($ObjectRoot)
if (Test-Path -LiteralPath $target) { throw '恢复对象目录必须不存在，禁止覆盖已有对象仓。' }
$manifest = Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
if ($manifest.formatVersion -ne 1 -or !$manifest.sourceStopped -or !$manifest.globalReadLock) { throw '备份没有可验证的冻结水位，拒绝恢复。' }
$dumpPath = Join-Path $backup 'database.sql'
if ((Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.databaseSha256) { throw '数据库备份摘要不符，拒绝恢复。' }
$objects = Join-Path $backup 'objects'
if (@(Get-ChildItem -LiteralPath $objects -Recurse -File).Count -ne @($manifest.objects).Count) { throw '对象副本与清单文件数不一致，拒绝恢复。' }
foreach ($entry in $manifest.objects) {
    $path = [IO.Path]::GetFullPath((Join-Path $objects $entry.path))
    if (!$path.StartsWith($objects + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '备份清单对象路径越界。' }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw '对象备份摘要不符，拒绝恢复。' }
}
$mysql = Join-Path $MySqlDirectory 'mysql.exe'
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $settings.DB_PASSWORD
    $connection = @('--no-defaults',('--host=' + $settings.DB_HOST),('--port=' + $settings.DB_PORT),('--user=' + $settings.DB_USER),'--default-character-set=utf8mb4')
    $existing = & $mysql @connection --batch --skip-column-names "--execute=SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$TargetDatabase';"
    if ($LASTEXITCODE -ne 0 -or $existing -ne '0') { throw '恢复目标库已存在或无法核验，禁止覆盖。' }
    & $mysql @connection "--execute=CREATE DATABASE ``$TargetDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw '无法建立新的恢复库。' }
    $start = [Diagnostics.ProcessStartInfo]::new($mysql)
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.RedirectStandardInput = $true; $start.RedirectStandardError = $true
    foreach ($argument in ($connection + @('--database=' + $TargetDatabase))) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    $errorTask = $process.StandardError.ReadToEndAsync()
    try {
        $reader = [IO.File]::OpenRead($dumpPath)
        try { $reader.CopyTo($process.StandardInput.BaseStream); $process.StandardInput.Close() } finally { $reader.Dispose() }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw '恢复 SQL 执行失败，目标库保留供核查，不自动删除。' }
    } finally { $process.Dispose() }
    New-Item -ItemType Directory -Path $target | Out-Null
    foreach ($entry in $manifest.objects) {
        $sourceFile=Join-Path $objects $entry.path
        $targetFile=Join-Path $target $entry.path
        New-Item -ItemType Directory -Path (Split-Path -Parent $targetFile) -Force | Out-Null
        Copy-Item -LiteralPath $sourceFile -Destination $targetFile
    }
    @{ restoredAt=[DateTime]::UtcNow.ToString('o'); targetDatabase=$TargetDatabase; databaseSha256=$manifest.databaseSha256; objectCount=@($manifest.objects).Count; recoveryPoint=$manifest.createdAt; binlogPosition=$manifest.binlogPosition } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path (Split-Path -Parent $target) ($TargetDatabase + '-restore.json')) -Encoding utf8NoBOM
    '数据库和对象仓已恢复到新目标；请在隔离应用中核对附件与全部对账后再切换。'
} finally { $env:MYSQL_PWD = $previousPassword }
