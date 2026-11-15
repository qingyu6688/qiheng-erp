#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][ValidatePattern('^erp_restore_[a-z0-9_]{1,40}$')][string]$Database,
    [Parameter(Mandatory)][string]$JarFile,
    [string]$JavaHome='E:\JDK21',
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$output=if($OutputDirectory){[IO.Path]::GetFullPath($OutputDirectory)}else{Join-Path $root ('.local/recovery-check-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
$settingsPath=(Resolve-Path -LiteralPath $SettingsFile).Path
if(Test-Path -LiteralPath $output){throw '恢复核验输出目录已存在。'}
New-Item -ItemType Directory -Path $output | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarFile))
try{
 foreach($entry in $archive.Entries){
  if(!$entry.FullName.StartsWith('BOOT-INF/classes/') -and !$entry.FullName.StartsWith('BOOT-INF/lib/')){continue}
  if($entry.FullName.EndsWith('/')){continue}
  $target=[IO.Path]::GetFullPath((Join-Path $output $entry.FullName))
  if(!$target.StartsWith([IO.Path]::GetFullPath($output)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'JAR资源路径越界。'}
  New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
  [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$false)
 }
}finally{$archive.Dispose()}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'java/RecoveryVerifier.java') -Destination $output
$java=(Resolve-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe')).Path
$javac=(Resolve-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe')).Path
# 相对类路径可直接在中文解包目录运行，避免依赖开发机专用的英文链接。
$classpath='BOOT-INF/classes;BOOT-INF/lib/*'
Push-Location -LiteralPath $output
try{
 & $javac -encoding UTF-8 -cp $classpath -d . RecoveryVerifier.java
 if($LASTEXITCODE -ne 0){throw '恢复对账检查编译失败。'}
 & $java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp ('.;'+$classpath) RecoveryVerifier $settingsPath $Database 'reconciliation-result.json'
 if($LASTEXITCODE -ne 0){throw '恢复库对账未通过。'}
}finally{Pop-Location}
