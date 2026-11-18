"""在隔离容量库采集总账记账、事件积压和对账证据。"""

import argparse
import hashlib
import http.cookiejar
import json
import math
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

if __name__ == "__main__" and os.name == "nt":
    # 直接启动采集命令时，防止在检出目录生成自测路径模块的字节码缓存。
    sys.dont_write_bytecode = True

if __package__:
    from .local_test_storage import checked_test_output
else:
    from local_test_storage import checked_test_output

ROOT = Path(__file__).resolve().parents[2]
ID = re.compile(r"[1-9][0-9]{0,18}")
HASH = re.compile(r"[a-f0-9]{64}")
SOURCE = re.compile(r"[a-f0-9]{40}")
BASE = "http://127.0.0.1:19080/api/v1"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def positive_id(value):
    return isinstance(value, str) and bool(ID.fullmatch(value))


def period_number(period):
    require(isinstance(period, str) and re.fullmatch(r"[0-9]{6}", period), "期间必须使用 YYYYMM")
    year, month = int(period[:4]), int(period[4:])
    require(1 <= month <= 12 and year > 0, "期间月份无效")
    return year * 12 + month


def manifest_at(path):
    return validate_manifest(read_json(path))


def validate_manifest(manifest):
    require(isinstance(manifest, dict), "总账容量清单必须是对象")
    require(bool(re.fullmatch(r"[a-z0-9_-]{1,64}", manifest.get("dataset", ""))), "样本标识无效")
    require(bool(SOURCE.fullmatch(manifest.get("sourceCommit", ""))), "请记录完整源码提交")
    for key in ("artifactSha256", "environmentSha256"):
        require(bool(HASH.fullmatch(manifest.get(key, ""))), "请记录构建包和运行环境的 SHA256")
    for key in ("tenantId", "companyId"):
        require(positive_id(manifest.get(key)), "租户和公司编号必须是十进制字符串")
    require(bool(re.fullmatch(r"LOAD-[a-f0-9]{12}-[01]", manifest.get("tenantCode", ""))), "仅接受容量夹具企业")
    require(manifest.get("phase") in ("month01", "month12"), "请选择 month01 或 month12")
    periods = manifest.get("periods", [])
    require(isinstance(periods, list) and len(periods) == (1 if manifest["phase"] == "month01" else 12), "阶段与期间数量不符")
    require(all(isinstance(row, dict) and positive_id(row.get("id")) for row in periods), "期间编号无效")
    require(len({row["id"] for row in periods}) == len(periods), "期间编号重复")
    positions = [period_number(row.get("period")) for row in periods]
    require(all(right == left + 1 for left, right in zip(positions, positions[1:])), "样本期间必须连续并按业务月份排序")
    vouchers = manifest.get("postVouchers", [])
    require(isinstance(vouchers, list), "记账样本必须是数组")
    require(all(isinstance(row, dict) and positive_id(row.get("id")) and
                isinstance(row.get("version"), int) and row["version"] >= 0 for row in vouchers), "记账样本编号或版本无效")
    require(len({row["id"] for row in vouchers}) == len(vouchers), "记账样本重复")
    return manifest


def public_manifest(manifest):
    return {key: manifest[key] for key in (
        "dataset", "sourceCommit", "artifactSha256", "environmentSha256", "tenantId", "companyId",
        "tenantCode", "phase", "periods",
    )}


