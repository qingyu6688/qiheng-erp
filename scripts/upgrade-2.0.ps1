#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$interpreter = if ($env:ERP_UPGRADE_PYTHON) { $env:ERP_UPGRADE_PYTHON } else { 'python' }
& $interpreter -B -X utf8 (Join-Path $PSScriptRoot 'upgrade-2.0/upgrade.py') @args
exit $LASTEXITCODE
