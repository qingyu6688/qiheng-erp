"""按部署起始预算读取资源，报告仅包含容量数据。"""

import argparse
import ctypes
import json
import math
import os
import platform
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIB = 1024 * 1024


def capacity_number(value, name, allow_zero=False):
    if (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
            or value < 0 or (value == 0 and not allow_zero)):
        raise ValueError(f"{name} 的容量值无效")
    return value


def read_inventory(path):
    inventory = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(inventory, dict):
        raise ValueError("主机采样必须为 JSON 对象")
    names = ("cpuCores", "totalMemoryMiB", "availableMemoryMiB", "freeDiskMiB")
    values = {name: capacity_number(inventory.get(name), name, name in ("availableMemoryMiB", "freeDiskMiB"))
              for name in names}
    if values["availableMemoryMiB"] > values["totalMemoryMiB"]:
        raise ValueError("可用内存超过总内存，请核对采样单位")
    return values


def windows_memory():
    class MemoryStatus(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                    *[(name, ctypes.c_ulonglong) for name in (
                        "total", "available", "pageTotal", "pageAvailable", "virtualTotal",
                        "virtualAvailable", "extendedAvailable")]]

    status = MemoryStatus()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        raise OSError("无法读取 Windows 内存数据")
    return status.total / MIB, status.available / MIB


def linux_resources(cpu, total, available, directory=None):
    # cgroup 的资源上限优先于宿主机资源，避免容器把宿主机容量当作自身额度。
    directory = directory or Path("/sys/fs/cgroup")
    if (directory / "cpu.max").is_file():
        quota, period = (directory / "cpu.max").read_text().split()
        if quota != "max":
            cpu = min(cpu, int(quota) / int(period))
    if (directory / "cpuset.cpus.effective").is_file():
        ranges = (directory / "cpuset.cpus.effective").read_text().strip().split(",")
        cores = sum(int(part.split("-")[-1]) - int(part.split("-")[0]) + 1 for part in ranges if part)
        if cores:
            cpu = min(cpu, cores)
    if (directory / "memory.max").is_file():
        maximum = (directory / "memory.max").read_text().strip()
        if maximum != "max":
            limit = int(maximum) / MIB
            used = int((directory / "memory.current").read_text()) / MIB
            total, available = min(total, limit), min(available, max(0, limit - used))
    return cpu, total, available


def linux_cgroup_directories():
    root = Path("/sys/fs/cgroup").resolve()
    membership = next((line.split(":", 2)[2] for line in Path("/proc/self/cgroup").read_text().splitlines()
                       if line.startswith("0::")), None)
    if membership is None:
        raise ValueError("当前 Linux cgroup 版本请使用采样 JSON 检查")
    current = (root / membership.lstrip("/")).resolve()
    if not current.is_relative_to(root) or not current.is_dir():
        raise ValueError("无法核对当前进程的 cgroup 资源范围，请使用采样 JSON")
    return [current, *[parent for parent in current.parents if parent.is_relative_to(root)]]


def collect_inventory(directory):
    cpu = os.cpu_count() or 1
    if platform.system() == "Windows":
        total, available = windows_memory()
    elif platform.system() == "Linux":
        entries = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        total = int(entries["MemTotal"].split()[0]) / 1024
        available = int(entries["MemAvailable"].split()[0]) / 1024
        if hasattr(os, "sched_getaffinity"):
            cpu = min(cpu, len(os.sched_getaffinity(0)))
        for group in linux_cgroup_directories():
            cpu, total, available = linux_resources(cpu, total, available, group)
    else:
        raise ValueError("当前系统请使用 --inventory 提供采样后的资源数据")
    return {"cpuCores": cpu, "totalMemoryMiB": math.floor(total),
            "availableMemoryMiB": math.floor(available),
            "freeDiskMiB": shutil.disk_usage(directory).free // MIB}


def evaluate(profile, inventory, required_disk):
    thresholds = [("cpuCores", profile["cpuCores"], "已分配 CPU 核数"),
                  ("totalMemoryMiB", profile["hostMemoryMiB"], "主机总内存 MiB"),
                  ("availableMemoryMiB", profile["newWorkloadMemoryMiB"], "启动前可用内存 MiB")]
    if required_disk is not None:
        thresholds.append(("freeDiskMiB", required_disk, "演练所需空闲空间 MiB"))
    checks = [{"item": label, "actual": inventory[key], "budget": minimum,
               "met": inventory[key] >= minimum} for key, minimum, label in thresholds]
    return {"budgetMet": all(check["met"] for check in checks), "checks": checks,
            "diskBudgetProvided": required_disk is not None,
            "pending": ["目标数据容量和关键接口压测", "CPU 峰值与持续使用率", "故障恢复与切换演练"]
            + ([] if required_disk is not None else ["升级备份、恢复与对象仓的峰值空间预算"])}


def main(argv=None):
    profiles = json.loads((ROOT / "deploy/resource-profiles.json").read_text(encoding="utf-8"))["profiles"]
    parser = argparse.ArgumentParser(description="按部署起始预算检查本机或私有采样数据")
    parser.add_argument("--profile", choices=profiles, required=True)
    parser.add_argument("--inventory", type=Path, help="私有主机采样 JSON")
    parser.add_argument("--data-path", type=Path, default=ROOT, help="待部署的数据分区，用于读取空闲空间")
    parser.add_argument("--required-free-disk-mib", type=float)
    parser.add_argument("--output", type=Path, help="可选的私有报告路径")
    args = parser.parse_args(argv)
    try:
        disk = args.required_free_disk_mib
        if disk is not None:
            capacity_number(disk, "所需空闲空间")
        inventory = read_inventory(args.inventory) if args.inventory else collect_inventory(args.data_path)
        profile = profiles[args.profile]
        report = {"schemaVersion": 1, "checkedAt": datetime.now(timezone.utc).isoformat(),
                  "profile": args.profile, "label": profile["label"], "inventory": inventory,
                  "servicesMiB": profile["services"], "excludedModules": profile["excludedModules"],
                  **evaluate(profile, inventory, disk)}
        payload = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(payload, encoding="utf-8")
        print(payload, end="")
        return int(not report["budgetMet"])
    except (OSError, ValueError, KeyError, TypeError):
        print("资源检查失败，请核对采样 JSON、资源单位、数据分区和输出目录。", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