def local_settings(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        found = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
        if found:
            values[found[1]] = found[2]
    require(values.get("DB_HOST") in ("localhost", "127.0.0.1"), "总账容量采集仅允许本机 MySQL")
    return values


class Database:
    def __init__(self, settings, state, mysql):
        require(bool(re.fullmatch(r"erp_load_[a-z0-9]+(?:_[a-z0-9]+)*", state.get("database", ""))), "请使用独立 erp_load_ 容量库")
        port = settings.get("DB_PORT", "3306")
        require(port.isdigit() and 1 <= int(port) <= 65535, "MySQL 端口无效")
        require(settings.get("TEST_DB_USER"), "请配置独立的只读 TEST_DB_USER")
        self.environment = dict(os.environ, MYSQL_PWD=settings.get("TEST_DB_PASSWORD", ""))
        self.command = [
            mysql, "--no-defaults", "--protocol=TCP", "--host=" + settings["DB_HOST"], "--port=" + port,
            "--user=" + settings["TEST_DB_USER"], "--database=" + state["database"], "--batch", "--raw",
            "--skip-column-names", "--default-character-set=utf8mb4",
        ]

    def rows(self, statements):
        sql = "SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT;\n"
        sql += ";\n".join(statements) + ";\nCOMMIT;\n"
        try:
            result = subprocess.run(self.command, input=sql, capture_output=True, text=True,
                                    encoding="utf-8", env=self.environment, timeout=300, check=True)
        except (OSError, subprocess.SubprocessError) as error:
            raise RuntimeError("MySQL 只读采集失败，请检查独立库、只读权限和客户端路径") from error
        try:
            return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        except (ValueError, TypeError) as error:
            raise RuntimeError("MySQL 采集结果格式无效") from error


def scoped_queue_sql(manifest):
    tenant, company = manifest["tenantId"], manifest["companyId"]
    scope = f"e.tenant_id={tenant} AND e.event_type='gl.journal.requested' AND e.aggregate_type='gl_journal'"
    scope += f" AND EXISTS(SELECT 1 FROM journal j WHERE j.tenant_id=e.tenant_id AND j.id=e.aggregate_id AND j.company_id={company})"
    return [
        f"""SELECT JSON_OBJECT('created',COUNT(*),'done',COALESCE(SUM(e.status='done'),0))
            FROM outbox_event e WHERE {scope}""",
        f"""SELECT JSON_OBJECT('pending',COALESCE(SUM(e.status='pending'),0),
            'processing',COALESCE(SUM(e.status='processing'),0),'dead',COALESCE(SUM(e.status='dead'),0),
            'oldestSeconds',COALESCE(MAX(IF(e.status IN('pending','processing'),
                GREATEST(0,TIMESTAMPDIFF(MICROSECOND,e.created_at,UTC_TIMESTAMP(3)))/1000000,0)),0))
            FROM outbox_event e WHERE e.status IN('pending','processing','dead') AND {scope}""",
    ]


def snapshot(database, manifest):
    tenant, company = manifest["tenantId"], manifest["companyId"]
    timer = time.perf_counter()
    result = database.rows([
        f"""SELECT JSON_OBJECT('tenantCode',t.code,'companyId',CAST(c.id AS CHAR),'bookStatus',b.status,
            'databaseVersion',VERSION(),'bufferPoolBytes',@@innodb_buffer_pool_size,
            'flushLogAtCommit',@@innodb_flush_log_at_trx_commit,'syncBinlog',@@sync_binlog,
            'maxConnections',@@max_connections,
            'schemaVersion',(SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1))
            FROM company c JOIN tenant t ON t.id=c.tenant_id
            LEFT JOIN gl_account_book b ON b.tenant_id=c.tenant_id AND b.company_id=c.id
            WHERE c.tenant_id={tenant} AND c.id={company}""",
        f"""SELECT JSON_OBJECT('id',CAST(p.id AS CHAR),'period',p.period,'startDate',CAST(p.start_date AS CHAR),
            'endDate',CAST(p.end_date AS CHAR),'businessPosted',COALESCE(v.cnt,0),'allPosted',COALESCE(v.all_cnt,0),
            'linkedBusinessPosted',COALESCE(v.linked,0),
            'voucherLines',COALESCE(l.cnt,0),'balanceRows',COALESCE(b.cnt,0))
            FROM accounting_period p
            LEFT JOIN (SELECT period_id,COUNT(*) all_cnt,SUM(source='business') cnt,
                COUNT(DISTINCT IF(source='business',journal_id,NULL)) linked FROM gl_voucher
                WHERE tenant_id={tenant} AND company_id={company} AND is_deleted=0
                AND status IN('posted','reversed') GROUP BY period_id) v ON v.period_id=p.id
            LEFT JOIN (SELECT v.period_id,COUNT(*) cnt FROM gl_voucher v JOIN gl_voucher_line l
                ON l.tenant_id=v.tenant_id AND l.company_id=v.company_id AND l.voucher_id=v.id
                WHERE v.tenant_id={tenant} AND v.company_id={company} AND v.is_deleted=0
                AND v.status IN('posted','reversed') AND v.source='business' GROUP BY v.period_id) l ON l.period_id=p.id
            LEFT JOIN (SELECT period_id,COUNT(*) cnt FROM gl_balance
                WHERE tenant_id={tenant} AND company_id={company} GROUP BY period_id) b ON b.period_id=p.id
            WHERE p.tenant_id={tenant} AND p.company_id={company} ORDER BY p.start_date""",
    ])
    require(result and result[0].get("tenantCode") == manifest["tenantCode"] and result[0].get("bookStatus") == "active",
            "样本公司、容量企业或启用账套核验失败")
    return {"collectedAt": utc_now(), "queryElapsedMs": (time.perf_counter() - timer) * 1000,
            "identity": result[0], "periods": result[1:]}


class Api:
    def __init__(self, manifest, state):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.company = manifest["companyId"]
        login = self.request("POST", "/auth/login", {
            "tenantCode": manifest["tenantCode"], "username": manifest.get("username", "operator"),
            "password": state["password"],
        })
        require(login["status"] == 200, "容量临时账号登录失败")
        profile = self.request("GET", "/auth/me")
        require(profile["status"] == 200 and not profile["data"].get("mustChangePassword"), "容量临时账号资料不可用或需要改密")
        require(any(row.get("id") == self.company and row.get("allWarehouses") for row in profile["data"].get("companies", [])),
                "总账验收需要当前公司的完整数据范围")

    def request(self, method, path, value=None, key=None):
        headers = {"Content-Type": "application/json", "X-Company-Id": self.company}
        token = next((cookie.value for cookie in self.cookies if cookie.name == "XSRF-TOKEN"), None)
        if token:
            headers["X-XSRF-TOKEN"] = token
        if key:
            headers["Idempotency-Key"] = key
        payload = None if value is None else json.dumps(value).encode("utf-8")
        request = urllib.request.Request(BASE + path, payload, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=120)
        except urllib.error.HTTPError as response_error:
            response = response_error
        except (OSError, urllib.error.URLError) as error:
            raise RuntimeError("总账 REST 请求失败，请检查本机容量服务") from error
        with response:
            try:
                body = json.loads(response.read())
            except (ValueError, UnicodeError) as error:
                raise RuntimeError("总账 REST 响应格式无效") from error
            return {"status": response.status, "data": body.get("data"), "errorCode": body.get("errorCode")}


def post_samples(database, api, manifest, emit=lambda value: None):
    vouchers = manifest.get("postVouchers", [])
    require(len(vouchers) >= 100, "记账延迟需要至少100张已经复核且尚未记账的凭证")
    last_period = manifest["periods"][-1]["id"]
    evidence = {"kind": "posting", "manifest": public_manifest(manifest), "before": snapshot(database, manifest), "samples": []}
    for voucher in vouchers:
        path = "/gl/vouchers/" + voucher["id"]
        before = api.request("GET", path)
        require(before["status"] == 200 and before["data"].get("periodId") == last_period and
                before["data"].get("source") == "manual" and before["data"].get("status") == "approved" and
                before["data"].get("version") == voucher["version"],
                "记账样本期间、复核状态或版本不符")
        started = utc_now()
        timer = time.perf_counter()
        try:
            result = api.request("POST", path + "/post", {"version": voucher["version"]}, str(uuid4()))
            elapsed = (time.perf_counter() - timer) * 1000
            after = api.request("GET", path)
            after_data = after.get("data") or {}
            valid = result["status"] == 200 and after["status"] == 200 and after_data.get("status") == "posted"
        except RuntimeError:
            result, after_data, valid = {"status": 0, "errorCode": "SAMPLE_INCOMPLETE"}, {}, False
            elapsed = (time.perf_counter() - timer) * 1000
        sample = {"voucherId": voucher["id"], "startedAt": started, "elapsedMs": elapsed,
                  "periodId": last_period, "version": voucher["version"], "afterVersion": after_data.get("version"),
                  "httpStatus": result["status"], "errorCode": result["errorCode"], "posted": valid}
        evidence["samples"].append(sample)
        emit(sample)
        if not valid:
            break
    evidence["after"] = snapshot(database, manifest)
    return evidence


def watch_queue(database, manifest, steady_seconds, drain_seconds, interval, emit=lambda value: None):
    require(steady_seconds >= 60 and drain_seconds >= interval * 3 and 0 < interval <= 10,
            "持续采样至少60秒，收尾至少3个采样间隔，每次间隔最多10秒")
    evidence = {"kind": "queue", "manifest": public_manifest(manifest), "before": snapshot(database, manifest),
                "steadySeconds": steady_seconds, "drainSeconds": drain_seconds, "intervalSeconds": interval, "samples": []}
    started = time.monotonic()
    while True:
        elapsed = time.monotonic() - started
        if elapsed > steady_seconds + drain_seconds and evidence["samples"]:
            break
        timer = time.perf_counter()
        # ID 在提交前分配。用同一范围的累计数作差，才能计入迟提交的小 ID。
        created, queue = database.rows(scoped_queue_sql(manifest))
        sample = {"collectedAt": utc_now(), "elapsedSeconds": elapsed,
                  "queryElapsedMs": (time.perf_counter() - timer) * 1000,
                  "phase": "steady" if elapsed <= steady_seconds else "drain", **created, **queue}
        evidence["samples"].append(sample)
        emit(sample)
        time.sleep(interval)
    evidence["after"] = snapshot(database, manifest)
    return evidence


def reconcile(database, api, manifest, timeout, emit=lambda value: None):
    before = snapshot(database, manifest)
    results = []
    for check_type in ("gl_trial", "gl_operational"):
        result = api.request("POST", "/reconciliation-runs", {"checkType": check_type}, str(uuid4()))
        require(result["status"] == 202 and positive_id(result["data"].get("id")), "总账对账任务未成功创建")
        identifier = result["data"]["id"]
        emit({"type": check_type, "id": identifier, "requestedAt": utc_now()})
        started = time.monotonic()
        while True:
            state = api.request("GET", "/reconciliation-runs/" + identifier)
            require(state["status"] == 200, "无法读取总账对账任务")
            if state["data"].get("status") in ("passed", "failed", "error"):
                break
            require(time.monotonic() - started < timeout, "总账对账超时，请保留任务并检查原始日志")
            time.sleep(2)
        cursor, differences, visited = "", [], set()
        while True:
            page = api.request("GET", "/reconciliation-runs/" + identifier + "/diffs?limit=200" + cursor)
            require(page["status"] == 200 and isinstance(page["data"].get("items"), list), "总账差异分页读取失败")
            differences.extend(page["data"]["items"])
            next_cursor = page["data"].get("nextCursor")
            if next_cursor is None:
                break
            require(positive_id(next_cursor) and next_cursor not in visited, "对账差异分页未前进")
            visited.add(next_cursor)
            cursor = "&cursor=" + next_cursor
        check = {"type": check_type, "id": identifier, "status": state["data"]["status"],
                 "diffCount": state["data"].get("diffCount"), "differences": differences}
        results.append(check)
        emit(check)
    return {"kind": "reconciliation", "manifest": public_manifest(manifest), "before": before,
            "after": snapshot(database, manifest), "checks": results}


def percentile95(samples):
    values = sorted(row["elapsedMs"] for row in samples)
    return values[math.ceil(len(values) * .95) - 1]


def check_history(evidence, errors, expected_phase=None):
    manifest = evidence["manifest"]
    periods = manifest["periods"]
    expected = {row["id"]: row for row in periods}
    if expected_phase and manifest["phase"] != expected_phase:
        errors.append("记账阶段不符")
    for label in ("before", "after"):
        snapshot_ = evidence.get(label, {})
        actual = {row["id"]: row for row in snapshot_.get("periods", [])}
        identity = snapshot_.get("identity", {})
        if identity.get("bookStatus") != "active" or identity.get("tenantCode") != manifest["tenantCode"] or identity.get("companyId") != manifest["companyId"]:
            errors.append("样本账套未启用")
        for identifier, period in expected.items():
            row = actual.get(identifier, {})
            if row.get("period") != period["period"] or row.get("businessPosted", 0) < 100000 or row.get("allPosted", 0) < row.get("businessPosted", 0) or row.get("linkedBusinessPosted", 0) < 100000 or row.get("voucherLines", 0) < 200000 or row.get("balanceRows", 0) < 1:
                errors.append("每期10万张业务凭证及非空分录、余额样本不足")
                break
        if any(row.get("allPosted", 0) > 0 and identifier not in expected for identifier, row in actual.items()):
            errors.append("历史期间超过阶段清单，首末期样本不可比较")


def same_identity(left, right):
    keys = ("dataset", "sourceCommit", "artifactSha256", "environmentSha256", "tenantId", "companyId", "tenantCode")
    return all(left.get(key) == right.get(key) for key in keys)


def evaluate(first, last, queue, reconciliation, target_rate):
    require(isinstance(target_rate, (int, float)) and math.isfinite(target_rate) and target_rate > 0, "持续事件速率必须大于0")
    errors = []
    evidence = (first, last, queue, reconciliation)
    for row in evidence:
        validate_manifest(row["manifest"])
    if [row.get("kind") for row in evidence] != ["posting", "posting", "queue", "reconciliation"]:
        errors.append("缺少记账、持续队列或对账证据")
    if not all(same_identity(first["manifest"], row["manifest"]) for row in evidence[1:]):
        errors.append("样本、源码、构建、环境或公司身份不一致")
    identities = [row.get(stage, {}).get("identity") for row in evidence for stage in ("before", "after")]
    if not identities[0] or not all(identity == identities[0] for identity in identities[1:]):
        errors.append("实际数据库、迁移或持久化配置发生变化")
    check_history(first, errors, "month01")
    for row in evidence[1:]:
        check_history(row, errors, "month12")
    if first["manifest"]["periods"][0] != last["manifest"]["periods"][0]:
        errors.append("首末期不属于同一连续样本")
    p95 = []
    for posting in (first, last):
        samples = posting.get("samples", [])
        valid = len(samples) >= 100 and len({row.get("voucherId") for row in samples}) == len(samples)
        valid = valid and all(row.get("posted") is True and row.get("httpStatus") == 200 and
                              isinstance(row.get("elapsedMs"), (int, float)) and math.isfinite(row["elapsedMs"]) and
                              row["elapsedMs"] > 0 and row.get("periodId") == posting["manifest"]["periods"][-1]["id"] and
                              isinstance(row.get("version"), int) and row.get("afterVersion") == row["version"] + 1 for row in samples)
        if not valid:
            errors.append("记账采样不足100张、样本重复或出现失败")
            p95.append(None)
        else:
            p95.append(percentile95(samples))
    if all(value is not None for value in p95) and (p95[1] > p95[0] * 1.2 or max(p95) >= 1000):
        errors.append("首末期记账P95相差超过20%或单次记账P95达到1000毫秒")
    if {row.get("voucherId") for row in first.get("samples", [])} & {row.get("voucherId") for row in last.get("samples", [])}:
        errors.append("首末期复用了相同记账样本")
    samples = queue.get("samples", [])
    valid_queue = isinstance(samples, list) and all(
        isinstance(row, dict) and all(
            isinstance(row.get(key), (int, float)) and math.isfinite(row[key]) and row[key] >= 0
            for key in ("elapsedSeconds", "created", "done", "pending", "processing", "dead", "oldestSeconds")
        ) and row["done"] <= row["created"] for row in samples
    )
    require(valid_queue, "队列证据字段缺失、无效或已完成计数大于创建计数")
    steady = [row for row in samples if row.get("phase") == "steady"]
    drain = [row for row in samples if row.get("phase") == "drain"]
    complete = queue.get("steadySeconds", 0) >= 60 and len(steady) >= 7 and len(drain) >= 3 and steady[-1]["elapsedSeconds"] - steady[0]["elapsedSeconds"] >= 55
    complete = complete and all(0 < b["elapsedSeconds"] - a["elapsedSeconds"] <= 15 for a, b in zip(samples, samples[1:]))
    if not complete:
        errors.append("持续或收尾采样不足、采样存在中断")
    else:
        elapsed = steady[-1]["elapsedSeconds"] - steady[0]["elapsedSeconds"]
        produced = steady[-1]["created"] - steady[0]["created"]
        if produced <= 0 or produced / elapsed < target_rate:
            errors.append("实际连续事件输入未达到核准速率")
        if steady[0]["pending"] + steady[0]["processing"] > 0:
            errors.append("持续输入前已有总账积压，请从可比空队列开始")
        if any(b["created"] < a["created"] or b["done"] < a["done"] for a, b in zip(samples, samples[1:])):
            errors.append("事件累计计数减少，采样期间存在清理或范围变动")
        for start in range(3):
            group = steady[start * len(steady) // 3:(start + 1) * len(steady) // 3 + 1]
            span = group[-1]["elapsedSeconds"] - group[0]["elapsedSeconds"]
            if span <= 0 or group[-1]["created"] - group[0]["created"] < target_rate * span * .8:
                errors.append("事件输入在持续观察窗口中断")
                break
        if any(row.get("dead", 0) > 0 for row in samples):
            errors.append("总账事件进入死信")
        if any(row.get("pending", 0) + row.get("processing", 0) > 0 for row in drain[-3:]):
            errors.append("总账事件在收尾阶段仍有积压")
        windows = [steady[index * len(steady) // 3:(index + 1) * len(steady) // 3] for index in range(3)]
        average_backlog = [sum(row["pending"] + row["processing"] for row in window) / len(window) for window in windows]
        average_oldest = [sum(row["oldestSeconds"] for row in window) / len(window) for window in windows]
        if average_backlog[0] < average_backlog[1] < average_backlog[2] and average_oldest[0] < average_oldest[1] < average_oldest[2]:
            errors.append("持续阶段末尾积压和最老等待时间连续增长")
    checks = reconciliation.get("checks", [])
    if {row.get("type") for row in checks} != {"gl_trial", "gl_operational"} or any(
        row.get("status") != "passed" or row.get("diffCount") != 0 or row.get("differences") != [] for row in checks
    ):
        errors.append("逐行总账余额或业务分录对账未通过")
    if reconciliation.get("before", {}).get("periods") != reconciliation.get("after", {}).get("periods"):
        errors.append("对账期间样本规模发生变化")
    return {"passed": not errors, "errors": list(dict.fromkeys(errors)), "p95Ms": {"month01": p95[0], "month12": p95[1]},
            "targetEventRate": target_rate, "manifest": last["manifest"], "evaluatedAt": utc_now()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("post", "watch", "reconcile", "evaluate"))
    parser.add_argument("--output", required=True)
    parser.add_argument("--manifest")
    parser.add_argument("--settings")
    parser.add_argument("--run-file")
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--steady-seconds", type=int, default=60)
    parser.add_argument("--drain-seconds", type=int, default=60)
    parser.add_argument("--interval-seconds", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--first")
    parser.add_argument("--twelfth")
    parser.add_argument("--queue")
    parser.add_argument("--reconciliation")
    parser.add_argument("--target-event-rate", type=float)
    args = parser.parse_args()
    output = Path(args.output)
    checked_test_output(output)
    require(not output.exists(), "证据文件已经存在，请使用新路径")
    require(output.parent.is_dir(), "请先创建私有证据输出目录")
    if args.action == "evaluate":
        require(all((args.first, args.twelfth, args.queue, args.reconciliation)), "请选择四份完整的证据文件")
        require(args.target_event_rate and math.isfinite(args.target_event_rate) and args.target_event_rate > 0, "请提供核准的持续事件输入速率")
        paths = [args.first, args.twelfth, args.queue, args.reconciliation]
        result = evaluate(*(read_json(path) for path in paths), args.target_event_rate)
        result["evidenceSha256"] = {
            name: hashlib.sha256(Path(path).read_bytes()).hexdigest()
            for name, path in zip(("first", "twelfth", "queue", "reconciliation"), paths)
        }
    else:
        require(all((args.manifest, args.settings, args.run_file)), "采集需要清单、私有配置和容量运行文件")
        raw_path = output.with_name(output.name + ".jsonl")
        checked_test_output(raw_path)
        manifest = manifest_at(args.manifest)
        state = read_json(args.run_file)
        require(state.get("runId") == manifest["tenantCode"][5:17] and len(state.get("password", "")) >= 20, "容量运行文件与样本企业不符")
        require(state.get("artifactSha256") == manifest["artifactSha256"],
                "构建摘要与容量运行文件不符，请使用 run-capacity.ps1 server -JarFile 记录候选包")
        database = Database(local_settings(args.settings), state, args.mysql)
        with raw_path.open("x", encoding="utf-8") as raw:
            def emit(value):
                raw.write(json.dumps(value, ensure_ascii=False, allow_nan=False) + "\n")
                raw.flush()
            emit({"kind": args.action, "manifest": public_manifest(manifest), "startedAt": utc_now()})
            if args.action == "watch":
                result = watch_queue(database, manifest, args.steady_seconds, args.drain_seconds, args.interval_seconds, emit)
            else:
                api = Api(manifest, state)
                result = post_samples(database, api, manifest, emit) if args.action == "post" else reconcile(database, api, manifest, args.timeout_seconds, emit)
    write_json(output, result)
    print("总账容量证据已保存" if "passed" not in result else ("总账容量证据通过" if result["passed"] else "总账容量证据未通过"))
    return 0 if result.get("passed", True) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as failure:
        print("总账容量采集未完成，请检查隔离配置和样本证据。", file=sys.stderr)
        print(str(failure) if isinstance(failure, RuntimeError) or isinstance(failure, ValueError) else "证据或字段不完整", file=sys.stderr)
        raise SystemExit(2)
