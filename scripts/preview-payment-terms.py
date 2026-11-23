"""只读查询指定公司的未清往来，生成到期日回填预览与待审核 SQL。"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def positive(value: object) -> int:
    text = str(value)
    if not re.fullmatch(r"[1-9][0-9]{0,18}", text):
        raise ValueError("租户、公司与记录编号必须为正整数")
    return int(text)


def preview_query(tenant: int, company: int) -> str:
    tenant, company = positive(tenant), positive(company)
    queries = []
    for fact, party, role, direction in [
        ("receivable", "customer_id", "customer", "D"),
        ("payable", "supplier_id", "supplier", "C"),
    ]:
        term_type = "COALESCE(p.term_type,c.default_term_type)"
        term_value = "IF(p.term_type IS NOT NULL,p.term_value,c.default_term_value)"
        due = (f"CASE {term_type} WHEN 'days' THEN DATE_ADD(r.business_date,INTERVAL {term_value} DAY) "
               f"WHEN 'monthly' THEN DATE_ADD(DATE_ADD(LAST_DAY(r.business_date),INTERVAL 1 DAY),INTERVAL ({term_value}-1) DAY) END")
        queries.append(f"""
            SELECT JSON_OBJECT('fact','{fact}','id',CAST(r.id AS CHAR),
              'tenantId',CAST(r.tenant_id AS CHAR),'companyId',CAST(r.company_id AS CHAR),
              'version',r.version,'businessDate',r.business_date,'openAmount',o.open_amount,
              'termType',{term_type},'termValue',{term_value},'dueDate',{due})
            FROM {fact} r JOIN partner_open_item o ON o.tenant_id=r.tenant_id
              AND o.company_id=r.company_id AND o.item_type='{fact}' AND o.source_id=r.id
            LEFT JOIN partner_role p ON p.tenant_id=r.tenant_id AND p.partner_id=r.{party}
              AND p.role='{role}' AND p.status='active'
            LEFT JOIN company_policy c ON c.tenant_id=r.tenant_id AND c.company_id=r.company_id
            WHERE r.tenant_id={tenant} AND r.company_id={company} AND r.kind='charge'
              AND r.posting_action='post' AND r.due_date IS NULL AND o.direction='{direction}' AND o.open_amount>0
            ORDER BY r.id
        """)
    return ";\n".join(queries) + ";\n"


def render_sql(rows: list[dict[str, object]]) -> str:
    statements = ["-- 仅供财务和管理员审核后手工执行；本脚本从不执行更新。",
                  "-- 每条更新保护原版本与空到期日。执行后逐条核对影响行数，确认无误再 COMMIT。",
                  "START TRANSACTION;"]
    for row in rows:
        fact = row["fact"]
        if fact not in {"receivable", "payable"}:
            raise ValueError("回填记录类型无效")
        if row.get("dueDate") is None:
            continue
        due = str(row["dueDate"])
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", due):
            raise ValueError("到期日格式无效")
        version = int(str(row["version"]))
        if version < 0:
            raise ValueError("记录版本无效")
        statements.append(
            f"UPDATE {fact} SET due_date='{due}',version=version+1 "
            f"WHERE tenant_id={positive(row['tenantId'])} AND company_id={positive(row['companyId'])} "
            f"AND id={positive(row['id'])} AND version={version} AND due_date IS NULL "
            f"AND kind='charge' AND posting_action='post' AND EXISTS(SELECT 1 FROM partner_open_item o "
            f"WHERE o.tenant_id={fact}.tenant_id AND o.company_id={fact}.company_id "
            f"AND o.source_id={fact}.id AND o.item_type='{fact}' AND o.open_amount>0);")
        statements.append("SELECT ROW_COUNT() AS affected_rows;")
    statements.extend(["-- 确认影响行数均为 1 后手工执行 COMMIT；有差异则执行 ROLLBACK。",
                       "-- COMMIT;", ""])
    return "\n".join(statements)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tenant", type=int, required=True)
    parser.add_argument("--company", type=int, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--mysql", default="mysql")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]+", args.database):
        parser.error("数据库名称只能包含字母、数字和下划线")
    if not os.environ.get("DB_USER") or not os.environ.get("DB_PASSWORD"):
        parser.error("请在当前进程环境中设置 DB_USER、DB_PASSWORD")
    args.output_dir.mkdir(parents=True, exist_ok=False)
    query = preview_query(args.tenant, args.company)
    environment = dict(os.environ, MYSQL_PWD=os.environ["DB_PASSWORD"])
    result = subprocess.run([args.mysql, "--batch", "--raw", "--skip-column-names",
                             "--default-character-set=utf8mb4", "--protocol=TCP",
                             "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
                             "--port=" + os.environ.get("DB_PORT", "3306"),
                             "--user=" + os.environ["DB_USER"], args.database],
                            input=query, capture_output=True, text=True, encoding="utf-8", env=environment,
                            timeout=120, check=False)
    if result.returncode:
        raise RuntimeError("只读查询失败，请检查数据库连接和查询权限；未执行任何更新")
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    (args.output_dir / "到期日回填预览.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.output_dir / "回填到期日.sql").write_text(render_sql(rows), encoding="utf-8")
    print(json.dumps({"records": len(rows), "proposed": sum(row.get("dueDate") is not None for row in rows),
                      "status": "已生成预览，未更新数据库"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
