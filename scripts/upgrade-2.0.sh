#!/bin/sh
set -eu
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
exec "${ERP_UPGRADE_PYTHON:-python3}" -B -X utf8 "$script_dir/upgrade-2.0/upgrade.py" "$@"
