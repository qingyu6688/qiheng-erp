#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JarFile,
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9_]{1,63}$')][string]$Database,
    [Parameter(Mandatory)][string]$TenantCode,
    [Parameter(Mandatory)][string]$TenantName,
    [Parameter(Mandatory)][string]$CompanyCode,
    [Parameter(Mandatory)][string]$CompanyName,
    [string]$AdminUsername = 'admin',
    [string]$AdminDisplayName = '系统管理员',
    [string]$SettingsFile,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$allowedKeys = 'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_SSL_MODE', 'DB_USER', 'DB_PASSWORD',
    'DB_MIGRATION_USER', 'DB_MIGRATION_PASSWORD', 'ERP_BOOTSTRAP_DB_TRUSTSTORE',
    'ERP_BOOTSTRAP_DB_TRUSTSTORE_PASSWORD'
$settings = @{}
if ($SettingsFile) {
    foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $SettingsFile).Path)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$' -and $matches[1] -in $allowedKeys) {
            $settings[$matches[1]] = $matches[2]
        }
    }
} else {
    foreach ($key in $allowedKeys) {
        $settings[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    }
}
if ($settings.DB_NAME -cne $Database) {
    throw '指定库名与数据库配置 DB_NAME 不一致。'
}
if ([bool]$settings.DB_MIGRATION_USER -xor [bool]$settings.DB_MIGRATION_PASSWORD) {
    throw '迁移账号及密码须成对配置。'
}
if ($settings.DB_MIGRATION_USER) {
    $dbUser = $settings.DB_MIGRATION_USER
    $dbPassword = $settings.DB_MIGRATION_PASSWORD
} else {
    $dbUser = $settings.DB_USER
    $dbPassword = $settings.DB_PASSWORD
}
if (!$dbUser -or !$dbPassword) {
    throw '请配置完整的数据库引导账号和密码。'
}
if (!$env:ERP_BOOTSTRAP_ADMIN_PASSWORD) {
    throw '请在当前进程的 ERP_BOOTSTRAP_ADMIN_PASSWORD 中提供临时口令。'
}

$javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
$java = if ($JavaHome) {
    (Resolve-Path -LiteralPath (Join-Path $JavaHome "bin/$javaName")).Path
} else {
    (Get-Command $javaName -CommandType Application | Select-Object -First 1).Source
}
$jar = (Resolve-Path -LiteralPath $JarFile).Path
$source = Join-Path $PSScriptRoot 'java/InitializeTenant.java'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar
)
$pathComparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$tempPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
$temporary = [IO.Path]::GetFullPath((Join-Path $tempPrefix ('erp-initialize-' + [Guid]::NewGuid().ToString('N'))))
if (!$temporary.StartsWith($tempPrefix, $pathComparison) -or
        [IO.Path]::GetFileName($temporary) -notmatch '^erp-initialize-[a-f0-9]{32}$') {
    throw '引导工具的临时目录路径异常。'
}
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $classes = Join-Path $temporary 'classes'
    $sharedClasses = Join-Path $classes 'cn/erp/shared'
    New-Item -ItemType Directory -Path $sharedClasses | Out-Null
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $classPathEntries = @($classes)
        foreach ($module in 'mysql-connector-j', 'spring-security-crypto') {
            $pattern = '^BOOT-INF/lib/' + [Regex]::Escape($module) + '-[0-9][A-Za-z0-9.-]*\.jar$'
            $entries = @($archive.Entries | Where-Object FullName -match $pattern)
            if ($entries.Count -ne 1) {
                throw "发布 JAR 中缺少可唯一识别的 $module 依赖。"
            }
            $target = Join-Path $temporary $entries[0].Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], $target, $false)
            $classPathEntries += $target
        }
        foreach ($class in 'Ids.class', 'ApiException.class') {
            $entry = $archive.GetEntry("BOOT-INF/classes/cn/erp/shared/$class")
            if (!$entry) {
                throw '发布 JAR 中缺少项目编号组件。'
            }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $sharedClasses $class), $false)
        }
    } finally {
        $archive.Dispose()
    }

    $launch = [Diagnostics.ProcessStartInfo]::new($java)
    $launch.UseShellExecute = $false
    $launch.CreateNoWindow = $true
    foreach ($argument in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
            '--class-path', ($classPathEntries -join [IO.Path]::PathSeparator),
            '--source', '21', $source, $Database, $TenantCode, $TenantName, $CompanyCode, $CompanyName,
            $AdminUsername, $AdminDisplayName)) {
        $launch.ArgumentList.Add($argument)
    }
    # Java 会回显通用 JVM 选项，专用信任库参数通过引导环境传递。
    foreach ($key in 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS') {
        $launch.Environment.Remove($key) | Out-Null
    }
    foreach ($key in 'DB_HOST', 'DB_PORT', 'DB_NAME', 'ERP_BOOTSTRAP_DB_TRUSTSTORE', 'ERP_BOOTSTRAP_DB_TRUSTSTORE_PASSWORD') {
        $launch.Environment[$key] = [string]$settings[$key]
    }
    $launch.Environment['DB_SSL_MODE'] = $settings.DB_SSL_MODE ?? 'REQUIRED'
    $launch.Environment['ERP_BOOTSTRAP_DB_USER'] = $dbUser
    $launch.Environment['ERP_BOOTSTRAP_DB_PASSWORD'] = $dbPassword
    $launch.Environment['ERP_BOOTSTRAP_ADMIN_PASSWORD'] = $env:ERP_BOOTSTRAP_ADMIN_PASSWORD
    # 引导使用单独的编号节点，避免部署时与 API 或 Worker 争用编号。
    $launch.Environment['ERP_NODE_ID'] = '1023'
    try {
        $process = [Diagnostics.Process]::Start($launch)
        $process.WaitForExit()
        $exitCode = $process.ExitCode
    } finally {
        if ($process) { $process.Dispose() }
        $launch.Environment.Remove('ERP_BOOTSTRAP_DB_PASSWORD') | Out-Null
        $launch.Environment.Remove('ERP_BOOTSTRAP_ADMIN_PASSWORD') | Out-Null
        $launch.Environment.Remove('ERP_BOOTSTRAP_DB_TRUSTSTORE_PASSWORD') | Out-Null
    }
} finally {
    if (Test-Path -LiteralPath $temporary -PathType Container) {
        $resolved = (Resolve-Path -LiteralPath $temporary).Path
        if (![string]::Equals($resolved, $temporary, $pathComparison) -or
                !$resolved.StartsWith($tempPrefix, $pathComparison) -or (Get-Item -LiteralPath $resolved).LinkType) {
            throw '引导工具的临时目录无法安全收尾。'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
exit $exitCode
