import json
import re
from pathlib import Path

from engine import UpgradeError, identifier, literal


APPENDED_TABLES = {
    "audit_log", "outbox_event", "wf_model", "wf_model_version", "wf_business_binding",
    "wf_instance", "wf_task_ext", "wf_task_action", "wf_copy", "wf_delay",
    "notify_message", "notify_template", "job_definition", "gl_standard_preset",
    "ledger_account", "gl_subject", "gl_business_mapping",
}
TRANSFORMED_PERMISSIONS = {"sys_permission", "sys_role_permission"}
HERE = Path(__file__).resolve().parent


def registry(name):
    return [line.split("\t") for line in (HERE / name).read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")]


def permission_codes():
    result = {}
    for fields in registry("03-permission-codes.tsv"):
        if len(fields) != 2 or any(not re.fullmatch(r"[a-z][a-z0-9_]*:[a-z][a-z0-9_]*", code)
                                    for code in fields):
            raise UpgradeError("权限码映射格式无效")
        old, new = fields
        if old == new or old in result:
            raise UpgradeError("权限码映射存在重复")
        result[old] = new
    if result.keys() & set(result.values()):
        raise UpgradeError("权限码映射不能串联或成环")
    return result


def retired_calls(mysql, database):
    calls = []
    for fields in registry("retired-api.tsv"):
        if len(fields) != 2 or fields[0] not in {"GET", "POST", "PUT", "PATCH", "DELETE"} \
                or not fields[1].startswith("/api/v1/"):
            raise UpgradeError("废弃接口清单格式无效")
        method, route = fields
        rows = mysql.query("""
            SELECT COALESCE(tenant_id,0),COALESCE(company_id,0),COALESCE(user_id,0),COUNT(*),MAX(created_at)
            FROM sys_api_access_log WHERE created_at>=UTC_TIMESTAMP(3)-INTERVAL 30 DAY AND request_method=
            """ + literal(method) + " AND route_pattern=" + literal(route) + """
            GROUP BY tenant_id,company_id,user_id ORDER BY tenant_id,company_id,user_id
            """, database)
        calls.extend({"method": method, "route": route, "tenantId": row[0], "companyId": row[1],
                      "userId": row[2], "calls": int(row[3]), "lastAt": row[4]} for row in rows)
    return calls


def running_work(mysql, database):
    queries = {
        "importJob": "SELECT COUNT(*) FROM import_job WHERE status='running'",
        "exportJob": "SELECT COUNT(*) FROM export_job WHERE status='running'",
        "managedJob": "SELECT COUNT(*) FROM job_run_log WHERE status='running' AND lease_until>=UTC_TIMESTAMP(3)",
        "managedLease": "SELECT COUNT(*) FROM job_definition WHERE lease_until>=UTC_TIMESTAMP(3)",
        "outboxLease": "SELECT COUNT(*) FROM outbox_event WHERE status='processing' AND lease_until>=UTC_TIMESTAMP(3)",
    }
    return {key: int(mysql.scalar(sql, database)) for key, sql in queries.items()}


def attachment_check(mysql, database, objects):
    count = 0
    for key, size, sha in mysql.query("""
            SELECT storage_key,size_bytes,sha256 FROM attachment WHERE status='active' ORDER BY id
            """, database):
        if objects.get(key) != {"size": int(size), "sha256": sha.lower()}:
            raise UpgradeError("对象仓缺少有效附件，或其大小及摘要与数据库不符")
        count += 1
    return count


def capture_table_fingerprints(mysql, database):
    tables = mysql.tables(database)
    actual = mysql.fingerprints(database, tables)
    return {table: {**meta, **actual[table]} for table, meta in tables.items()}


def verify_copy(mysql, database, original):
    if capture_table_fingerprints(mysql, database) != original:
        raise UpgradeError("恢复库的表、行数或数据摘要与停写点不一致")


def verify_business(mysql, source, target, original):
    baseline, remapped_baseline, queries = {}, {}, {}
    for old, old_meta in original.items():
        if old.lower().startswith("act_") or old in TRANSFORMED_PERMISSIONS | {"flyway_schema_history"}:
            continue
        table = "org_user_data_scope" if old == "sys_user_data_scope" else old
        columns, key = old_meta["columns"], old_meta["key"]
        condition = ""
        if old in APPENDED_TABLES:
            if not key:
                raise UpgradeError("需要比对的追加表缺少主键：" + old)
            same_key = " AND ".join("existing." + identifier(col) + "=" + identifier(table) + "."
                                    + identifier(col) for col in key)
            condition = ("WHERE EXISTS (SELECT 1 FROM " + identifier(source) + "." + identifier(old)
                         + " existing WHERE " + same_key + ")")
        expected = {"count": old_meta["count"], "sha256": old_meta["sha256"]}
        changed = {"wf_instance_id"}
        if old == "open_app":
            changed = {"scopes"}
        if old == "job_definition":
            changed = {"enabled", "updated_at", "version"}
        if old == "notify_message":
            changed = {"state", "updated_at"}
        if changed.intersection(columns):
            columns = [col for col in columns if col not in changed]
            remapped_baseline[old] = {"columns": columns, "key": key}
        baseline[table] = expected
        queries[table] = {"columns": columns, "key": key, "condition": condition}
    if remapped_baseline:
        baseline.update(mysql.fingerprints(source, remapped_baseline))
    actual = mysql.fingerprints(target, queries)
    for table, expected in baseline.items():
        if actual[table] != expected:
            raise UpgradeError("升级后存在未预期的数据差异：" + table)
    return actual


def normalize(codes, mapping):
    return sorted({mapping.get(code, code) for code in codes})


def apps(mysql, database):
    return {(tenant, app): json.loads(scopes) for tenant, app, scopes in mysql.query(
        "SELECT tenant_id,id,scopes FROM open_app ORDER BY tenant_id,id", database)}


def grants(mysql, database):
    return {tuple(row) for row in mysql.query("""
        SELECT rp.tenant_id,rp.role_id,p.code FROM sys_role_permission rp
        JOIN sys_permission p ON p.id=rp.permission_id ORDER BY rp.tenant_id,rp.role_id,p.code
        """, database)}


def convert_permissions(mysql, database, mapping):
    sql = ["START TRANSACTION"]
    app_updates = 0
    for old, new in mapping.items():
        if mysql.scalar("SELECT COUNT(*) FROM sys_permission WHERE code=" + literal(new), database) != "1":
            raise UpgradeError("权限目录没有唯一的新权限码：" + new)
        sql.extend([
            "SET @legacy=(SELECT id FROM sys_permission WHERE code=" + literal(old) + ")",
            "SET @current=(SELECT id FROM sys_permission WHERE code=" + literal(new) + ")",
            """UPDATE sys_role_permission rp LEFT JOIN sys_role_permission current_link
                ON current_link.tenant_id=rp.tenant_id AND current_link.role_id=rp.role_id
                   AND current_link.permission_id=@current
                SET rp.permission_id=@current WHERE rp.permission_id=@legacy AND current_link.id IS NULL""",
            "DELETE FROM sys_role_permission WHERE permission_id=@legacy",
            "DELETE FROM sys_permission WHERE id=@legacy",
        ])
    for (tenant, app), scopes in apps(mysql, database).items():
        changed = normalize(scopes, mapping)
        if set(changed) != set(scopes) or len(changed) != len(scopes):
            sql.append("UPDATE open_app SET updated_at=updated_at,scopes=" + literal(json.dumps(changed))
                       + " WHERE tenant_id=" + str(int(tenant)) + " AND id=" + str(int(app)))
            app_updates += 1
    sql.append("COMMIT")
    mysql.execute(";\n".join(sql) + ";", database)
    return {"mapping": mapping, "applicationsUpdated": app_updates}


def compare_permissions(mysql, source, target, mapping):
    original = {(tenant, role, mapping.get(code, code)) for tenant, role, code in grants(mysql, source)}
    actual = grants(mysql, target)
    legacy_catalog = {mapping.get(code, code) for (code,) in mysql.query("SELECT code FROM sys_permission", source)}
    if original != {grant for grant in actual if grant[2] in legacy_catalog}:
        raise UpgradeError("旧角色对既有权限的授权出现差异")
    if any(code in mapping for _, _, code in actual):
        raise UpgradeError("目标库仍有废弃权限码授权")
    original_apps = {key: normalize(value, mapping) for key, value in apps(mysql, source).items()}
    current_apps = {key: sorted(value) for key, value in apps(mysql, target).items()}
    if original_apps != current_apps:
        raise UpgradeError("开放平台应用的授权范围出现差异")
    return {"legacyGrants": len(original), "openApplications": len(original_apps),
            "addedGrants": sorted(actual - original)}


def compare_scopes(before, after):
    left = {(row["tenantId"], row["companyId"], row["userId"]): row for row in before}
    right = {(row["tenantId"], row["companyId"], row["userId"]): row for row in after}
    if left.keys() != right.keys():
        raise UpgradeError("公司授权范围发生变化")
    differences = []
    for key in left:
        old, new = left[key], right[key]
        if any(old[field] != new[field] for field in ("active", "allWarehouses", "warehouseIds", "departments")):
            differences.append({"scope": key, "field": "组织范围"})
        old_docs, new_docs = old["documents"], new["documents"]
        if any(old_docs[doc] != new_docs.get(doc) for doc in old_docs):
            differences.append({"scope": key, "field": "可见单据"})
        if any(result["count"] for doc, result in new_docs.items() if doc not in old_docs):
            differences.append({"scope": key, "field": "新增单据类型的可见记录"})
    return {"before": before, "after": after, "differences": differences}
