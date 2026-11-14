[CmdletBinding()]
param(
    [ValidateSet('verify', 'server', 'web', 'e2e', 'e2e-api', 'e2e-web')]
    [string]$Action = 'verify'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$settingsFile = Join-Path $projectRoot '.env'
if (!(Test-Path -LiteralPath $settingsFile)) { throw '请先按部署说明创建项目根目录下的 .env。' }
$frontend = $Action -in 'web','e2e','e2e-web'
if ($frontend) {
    # npm 子进程只使用页面端口。浏览器测试还需要演示账号的口令。
    Get-ChildItem Env: | Where-Object { $_.Name -match '^(DB_|TEST_DB_|REDIS_|MYSQL_PWD$|ERP_DEV_ADMIN_PASSWORD$)' } |
        ForEach-Object { [Environment]::SetEnvironmentVariable($_.Name, $null, 'Process') }
}
foreach ($line in [IO.File]::ReadAllLines($settingsFile)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        if ($frontend -and $matches[1] -ne 'SERVER_PORT' -and
            !($Action -eq 'e2e' -and $matches[1] -eq 'ERP_DEV_ADMIN_PASSWORD')) { continue }
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
if ($Action -in 'web','e2e','e2e-web') {
    $directory = Get-Item -LiteralPath $projectRoot
    if ($directory.LinkType -eq 'Junction') {
        $projectRoot = $directory.ResolveLinkTarget($true).FullName
    }
    Push-Location (Join-Path $projectRoot 'erp-web')
    try {
        if ($Action -eq 'e2e') {
            npm run test:e2e
        } elseif ($Action -eq 'e2e-web') {
            $env:ERP_API_ORIGIN = 'http://127.0.0.1:18080'
            npm run dev -- --port 15173
        } else {
            $env:ERP_API_ORIGIN = "http://127.0.0.1:$env:SERVER_PORT"
            npm run dev
        }
        $status = $LASTEXITCODE
    } finally { Pop-Location }
    exit $status
}
if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { throw '请在 .env 中配置 JDK 21 的 JAVA_HOME。' }
if ($Action -in 'e2e-api','verify' -and (!$env:TEST_DB_NAME -or $env:TEST_DB_NAME -eq $env:DB_NAME -or $env:TEST_DB_NAME -notmatch '^erp_test(?:_[a-z0-9]+)*$')) {
    throw '测试库必须独立于开发库，名称使用 erp_test 或 erp_test_环境名。'
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location (Join-Path $projectRoot 'erp-server')
try {
    if ($Action -eq 'e2e-api') {
        $env:APP_ENV = 'e2e'
        $env:SPRING_PROFILES_ACTIVE = 'dev'
        $env:SERVER_PORT = '18080'
        $env:ERP_NODE_ID = '1'
        $env:DB_NAME = $env:TEST_DB_NAME
        $env:DB_USER = $env:TEST_DB_USER
        $env:DB_PASSWORD = $env:TEST_DB_PASSWORD
        mvn spring-boot:run
    } elseif ($Action -eq 'server') {
        $env:SPRING_PROFILES_ACTIVE = 'dev'
        mvn spring-boot:run
    } else {
        mvn verify
    }
    $status = $LASTEXITCODE
} finally { Pop-Location }
exit $status
