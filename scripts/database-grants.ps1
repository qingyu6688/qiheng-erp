#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9_]{1,63}$')][string]$Database,
    [ValidatePattern('^[a-z][a-z0-9_]{1,31}$')][string]$RuntimeRole='erp_runtime_role',
    [ValidatePattern('^[a-z][a-z0-9_]{1,31}$')][string]$MigrationRole='erp_migration_role',
    [ValidatePattern('^[a-z][a-z0-9_]{1,31}$')][string]$ReportRole='erp_report_role',
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
    $sql.Add("CREATE ROLE IF NOT EXISTS '$RuntimeRole', '$MigrationRole', '$ReportRole';")
    foreach($table in $tables){if($table -notmatch '^[a-z][a-z0-9_]*$'){throw '表标识无效。'};$permissions=if($table -in $appendOnly){'SELECT, INSERT'}elseif($table -in $readOnly){'SELECT'}else{'SELECT, INSERT, UPDATE, DELETE'};$sql.Add(('GRANT {0} ON `{1}`.`{2}` TO ''{3}'';' -f $permissions,$Database,$table,$RuntimeRole))}
    $sql.Add(('GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON `{0}`.* TO ''{1}'';' -f $Database,$MigrationRole))
    $reportTables=@('shipment','shipment_line','sales_return','sales_return_line','goods_receipt','goods_receipt_line','purchase_return','purchase_return_line','receivable','payable','document_reversal','inventory_balance','inventory_cost','inventory_movement','cost_adjustment','partner_open_item','journal','journal_line','warehouse','sku','business_partner','cash_account','receipt','payment','opening_entry','account_transfer','stock_adjustment','stock_count','stock_transfer','refund_request','report_snapshot_watermark')
    foreach($table in $reportTables){if($table -in $tables){$sql.Add(('GRANT SELECT ON `{0}`.`{1}` TO ''{2}'';' -f $Database,$table,$ReportRole))}}
    $sql.Add('-- 报表角色不授予写权限；在备库给报表账号绑定此角色，API 与 Worker 配置相同的 ERP_REPORT_*。')
    $sql.Add('-- 示例：GRANT ''erp_runtime_role'' TO ''erp_runtime''@''应用私网地址''; SET DEFAULT ROLE ''erp_runtime_role'' TO ''erp_runtime''@''应用私网地址'';')
    $sql.Add('-- 账号使用 caching_sha2_password；跨主机账号额外 REQUIRE SSL，不给 root 远程授权。')
    [IO.File]::WriteAllLines([IO.Path]::GetFullPath($OutputFile),$sql,[Text.UTF8Encoding]::new($false))
    '最小权限授权脚本已生成；未修改任何账号授权。'
}finally{$env:MYSQL_PWD=$previous}
