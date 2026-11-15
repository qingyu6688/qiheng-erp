[CmdletBinding()]
param(
    [string]$JavaHome = 'E:\JDK21',
    [string]$RedisDirectory = 'D:\Redis',
    [ValidateRange(1024, 65535)][int]$RedisPort = 6380
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$settings = Join-Path $root '.env'
$private = Join-Path $root '.local'
$pending = Join-Path $private 'prepare.pending.env'
$redisConfig = Join-Path $private 'redis.conf'
$redisServer = Join-Path $RedisDirectory 'redis-server.exe'
$redisCli = Join-Path $RedisDirectory 'redis-cli.exe'
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw '未找到指定的 JDK。' }
if (!(Test-Path -LiteralPath $redisServer) -or !(Test-Path -LiteralPath $redisCli)) { throw '未找到指定的 Redis 工具目录。' }

function New-ProjectPassword {
    $bytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Invoke-SetupSql([string]$sql, [string]$dbUser, [string]$dbPassword) {
    $previous = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $dbPassword
        $result = $sql | mysql --no-defaults --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u $dbUser --batch --skip-column-names 2>$null
        if ($LASTEXITCODE -ne 0) { throw '数据库操作失败，请检查账号的连接、建库及授权权限。' }
        return $result
    } finally { $env:MYSQL_PWD = $previous }
}

function Read-ProjectSettings([string]$path) {
    $config = @{}
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $config[$matches[1]] = $matches[2].TrimEnd("`r") }
    }
    return $config
}

function New-SetupMarker([string]$serverId) {
    $occupied = Invoke-SetupSql "SELECT (SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ('erp_dev','erp_test')) + (SELECT COUNT(*) FROM mysql.user WHERE User IN ('erp_dev','erp_test'));" $env:DB_USERNAME $env:DB_PASSWORD
    if ($occupied -ne '0') { throw '库或账号已存在，请在 .env 中填写已分配的项目凭据。' }
    $text = [IO.File]::ReadAllText((Join-Path $root '.env.example'))
    $values = @{ JAVA_HOME=$JavaHome; DB_PASSWORD=New-ProjectPassword; TEST_DB_PASSWORD=New-ProjectPassword; REDIS_PORT=$RedisPort; REDIS_PASSWORD=New-ProjectPassword; ERP_DEV_ADMIN_PASSWORD=New-ProjectPassword }
    foreach ($name in $values.Keys) {
        $text = [regex]::Replace($text, "(?m)^$name=[^\r\n]*", { param($m) "$name=$($values[$name])" })
    }
    # 完整写入后才发布恢复标记；标记出现之前不会执行任何数据库 DDL。
    $temporary = "$pending.tmp"
    [IO.File]::WriteAllText($temporary, "# 本机初始化实例 $serverId`n$text")
    [IO.File]::Move($temporary, $pending)
}

function Initialize-ProjectDatabase([string]$database, [string]$secret) {
    if ($secret -notmatch '^[0-9a-f]{64}$') { throw '初始化标记的项目凭据无效。' }
    $grantScope = $database.Replace('_','\_')
    $account = "'$database'@'127.0.0.1'"
    $hasSchema = Invoke-SetupSql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$database';" $env:DB_USERNAME $env:DB_PASSWORD
    $accounts = @(Invoke-SetupSql "SELECT Host FROM mysql.user WHERE User='$database';" $env:DB_USERNAME $env:DB_PASSWORD)
    if ($accounts.Count -eq 0) {
        if ($hasSchema -ne '0') { throw "库 $database 已被占用，请核对数据库归属。" }
        Invoke-SetupSql "CREATE USER $account IDENTIFIED BY '$secret';" $env:DB_USERNAME $env:DB_PASSWORD
    } elseif ($accounts.Count -ne 1 -or $accounts[0] -ne '127.0.0.1') {
        throw "已有同名账号 $database，请核对数据库归属。"
    }
    # 用此次随机生成的密码核实账号，拒绝对其他已有账号授权或改密。
    $matchedAccount = Invoke-SetupSql 'SELECT CURRENT_USER();' $database $secret
    if ($matchedAccount -ne "$database@127.0.0.1") { throw "项目账号 $database 的身份核实失败。" }
    if ($hasSchema -eq '0') {
        # 先将尚空闲的库名划给项目账号，再创建库。中途退出可根据原凭据续办。
        $grant = 'GRANT ALL PRIVILEGES ON `{0}`.* TO {1};' -f $grantScope,$account
        Invoke-SetupSql $grant $env:DB_USERNAME $env:DB_PASSWORD
        $create = 'CREATE DATABASE `{0}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' -f $database
        Invoke-SetupSql $create $database $secret
    } else {
        # 首次配置尚未发布，脚本创建的库应当为空，并且原项目账号已经有权访问。
        $objects = Invoke-SetupSql ('USE `{0}`; SHOW FULL TABLES;' -f $database) $database $secret
        if ($objects) { throw "初始化尚未完成，但 $database 已有业务对象，请人工核对归属。" }
    }
}

