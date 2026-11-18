function Get-ErpTestRoot([string[]]$SettingsFiles) {
    $configured = $env:ERP_TEST_ROOT
    foreach ($file in $SettingsFiles) {
        if ($configured) { break }
        if (!$file -or !(Test-Path -LiteralPath $file -PathType Leaf)) { continue }
        $SettingsFile = (Resolve-Path -LiteralPath $file).Path
        foreach ($line in [IO.File]::ReadLines($SettingsFile)) {
            if ($line -match '^ERP_TEST_ROOT=(.*)$') { $configured = $matches[1].Trim().Trim('"',"'") }
        }
    }
    if (!$configured) { return }
    if (![IO.Path]::IsPathFullyQualified($configured)) { throw 'ERP_TEST_ROOT 必须使用绝对路径。' }
    $path = [IO.Path]::GetFullPath($configured)
    Assert-ErpTestOutputPath $path
    return $path
}

function Assert-ErpTestOutputPath([string]$Path) {
    if (!$IsWindows) { return }
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if ($current -match '^(?:(?:\\\\[?.]\\)?C:[\\/]|\\\\[?.]\\Volume\{)') {
            throw '测试数据输出不能放在 C 盘，请使用其他磁盘上的绝对路径。'
        }
        $entry = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($entry -and ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            $target = $entry.ResolveLinkTarget($true)
            if (!$target) { throw '无法确认测试目录联接的实际盘符。' }
            Assert-ErpTestOutputPath $target.FullName
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Set-ErpTestStorage([string]$Root, [string]$Stage) {
    if (!$Root) { return }
    $directory = Join-Path $Root $Stage
    $temp = Join-Path $directory 'tmp'
    Assert-ErpTestOutputPath $temp
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    $env:ERP_TEST_ROOT = $Root
    $env:TEMP = $temp
    $env:TMP = $temp
    $env:TMPDIR = $temp
    $env:ERP_HTTP_LOG_FILE = Join-Path $directory 'http.log'
    $env:ERP_STORAGE_LOCAL_ROOT = Join-Path $directory 'objects'
    $env:ERP_AI_KB_STORAGE = Join-Path $directory 'knowledge'
}

function Set-ErpTestJavaStorage([string]$Root, [string]$Module) {
    if (!$Root) { return }
    $temp = Join-Path $Root "$Module/tmp"
    Assert-ErpTestOutputPath $temp
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    $env:JDK_JAVA_OPTIONS = ('{0} "-Djava.io.tmpdir={1}"' -f $env:JDK_JAVA_OPTIONS, $temp).Trim()
}

function Assert-ErpTestMySqlStorage {
    if (!$IsWindows -or !$env:ERP_TEST_ROOT -or $env:DB_HOST -notin '',$null,'localhost','127.0.0.1','::1') { return }
    $mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if (!$mysql) { throw '请把 mysql.exe 加入 PATH，以便在测试前核对 MySQL 的数据和临时目录。' }
    $server = if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }
    $port = if ($env:DB_PORT) { $env:DB_PORT } else { '3306' }
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $env:TEST_DB_PASSWORD
        $paths = & $mysql.Source --no-defaults --batch --skip-column-names --raw --protocol=tcp "--host=$server" "--port=$port" "--user=$env:TEST_DB_USER" --connect-timeout=5 --execute="SELECT @@datadir, @@tmpdir, COALESCE(@@log_bin_basename, '')"
        if ($LASTEXITCODE -ne 0 -or !$paths) { throw '无法核对隔离测试 MySQL 的存储目录。' }
        foreach ($path in ($paths -split '[\t;]' | Where-Object { $_ })) { Assert-ErpTestOutputPath $path }
    } finally { $env:MYSQL_PWD = $previousPassword }
}
