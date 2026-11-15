#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SettingsFile,
    [Parameter(Mandatory)][ValidatePattern('^erp_restore_[a-z0-9_]{1,40}$')][string]$Database,
    [Parameter(Mandatory)][string]$JarFile,
    [string]$JavaHome='E:\JDK21'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$output=Join-Path $root ('.local/recovery-check-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $output | Out-Null
$nativeOutput=$output
if($output -match '[^\x00-\x7F]'){
 $alias='F:\projects\erp';$link=Get-Item -LiteralPath $alias
 if($link.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath(@($link.Target)[0]).TrimEnd('\') -ne [IO.Path]::GetFullPath($root).TrimEnd('\')){throw '需要已核验的英文项目联接运行Java恢复检查。'}
 $nativeOutput=Join-Path $alias ([IO.Path]::GetRelativePath($root,$output))
}
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
$classpath=(Join-Path $nativeOutput 'BOOT-INF/classes')+';'+(Join-Path $nativeOutput 'BOOT-INF/lib/*')
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d $nativeOutput (Join-Path $PSScriptRoot 'java/RecoveryVerifier.java')
if($LASTEXITCODE -ne 0){throw '恢复对账检查编译失败。'}
$result=Join-Path $output 'reconciliation-result.json'
& (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp ($nativeOutput+';'+$classpath) RecoveryVerifier (Resolve-Path -LiteralPath $SettingsFile).Path $Database $result
if($LASTEXITCODE -ne 0){throw '恢复库对账未通过。'}
