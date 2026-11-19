"""检查依赖或静态扫描结果，采集失败与高危发现均返回非零退出码。"""
import argparse
import json
from pathlib import Path
import sys


def dependency_findings(files):
    for file in files:
        report = json.loads(file.read_text(encoding="utf-8"))
        groups = report["Results"]
        if not any(group.get("Packages") for group in groups):
            raise ValueError(f"{file.name} 缺少已扫描的依赖")
        for group in groups:
            for item in group.get("Vulnerabilities") or ():
                if item["Severity"] in ("HIGH", "CRITICAL"):
                    yield f"{file.name}: {item['VulnerabilityID']} {item['PkgName']} {item['InstalledVersion']}"


def code_findings(files):
    for file in files:
        report = json.loads(file.read_text(encoding="utf-8"))
        if not report["runs"]:
            raise ValueError(f"{file.name} 缺少静态分析结果")
        for run in report["runs"]:
            components = [run["tool"]["driver"], *run["tool"].get("extensions", [])]
            severity = {
                rule["id"]: float(rule.get("properties", {}).get("security-severity", 0))
                for component in components for rule in component.get("rules", [])
            }
            for result in run["results"]:
                if severity[result["ruleId"]] >= 7:
                    yield f"{file.name}: {result['ruleId']}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("dependency", "code"))
    parser.add_argument("reports", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        scan = dependency_findings if args.kind == "dependency" else code_findings
        findings = list(scan(args.reports))
    except (OSError, KeyError, TypeError, ValueError) as error:
        print(f"安全报告无效：{error}", file=sys.stderr)
        return 1
    if findings:
        print("发现高危或严重问题：", file=sys.stderr)
        print("\n".join(findings), file=sys.stderr)
        return 1
    print("安全检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
