"""校验本机测试数据输出，并为自测准备临时目录。"""

import os
import re
import tempfile
from pathlib import Path


def checked_test_output(path):
    location = Path(path)
    if os.name == "nt":
        location = location.resolve()
        drive = location.drive.upper()
        if drive == "C:" or not re.fullmatch(r"[A-Z]:", drive):
            raise ValueError("测试数据必须写入本机非 C 盘，目录联接也不能指回 C 盘")
    return location


def temporary_test_directory(stage):
    configured = os.environ.get("ERP_TEST_ROOT", "").strip()
    if not configured:
        if os.name == "nt":
            raise ValueError("请设置 ERP_TEST_ROOT 指向非 C 盘，再运行本机自测")
        return tempfile.TemporaryDirectory()
    root = Path(configured)
    if not root.is_absolute():
        raise ValueError("ERP_TEST_ROOT 必须使用绝对路径")
    directory = checked_test_output(root / stage / "tmp")
    directory.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(dir=directory)
