"""核对单个租户的封存链，输出可保存在独立存储的检查点。"""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import re
import subprocess

from engine import MySql, UpgradeError, load_json, read_settings

GENESIS = "0" * 64
HASH = re.compile(r"[0-9a-f]{64}")


def verify(rows, tenant, anchor=None):
    previous = GENESIS
    batch = 0
    records = 0
    anchored = anchor is None
    for number, group in itertools.groupby(rows, key=lambda row: row[0]):
        batch += 1
        if int(number) != batch:
            raise UpgradeError(f"租户 {tenant} 的封存链缺少批次 {batch}")
        digest = hashlib.sha256(f"qiheng-audit-v1:{tenant}:{batch}:{previous}\n".encode("ascii"))
        count = 0
        for _, size, parent, expected, record, row_hash in group:
            if parent != previous or not HASH.fullmatch(row_hash):
                raise UpgradeError(f"租户 {tenant} 批次 {batch} 的链头或审计记录无效")
            digest.update(f"{record}:{row_hash}\n".encode("ascii"))
            count += 1
        previous = digest.hexdigest()
        if count != int(size) or previous != expected:
            raise UpgradeError(f"租户 {tenant} 批次 {batch} 的成员数或摘要不一致")
        records += count
        if anchor and batch == anchor["batch"]:
            anchored = previous == anchor["hash"]
    if not anchored:
        raise UpgradeError(f"租户 {tenant} 现有封存链与外部检查点不一致")
    return {"tenant": str(tenant), "batch": batch, "hash": previous, "records": records}


def inspect(mysql, database, tenant, anchor):
    # 一条流式查询固定读视图，避免验证过程中并发封存造成假差异。
    sql = f"""
        SELECT s.batch_no,s.row_count,s.previous_hash,s.batch_hash,m.audit_id,a.row_hash
        FROM audit_seal s
        LEFT JOIN audit_seal_member m ON m.tenant_id=s.tenant_id AND m.batch_no=s.batch_no
        LEFT JOIN audit_seal_source a ON a.tenant_id=m.tenant_id AND a.audit_id=m.audit_id
        WHERE s.tenant_id={tenant} ORDER BY s.batch_no,m.audit_id
    """
    with subprocess.Popen([*mysql.command(database), "--quick", "--execute=" + sql],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          encoding="utf-8", env=mysql.environment) as reader:
        try:
            checkpoint = verify((line.rstrip("\r\n").split("\t") for line in reader.stdout), tenant, anchor)
            if reader.wait(timeout=10):
                raise UpgradeError("读取封存记录失败，请核对数据库连接和只读权限")
        finally:
            if reader.poll() is None:
                reader.kill()
    checkpoint["unsealed"] = int(mysql.scalar(f"""
        SELECT COUNT(*) FROM audit_log a
        WHERE a.tenant_id={tenant} AND NOT EXISTS (
            SELECT 1 FROM audit_seal_member m WHERE m.tenant_id=a.tenant_id AND m.audit_id=a.id)
        """, database))
    return checkpoint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, required=True)
    parser.add_argument("--tenant", type=int, required=True)
    parser.add_argument("--anchor", type=Path, help="独立存储中的上一次检查点")
    parser.add_argument("--mysql", default="mysql")
    args = parser.parse_args()
    try:
        if not 0 < args.tenant < 2**63:
            raise UpgradeError("租户编号无效")
        anchor = load_json(args.anchor) if args.anchor else None
        if anchor is not None and (not isinstance(anchor, dict)
                                   or anchor.get("tenant") != str(args.tenant)
                                   or type(anchor.get("batch")) is not int
                                   or not 0 < anchor["batch"] < 2**63
                                   or not HASH.fullmatch(str(anchor.get("hash", "")))):
            raise UpgradeError("外部检查点格式或租户不匹配")
        settings = read_settings(args.settings)
        mysql = MySql(settings, args.mysql, None)
        checkpoint = inspect(mysql, settings["DB_NAME"], args.tenant, anchor)
    except (UpgradeError, OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, f"审计封存核验失败：{error}\n")
    print(json.dumps(checkpoint, ensure_ascii=False))


if __name__ == "__main__":
    main()
