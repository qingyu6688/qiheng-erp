#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][ValidatePattern('^erp_restore_[a-z0-9_]{1,40}$')][string]$Database,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$MySqlDirectory='D:\mysql-8.0.46-winx64\bin'
)
$ErrorActionPreference='Stop'
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $SettingsFile))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
if($settings.DB_HOST -notin '127.0.0.1','localhost' -or $settings.DB_PORT -eq '3306'){throw '权限演练只允许本机独立恢复实例，禁止操作共享3306。'}
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw '权限验证输出目录必须不存在。'}
New-Item -ItemType Directory -Path $output | Out-Null
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,8)
$runtime='erp_runtime_'+$suffix;$migration='erp_migrate_'+$suffix
$runtimeRole='erp_runrole_'+$suffix;$migrationRole='erp_migrole_'+$suffix
$runtimeSecret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$migrationSecret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$mysql=Join-Path $MySqlDirectory 'mysql.exe'
$connection=@('--no-defaults',('--host='+$settings.DB_HOST),('--port='+$settings.DB_PORT),'--default-character-set=utf8mb4','--batch','--skip-column-names')
$previous=$env:MYSQL_PWD
try {
    & (Join-Path $PSScriptRoot 'database-grants.ps1') -SettingsFile $SettingsFile -Database $Database -RuntimeRole $runtimeRole -MigrationRole $migrationRole -OutputFile (Join-Path $output 'grants.sql') -MySqlDirectory $MySqlDirectory
    $env:MYSQL_PWD=$settings.DB_PASSWORD
    $sql="CREATE USER '$runtime'@'localhost' IDENTIFIED WITH caching_sha2_password BY '$runtimeSecret'; CREATE USER '$migration'@'localhost' IDENTIFIED WITH caching_sha2_password BY '$migrationSecret';`n"+[IO.File]::ReadAllText((Join-Path $output 'grants.sql'))+"`nGRANT '$runtimeRole' TO '$runtime'@'localhost'; SET DEFAULT ROLE '$runtimeRole' TO '$runtime'@'localhost'; GRANT '$migrationRole' TO '$migration'@'localhost'; SET DEFAULT ROLE '$migrationRole' TO '$migration'@'localhost';"
    $sql | & $mysql @connection ('--user='+$settings.DB_USER) 2> (Join-Path $output 'setup.err.log') | Out-Null
    if($LASTEXITCODE -ne 0){throw '隔离权限角色创建失败。'}
    $env:MYSQL_PWD=$runtimeSecret
    'START TRANSACTION; INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(850000000000000001,850000000000000001,''permission.check'',''test''); SELECT COUNT(*) FROM audit_log WHERE id=850000000000000001; ROLLBACK;' | & $mysql @connection ('--user='+$runtime) ('--database='+$Database) 2> (Join-Path $output 'runtime.err.log') | Out-Null
    if($LASTEXITCODE -ne 0){throw '运行账号的合法只追加写入失败。'}
    $blocked=@()
    foreach($probe in @(@('ddl','CREATE TABLE runtime_forbidden_probe(id INT);'),@('audit_update','UPDATE audit_log SET action=action WHERE id=0;'),@('journal_delete','DELETE FROM journal WHERE id=0;'),@('permission_write','UPDATE sys_permission SET name=name WHERE id=0;'))){
        $probe[1] | & $mysql @connection ('--user='+$runtime) ('--database='+$Database) 2> (Join-Path $output ($probe[0]+'.err.log')) | Out-Null
        if($LASTEXITCODE -eq 0){throw ('运行账号越权检查未阻止：'+$probe[0])};$blocked+=$probe[0]
    }
    $env:MYSQL_PWD=$migrationSecret
    'CREATE TABLE migration_allowed_probe(id INT PRIMARY KEY); ALTER TABLE migration_allowed_probe ADD COLUMN note VARCHAR(20); DROP TABLE migration_allowed_probe;' | & $mysql @connection ('--user='+$migration) ('--database='+$Database) 2> (Join-Path $output 'migration.err.log') | Out-Null
    if($LASTEXITCODE -ne 0){throw '独立迁移账号的DDL验证失败。'}
    [IO.File]::WriteAllText((Join-Path $output 'runtime.env'),"DB_HOST=$($settings.DB_HOST)`nDB_PORT=$($settings.DB_PORT)`nDB_NAME=$Database`nDB_USER=$runtime`nDB_PASSWORD=$runtimeSecret`nDB_MIGRATION_USER=$migration`nDB_MIGRATION_PASSWORD=$migrationSecret`n",[Text.UTF8Encoding]::new($false))
    @{completedAt=[DateTime]::UtcNow.ToString('o');database=$Database;runtimeInsert='passed';runtimeForbidden=$blocked;migrationDdl='passed';result='passed';notes='真实MySQL账号/角色验证，应用完整启动由最终集成检查覆盖。'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'permissions-result.json') -Encoding utf8NoBOM
    '真实最小权限检查通过：运行写入可用，DDL和事实修改被拒绝，独立迁移DDL通过。'
}finally{$env:MYSQL_PWD=$previous}
