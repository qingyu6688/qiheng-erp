[CmdletBinding()]
param(
    [ValidateSet('verify', 'verify-ai', 'server', 'web', 'e2e', 'e2e-api', 'e2e-web')]
    [string]$Action = 'verify'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'test-storage.ps1')

function Read-DatabaseIdentity([string]$File) {
    $identity = @{}
    foreach ($setting in [IO.File]::ReadLines($File)) {
        if ($setting -match '^(DB_HOST|DB_PORT|DB_NAME|TEST_DB_NAME)=(.*)$') {
            $identity[$matches[1]] = $matches[2].Trim()
        }
    }
    return $identity
}

function Get-DatabaseEndpoint([string]$HostName, [string]$Port) {
    if (!$HostName -or $HostName -in 'localhost','::1','127.0.0.1') { $HostName = 'localhost' }
    if (!$Port) { $Port = '3306' }
    return "${HostName}:$Port"
}

function Assert-IsolatedWorktreeDatabase([string]$Database) {
    if (!(Test-Path -LiteralPath (Join-Path $projectRoot '.git')) -or !(Get-Command git -ErrorAction SilentlyContinue)) { return }
    $endpoint = Get-DatabaseEndpoint $env:DB_HOST $env:DB_PORT
    $worktrees = @(git -C $projectRoot worktree list --porcelain)
    if ($LASTEXITCODE -ne 0) { throw '无法核对 Git 工作树的数据库配置。' }
    foreach ($entry in $worktrees) {
        if (!$entry.StartsWith('worktree ')) { continue }
        $otherRoot = [IO.Path]::GetFullPath($entry.Substring(9))
        if ($otherRoot -eq $projectRoot) { continue }
        $otherFile = Join-Path $otherRoot '.env'
        if (!(Test-Path -LiteralPath $otherFile -PathType Leaf)) { continue }
        $other = Read-DatabaseIdentity $otherFile
        if ((Get-DatabaseEndpoint $other.DB_HOST $other.DB_PORT) -eq $endpoint -and
            $Database -in $other.DB_NAME,$other.TEST_DB_NAME) {
            throw '当前数据库与另一 Git 工作树重复。请为当前工作树配置独立的 DB_NAME 和 TEST_DB_NAME，参见《环境检查》。'
        }
    }
}

