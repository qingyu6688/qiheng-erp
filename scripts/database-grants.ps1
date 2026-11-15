#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9_]{1,63}$')][string]$Database,
    [ValidatePattern('^[a-z][a-z0-9_]{1,31}$')][string]$RuntimeRole='erp_runtime_role',
    [ValidatePattern('^[a-z][a-z0-9_]{1,31}$')][string]$MigrationRole='erp_migration_role',
    [Parameter(Mandatory)][string]$OutputFile,
    [string]$MySqlDirectory='D:\mysql-8.0.46-winx64\bin'
)
$ErrorActionPreference='Stop'
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $SettingsFile))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$settings[$matches[1]]=$matches[2].TrimEnd("`r")}}
$previous=$env:MYSQL_PWD
try {
    $env:MYSQL_PWD=$settings.DB_PASSWORD
    $mysql=Join-Path $MySqlDirectory 'mysql.exe'
    $tables=@("SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='$Database' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;" | & $mysql --no-defaults --host=$($settings.DB_HOST) --port=$($settings.DB_PORT) --user=$($settings.DB_USER) --batch --skip-column-names)
    if($LASTEXITCODE -ne 0 -or $tables.Count -lt 80){throw '无法取得完整项目表清单，未生成授权。'}
    $appendOnly=@('inventory_movement','inventory_reservation_change','cost_adjustment','journal','journal_line','approval_record','audit_log','stock_transfer_event_line')
    $readOnly=@('sys_permission','ledger_account','flyway_schema_history')
    $sql=[Collections.Generic.List[string]]::new()
    $sql.Add('-- 由实际表清单生成，先审阅再以数据库管理员执行。账号密码另用私有脚本配置。')
    $sql.Add("CREATE ROLE IF NOT EXISTS '$RuntimeRole', '$MigrationRole';")
    foreach($table in $tables){if($table -notmatch '^[a-z][a-z0-9_]*$'){throw '表标识无效。'};$permissions=if($table -in $appendOnly){'SELECT, INSERT'}elseif($table -in $readOnly){'SELECT'}else{'SELECT, INSERT, UPDATE, DELETE'};$sql.Add(('GRANT {0} ON `{1}`.`{2}` TO ''{3}'';' -f $permissions,$Database,$table,$RuntimeRole))}
    $sql.Add(('GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON `{0}`.* TO ''{1}'';' -f $Database,$MigrationRole))
    $sql.Add('-- 示例：GRANT ''erp_runtime_role'' TO ''erp_runtime''@''应用私网地址''; SET DEFAULT ROLE ''erp_runtime_role'' TO ''erp_runtime''@''应用私网地址'';')
    $sql.Add('-- 账号使用 caching_sha2_password；跨主机账号额外 REQUIRE SSL，不给 root 远程授权。')
    [IO.File]::WriteAllLines([IO.Path]::GetFullPath($OutputFile),$sql,[Text.UTF8Encoding]::new($false))
    '最小权限授权脚本已生成；未修改任何账号授权。'
}finally{$env:MYSQL_PWD=$previous}
