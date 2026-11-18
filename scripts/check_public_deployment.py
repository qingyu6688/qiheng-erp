"""检查公开文档中的部署地址，报错只输出文件和行号。"""

import ipaddress
import re
import subprocess
from collections.abc import Iterable, Iterator
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRIVATE_RECORD = "docs/服务器部署记录.md"
PRIVATE_PATHS = (
    ":(glob)**/.env", ":(glob)**/.env.*", ":(glob)**/.local/**", PRIVATE_RECORD,
    ":(exclude,glob)**/.env.example",
)
PUBLIC_PATHS = ("docs", "deploy", ".env.example", ".github/workflows", "README.md", "README.en.md")
TEXT_SUFFIXES = {
    ".md", ".env", ".yml", ".yaml", ".json", ".toml", ".ini", ".sh", ".ps1", ".py",
    ".example", ".conf", ".txt", ".properties", ".xml", ".sql", ".js", ".ts", ".html",
}
ADDRESS = re.compile(
    r"(?<![A-Za-z0-9_.])(?:\d{1,3}\.){3}\d{1,3}(?![A-Za-z0-9_.])"
    r"|(?<![A-Za-z0-9_:])(?=[0-9a-f:]*:[0-9a-f:]*:)[0-9a-f:]+(?![A-Za-z0-9_:])",
    re.IGNORECASE,
)
SCHEMA_VERSION = re.compile(r"schema\.version=\d+(?:\.\d+)+")
NEW_HUNK = re.compile(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def contains_public_address(line: str) -> bool:
    for match in ADDRESS.finditer(SCHEMA_VERSION.sub("", line)):
        try:
            ip = ipaddress.ip_address(match.group())
        except ValueError:
            continue
        if ip.is_global and not ip.is_multicast and not ip.is_reserved:
            return True
    return False


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8")


def historical_addresses(lines: Iterable[str]) -> Iterator[str]:
    commit = name = ""
    number = remaining = 0
    for line in lines:
        if remaining and line.startswith(("+", " ")):
            if name and line.startswith("+") and contains_public_address(line[1:]):
                yield f"{commit[:12]} {name}:{number} 历史提交中包含公网地址"
            number += 1
            remaining -= 1
        elif line.startswith("commit:"):
            commit = line.removeprefix("commit:").strip()
            name = ""
        elif line.startswith("diff --git "):
            name = ""
        elif line.startswith("+++ b/"):
            candidate = line.removeprefix("+++ b/").rstrip("\r\n\t")
            name = candidate if Path(candidate).suffix in TEXT_SUFFIXES else ""
        elif hunk := NEW_HUNK.match(line):
            number = int(hunk[1])
            remaining = int(hunk[2] or 1)


def scan_history() -> list[str]:
    command = [
        "git", "-c", "core.quotepath=false", "log", "--all", "--format=commit:%H",
        "--no-color", "--no-ext-diff", "--no-renames", "--diff-merges=first-parent",
        "--unified=0", "-p", "--", *PUBLIC_PATHS,
    ]
    with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, encoding="utf-8", errors="replace") as process:
        if process.stdout is None:
            raise RuntimeError("无法读取部署文档的 Git 历史")
        findings = list(dict.fromkeys(historical_addresses(process.stdout)))
        if process.wait() != 0:
            raise RuntimeError("部署文档的 Git 历史读取失败")
        return findings


def has_private_versions() -> bool:
    return bool(git("ls-files", "-z", "--", *PRIVATE_PATHS)
                or git("log", "--all", "-1", "--format=%h", "--", *PRIVATE_PATHS).strip())


def main() -> int:
    tracked = git("ls-files", "-z", "--", *PUBLIC_PATHS).split("\0")
    failures = []
    if has_private_versions():
        failures.append("私有环境配置、本机证据目录或私有部署记录已进入 Git 索引或提交历史")
    for name in filter(None, tracked):
        path = ROOT / name
        if not path.is_file() or path.suffix not in TEXT_SUFFIXES:
            continue
        lines = path.read_text(encoding="utf-8-sig").splitlines()
        for number, line in enumerate(lines, 1):
            if contains_public_address(line):
                failures.append(f"{name}:{number} 包含公网地址，请用文档保留地址或域名占位符")
    failures.extend(scan_history())
    if failures:
        print("\n".join(failures))
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