$settingsFile = if ($env:ERP_PROJECT_SETTINGS_FILE) {
    [IO.Path]::GetFullPath($env:ERP_PROJECT_SETTINGS_FILE, $projectRoot)
} else { Join-Path $projectRoot '.env' }
if (!(Test-Path -LiteralPath $settingsFile)) { throw '请先按部署说明创建项目根目录下的 .env。' }
$testRoot = if ($Action -in 'verify','verify-ai','e2e','e2e-api','e2e-web') {
    $configured = Get-ErpTestRoot @($settingsFile, (Join-Path $projectRoot '.env'))
    if ($IsWindows -and !$configured) { throw '请先在 .env 中设置非 C 盘的 ERP_TEST_ROOT，再运行本机测试。' }
    $configured
}
$frontend = $Action -in 'web','e2e','e2e-web'
if ($frontend) {
    # npm 子进程只使用页面端口。浏览器测试还需要演示账号的口令。
    Get-ChildItem Env: | Where-Object { $_.Name -match '^(DB_|TEST_DB_|REDIS_|MYSQL_PWD$|ERP_DEV_ADMIN_PASSWORD$)' } |
        ForEach-Object { [Environment]::SetEnvironmentVariable($_.Name, $null, 'Process') }
}
foreach ($line in [IO.File]::ReadAllLines($settingsFile)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        if ($frontend -and $matches[1] -notin 'SERVER_PORT','ERP_AI_ORIGIN' -and !($Action -eq 'web' -and $matches[1] -in 'ERP_DEV_ADMIN_PASSWORD','ERP_DEV_TENANT_CODE') -and
            !($Action -eq 'e2e' -and $matches[1] -eq 'ERP_DEV_ADMIN_PASSWORD')) { continue }
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2].TrimEnd("`r"), 'Process')
    }
}
if ($testRoot) {
    Set-ErpTestStorage $testRoot $Action
    if ($frontend) { $env:npm_config_logs_dir = Join-Path $env:TEMP 'npm-logs' }
}
if ($Action -in 'web','e2e','e2e-web') {
    $directory = Get-Item -LiteralPath $projectRoot
    if ($directory.LinkType -eq 'Junction') {
        $linkedRoot = @($directory.Target)[0]
        if (!$linkedRoot -or !(Test-Path -LiteralPath $linkedRoot -PathType Container)) { throw '项目目录联接目标无效。' }
        $projectRoot = (Resolve-Path -LiteralPath $linkedRoot).Path
    }
    Push-Location (Join-Path $projectRoot 'erp-web')
    try {
        if ($Action -eq 'e2e') {
            $env:ERP_E2E_PASSWORD = [Guid]::NewGuid().ToString('N')
            $env:ERP_E2E_TENANT_CODE = 'E2E-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
            $env:ERP_DEV_ADMIN_PASSWORD = $env:ERP_E2E_PASSWORD
            npm run test:e2e
        } elseif ($Action -eq 'e2e-web') {
            $env:ERP_API_ORIGIN = 'http://127.0.0.1:18080'
            $outputOptions = @()
            if ($testRoot) {
                $previewDirectory = Join-Path $testRoot ('e2e-web/dist-' + [Guid]::NewGuid().ToString('N'))
                Assert-ErpTestOutputPath $previewDirectory
                $outputOptions = @('--outDir', $previewDirectory)
                npm --ignore-scripts run build -- @outputOptions
            } else {
                npm run build
            }
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            npm run preview -- --host 127.0.0.1 --port 15173 --strictPort @outputOptions
        } else {
            $env:ERP_API_ORIGIN = "http://127.0.0.1:$env:SERVER_PORT"
            npm run dev
        }
        $status = $LASTEXITCODE
    } finally {
        Pop-Location
        if ($Action -eq 'e2e' -and $IsWindows) {
            # Windows 下测试超时可能留下子进程，只回收本工作区占用测试端口的API和Vite。
            $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in 18080,15173)
            foreach ($listener in $listeners) {
                $testProcess = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $listener.OwningProcess) -ErrorAction SilentlyContinue
                if (!$testProcess) { continue }
                $belongsToProject = $testProcess.CommandLine -and $testProcess.CommandLine.Replace('/','\').IndexOf($projectRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0
                if (!$belongsToProject -and $testProcess.Name -eq 'java.exe') {
                    $testParent = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $testProcess.ParentProcessId) -ErrorAction SilentlyContinue
                    $belongsToProject = $testParent -and $testParent.CommandLine -match 'spring-boot:test-run' -and $testParent.CommandLine.IndexOf($projectRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0
                }
                $isTestServer = ($testProcess.Name -eq 'java.exe' -and $testProcess.CommandLine -match 'cn.erp.ErpApplication') -or ($testProcess.Name -eq 'node.exe' -and $testProcess.CommandLine -match 'vite')
                if ($belongsToProject -and $isTestServer) { Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue }
            }
        }
    }
    exit $status
}
$javaCompiler = if ([IO.Path]::DirectorySeparatorChar -eq '\') { 'bin/javac.exe' } else { 'bin/javac' }
if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME $javaCompiler))) { throw '请在 .env 中配置 JDK 21 的 JAVA_HOME。' }
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
if ($Action -eq 'verify-ai') {
    Set-ErpTestJavaStorage $testRoot 'erp-ai'
    Push-Location (Join-Path $projectRoot 'erp-ai')
    try { mvn clean verify; $status = $LASTEXITCODE } finally { Pop-Location }
    exit $status
}
if ($env:DB_NAME -and $env:DB_NAME -eq $env:TEST_DB_NAME) {
    throw '开发库和测试库必须使用不同的数据库。请核对 DB_NAME 和 TEST_DB_NAME。'
}
if ($Action -in 'e2e-api','verify' -and (!$env:TEST_DB_NAME -or $env:TEST_DB_NAME -notmatch '^erp_test(?:_[a-z0-9]+)*$')) {
    throw '测试库必须独立于开发库，名称使用 erp_test 或 erp_test_环境名。'
}
$workingDatabase = if ($Action -eq 'server') { $env:DB_NAME } else { $env:TEST_DB_NAME }
if (!$workingDatabase) { throw '请在 .env 中配置当前工作树的数据库名称。' }
Assert-IsolatedWorktreeDatabase $workingDatabase
if ($testRoot -and $Action -in 'verify','e2e-api') {
    Assert-ErpTestMySqlStorage
    Set-ErpTestJavaStorage $testRoot 'erp-server'
}
Push-Location (Join-Path $projectRoot 'erp-server')
try {
    if ($Action -eq 'e2e-api') {
        if (!$env:ERP_E2E_PASSWORD -or $env:ERP_E2E_TENANT_CODE -notmatch '^E2E-[a-f0-9]{12}$') {
            throw '请使用 project.ps1 e2e 启动测试，以准备独立的测试企业。'
        }
        $env:ERP_DEV_ADMIN_PASSWORD = $env:ERP_E2E_PASSWORD
        $env:ERP_DEV_TENANT_CODE = $env:ERP_E2E_TENANT_CODE
        $env:APP_ENV = 'e2e'
        $env:SPRING_PROFILES_ACTIVE = 'dev,e2e'
        $env:SERVER_PORT = '18080'
        $env:ERP_NODE_ID = '1'
        $env:DB_NAME = $env:TEST_DB_NAME
        $env:DB_USER = $env:TEST_DB_USER
        $env:DB_PASSWORD = $env:TEST_DB_PASSWORD
        mvn spring-boot:test-run '-Dspring-boot.run.main-class=cn.erp.ErpApplication'
    } elseif ($Action -eq 'server') {
        $env:SPRING_PROFILES_ACTIVE = 'dev'
        mvn spring-boot:run
    } else {
        mvn clean verify
    }
    $status = $LASTEXITCODE
} finally { Pop-Location }
exit $status