function Initialize-LocalSettings {
    if (Test-Path -LiteralPath $settings) {
        if (Test-Path -LiteralPath $pending) { throw '本机配置与未完成的初始化标记同时存在，请核实后再继续。' }
        return
    }
    if (!$env:DB_USERNAME -or !$env:DB_PASSWORD) { throw '请在当前终端配置本机建库账号 DB_USERNAME 和 DB_PASSWORD。' }
    $serverId = Invoke-SetupSql 'SELECT @@server_uuid WHERE VERSION() = ''8.0.46'';' $env:DB_USERNAME $env:DB_PASSWORD
    if ($serverId -notmatch '^[a-fA-F0-9-]{36}$') { throw '连接到的 MySQL 实例版本与项目约定不符。' }
    if (!(Test-Path -LiteralPath $pending)) { New-SetupMarker $serverId }
    if ((Get-Content -LiteralPath $pending -Encoding UTF8 -TotalCount 1) -ne "# 本机初始化实例 $serverId") {
        throw '本机 MySQL 实例与初始化标记不符，请核对连接目标。'
    }
    $initial = Read-ProjectSettings $pending
    foreach ($database in 'erp_dev','erp_test') {
        $secret = if ($database -eq 'erp_dev') { $initial.DB_PASSWORD } else { $initial.TEST_DB_PASSWORD }
        Initialize-ProjectDatabase $database $secret
    }
    [IO.File]::Move($pending, $settings)
}

function Test-LocalServices {
    $config = Read-ProjectSettings $settings
    foreach ($entry in @(@('DB_USER','DB_PASSWORD','DB_NAME'),@('TEST_DB_USER','TEST_DB_PASSWORD','TEST_DB_NAME'))) {
        $database = $config[$entry[2]]
        if ($database -notin 'erp_dev','erp_test') { throw '本机准备脚本仅支持项目专用开发库和测试库。' }
        $version = Invoke-SetupSql ('USE `{0}`; SELECT VERSION();' -f $database) $config[$entry[0]] $config[$entry[1]]
        if ($version -ne '8.0.46') { throw '连接到的 MySQL 服务端版本与项目约定不符。' }
    }
    $port = [int]$config.REDIS_PORT
    $previousAuth = $env:REDISCLI_AUTH
    try {
        $env:REDISCLI_AUTH = $config.REDIS_PASSWORD
        $ping = & $redisCli --no-auth-warning -h 127.0.0.1 -p $port PING 2>$null
        if ($ping -ne 'PONG') {
            if ($port -ne $RedisPort -or $port -eq 6379) { throw '请检查 .env 中现有 Redis 的凭据和端口。' }
            if ($config.REDIS_PASSWORD -notmatch '^[a-fA-F0-9]{64}$') { throw '独立 Redis 实例需要准备脚本生成的随机密码。' }
            $tcp = [Net.Sockets.TcpClient]::new()
            try {
                try { $tcp.Connect('127.0.0.1', $port) } catch [Net.Sockets.SocketException] { Write-Verbose '本机端口空闲。' }
                if ($tcp.Connected) { throw 'Redis 端口已被占用，请核实项目配置。' }
            } finally { $tcp.Dispose() }
            [IO.Directory]::CreateDirectory($private) | Out-Null
            [IO.File]::WriteAllLines($redisConfig, @('bind 127.0.0.1', "port $port", 'protected-mode yes', 'save ""', 'appendonly no', "requirepass $($config.REDIS_PASSWORD)"))
            $process = Start-Process -FilePath $redisServer -ArgumentList 'redis.conf' -WorkingDirectory $private -WindowStyle Hidden -PassThru
            for ($i=0; $i -lt 30; $i++) {
                Start-Sleep -Milliseconds 150
                $ping = & $redisCli --no-auth-warning -h 127.0.0.1 -p $port PING 2>$null
                if ($ping -eq 'PONG' -or $process.HasExited) { break }
            }
            if ($ping -ne 'PONG') { throw 'ERP 专用 Redis 启动失败。' }
        }
    } finally { $env:REDISCLI_AUTH = $previousAuth }
    Write-Output "本机 ERP 开发库、测试库和 Redis（$port）已验证。"
}

[IO.Directory]::CreateDirectory($private) | Out-Null
$lockPath = Join-Path $private 'prepare.lock'
try {
    $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
} catch [IO.IOException] {
    throw '另一个准备进程正在使用本项目，或初始化锁文件不可写。'
}
try {
    Initialize-LocalSettings
    Test-LocalServices
} finally { $lock.Dispose() }
