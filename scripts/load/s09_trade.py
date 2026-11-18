"""在本机已启账的隔离 E2E 公司，以间隔十秒运行一组五销一采。"""

import hashlib
import http.cookiejar
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from uuid import uuid4

if __name__ == "__main__":
    sys.dont_write_bytecode = True
if __package__:
    from .local_test_storage import checked_test_output
else:
    from local_test_storage import checked_test_output

TYPES = ("sale",) * 5 + ("purchase",)
INTERVAL = 10


def require(ok, message):
    if not ok:
        raise ValueError(message)


def isolated_directory(root, location, description):
    require(bool(root) and Path(root).is_absolute(), "须指定本机隔离根目录的绝对路径")
    require(bool(location) and Path(location).is_absolute(), description + "须使用绝对路径")
    base = checked_test_output(Path(root).resolve())
    target = checked_test_output(Path(location).resolve())
    require(base.is_dir() and target.is_dir() and target != base and target.is_relative_to(base),
            description + "必须位于已准备的隔离根目录内")
    return target


def atomic(path, data):
    tmp = path.with_suffix(".next")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, path)


class Paused(RuntimeError):
    pass


@contextmanager
def exclusive(path):
    # 用系统文件锁，异常退出后系统会释放，同一份状态只能由一个驱动进程修改。
    with path.open("a+b") as stream:
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


class Session:
    def __init__(self, base, tenant, company, user, password):
        self.base = base
        self.company = company
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.request("POST", "/auth/login", {"tenantCode": tenant, "username": user, "password": password})
        me = self.request("GET", "/auth/me")
        require(me["username"] == user and not me.get("mustChangePassword")
                and any(c["id"] == company and c["allWarehouses"] for c in me["companies"]),
                "账号、公司或仓库授权与夹具不一致")
        self.user_id = me["id"]

    def request(self, method, path, body=None, key=None):
        headers = {"Content-Type": "application/json", "X-Company-Id": self.company}
        for cookie in self.cookies:
            if cookie.name == "XSRF-TOKEN":
                headers["X-XSRF-TOKEN"] = cookie.value
        if key:
            headers["Idempotency-Key"] = key
        req = urllib.request.Request(self.base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with self.opener.open(req, timeout=35) as response:
                return json.load(response)["data"]
        except urllib.error.HTTPError as error:
            try:
                detail = json.loads(error.read())
            except ValueError:
                detail = {}
            raise RuntimeError(f"{method} {path} HTTP {error.code} {detail.get('errorCode','')} {detail.get('message','')}") from None


class Observer:
    def __init__(self, tenant_code, root):
        require(re.fullmatch(r"E2E-[a-f0-9]{12}", tenant_code), "只能观察随机 E2E 企业")
        self.tenant = tenant_code
        self.root = root
        self.name = os.environ["S09_DB_NAME"]
        require(bool(re.fullmatch(r"erp_test_s09_[a-z0-9_]+", self.name)), "须选择独立 S09 测试库")
        self.port = int(os.environ["S09_DB_PORT"])
        require(1024 <= self.port <= 65535 and self.port != 3306, "须选择独立 MySQL 实例")
        self.client_env = dict(os.environ)
        password = os.getenv("S09_OBSERVER_PASSWORD")
        require(password, "请通过环境变量 S09_OBSERVER_PASSWORD 指定隔离库观察账号口令")
        self.client_env["MYSQL_PWD"] = password

    def rows(self, query):
        command = [os.getenv("S09_MYSQL", "mysql"), "--no-defaults", "--protocol=tcp", "--host=127.0.0.1",
                   f"--port={self.port}", "--user=" + os.getenv("S09_DB_USER", "root"), f"--database={self.name}",
                   "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4"]
        sql = ("START TRANSACTION READ ONLY;\n" + query + ";\nROLLBACK;\n").encode("utf-8")
        p = subprocess.run(command, input=sql, capture_output=True, timeout=30, env=self.client_env)
        require(p.returncode == 0, "只读观察查询失败：" + p.stderr.decode("utf-8", errors="replace")[:400])
        return [line.split("\t") for line in p.stdout.decode("utf-8").splitlines() if line]

    def fixture(self):
        rows = self.rows(f"""
            SELECT t.id,c.id,w.id,s.id,u.unit_id,customer.id,supplier.id,a.id,@@datadir,p.id,b.id,@@port,DATABASE()
            FROM tenant t JOIN company c ON c.tenant_id=t.id AND c.code='DEMO-A'
            JOIN warehouse w ON w.tenant_id=t.id AND w.company_id=c.id AND w.code='E2E-WAREHOUSE'
            JOIN sku s ON s.tenant_id=t.id AND s.code='E2E-SKU'
            JOIN unit_conversion u ON u.tenant_id=t.id AND u.sku_id=s.id AND u.is_base=1
            JOIN business_partner customer ON customer.tenant_id=t.id AND customer.code='E2E-CUSTOMER'
            JOIN business_partner supplier ON supplier.tenant_id=t.id AND supplier.code='E2E-M5-SUPPLIER'
            JOIN cash_account a ON a.tenant_id=t.id AND a.company_id=c.id AND a.code='E2E-BANK'
            JOIN accounting_period p ON p.tenant_id=t.id AND p.company_id=c.id AND CURRENT_DATE BETWEEN p.start_date AND p.end_date
            JOIN gl_account_book b ON b.tenant_id=t.id AND b.company_id=c.id AND b.status='active'
            WHERE t.code='{self.tenant}'""")
        require(len(rows) == 1 and rows[0][-2:] == [str(self.port), self.name], "隔离端口、测试库、公司或账套不符")
        keys = "tenant company warehouse sku unit customer supplier account datadir period book".split()
        result = dict(zip(keys, rows[0][:-2], strict=True))
        result["datadir"] = str(isolated_directory(self.root, result["datadir"], "MySQL 数据目录"))
        require(all(v.isdecimal() for k, v in result.items() if k != "datadir"), "主数据编号无效")
        return result

    def postings(self, fixture, facts):
        # 只统计这六张订单实际产生的源单编号。期初和其他凭证不进入负载事件数。
        require(facts and all(kind in {"shipment_post","shipment_sign","goods_receipt","payment","receipt"}
                              and str(key).isdecimal() for kind,key in facts), "缺少本组业务来源")
        ids = ",".join(f"('{kind}',{key})" for kind,key in facts)
        rows = self.rows(f"""
            SELECT j.source_type,j.source_id,j.id,e.id,e.status,v.id,v.status,j.total_debit,j.total_credit
            FROM journal j
            LEFT JOIN outbox_event e ON e.tenant_id=j.tenant_id AND e.event_type='gl.journal.requested'
                AND e.aggregate_type='gl_journal' AND e.aggregate_id=j.id
            LEFT JOIN gl_voucher v ON v.tenant_id=j.tenant_id AND v.company_id=j.company_id AND v.journal_id=j.id
                AND v.source='business' AND v.is_deleted=0
            WHERE j.tenant_id={fixture['tenant']} AND j.company_id={fixture['company']}
              AND j.posting_action='post' AND (j.source_type,j.source_id) IN ({ids})
            ORDER BY j.id""")
        return [dict(zip("type source journal event eventStatus voucher voucherStatus debit credit".split(), row, strict=True)) for row in rows]


class Group:
    def __init__(self, output, identity, sessions, clock=time.time, sleeper=time.sleep):
        self.output, self.sessions, self.clock, self.sleep = output, sessions, clock, sleeper
        self.path = output / "checkpoint.json"
        self.state = json.loads(self.path.read_text(encoding="utf-8")) if self.path.exists() else {
            "group": uuid4().hex, "identity": identity, "day": datetime.now(timezone(timedelta(hours=8))).date().isoformat(),
            "steps": {}, "starts": {}, "orders": {}, "status": "ready"}
        require(self.state["identity"] == identity, "检查点的公司、账套、日期环境或经办复核身份不一致")
        self.save()

    def save(self):
        atomic(self.path, self.state)

    def check_pause(self):
        if (self.output/"pause").exists():
            raise Paused("已保留检查点及幂等键；移除 pause 后使用同一测试库和输出目录恢复")

    def step(self, label, role, path, body):
        self.check_pause()
        basis = json.dumps([role, path, body], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256(basis.encode()).hexdigest()
        steps = self.state["steps"]
        entry = steps.setdefault(label, {"sha256": digest})
        require(entry["sha256"] == digest, f"恢复时步骤参数变化：{label}")
        if "result" not in entry:
            self.state.update(status="running", currentStep=label, currentPath=path)
            self.save()
            key = "s09-" + self.state["group"] + "-" + label.replace(".", "-")
            result = self.sessions[role].request("POST", path, body, key)
            entry["result"] = {"id": result["id"], "version": result.get("version"),
                               "lines": [{"id": x["id"]} for x in result.get("lines", [])]}
            self.save()
        return entry["result"]

    def advance(self, name, collection, doc, actions):
        for action,role in actions:
            doc = self.step(name+"."+action,role,f"/{collection}/{doc['id']}/{action}",{"version":doc["version"]})
        return doc

    def wait_slot(self, index):
        starts = self.state["starts"]
        if str(index) in starts:
            return
        if index:
            target = starts[str(index-1)] + INTERVAL
            while self.clock() < target:
                self.check_pause()
                self.sleep(min(.5, target-self.clock()))
        starts[str(index)] = self.clock()
        self.save()

    def document(self, index, fixture):
        sale = TYPES[index] == "sale"
        name = ("s" if sale else "p") + str(index+1)
        kind = "sales-orders" if sale else "purchase-orders"
        party = "customer" if sale else "supplier"
        qty = "0.1" if sale else "1"
        line = {"skuId":fixture["sku"], "warehouseId":fixture["warehouse"], "unitId":fixture["unit"],
                "unitPrice":"100" if sale else "10","qty":qty,"lineDiscountAmount":"0","taxRate":"0"}
        day = self.state["day"]
        doc = self.step(name+".create","maker","/"+kind,{
            party+"Id":fixture[party],"businessDate":day,"priceTaxInclusive":True,"orderDiscountAmount":"0",
            "lines":[line.copy() for _ in range(10)],"fees":[],"remark":"S09 "+self.state["group"]+" "+name})
        require(len(doc["lines"]) == 10, "订单行数应为 10")
        self.advance(name, kind, doc, [("submit","maker"),("approve","reviewer")])
        money = "receipts" if sale else "payments"
        entry = self.step(name+".cash","maker","/"+money,{
            ("receiptType" if sale else "paymentType"):party,"partnerId":fixture[party],"cashAccountId":fixture["account"],
            "amount":"100","businessDate":day,"relatedOrderId":doc["id"],"remark":"S09 订单资金核销"})
        self.advance(name+".cash",money,entry,[("submit","maker"),("confirm","reviewer")])
        mover = "shipments" if sale else "goods-receipts"
        payload = {party+"Id":fixture[party],"warehouseId":fixture["warehouse"],"businessDate":day,
                   "lines":[{"orderLineId":x["id"],"unitId":fixture["unit"],"qty":qty} for x in doc["lines"]]}
        if sale:
            payload["deliveryMethod"] = "delivery"
        movement = self.step(name+".move","maker","/"+mover,payload)
        posted = self.advance(name+".move",mover,movement,[("submit","maker"),("post","reviewer")])
        if sale:
            require(len(movement["lines"]) == 10, "发货单行数应为 10")
            self.step(name+".sign","reviewer",f"/shipments/{movement['id']}/sign",{"version":posted["version"],"signDate":day,
                      "lines":[{"lineId":x["id"],"signedQty":qty} for x in movement["lines"]],"attachmentIds":[]})
        state = self.sessions["maker"].request("GET",f"/{kind}/{doc['id']}")
        require(state["status"]=="closed" and state["fulfillmentStatus"]=="full" and state["settlementStatus"]=="full",
                f"{name} 履约或核销未闭环")
        self.state["orders"][name] = {"type":TYPES[index],"order":doc["id"],"cash":entry["id"],"movement":movement["id"],
                                      "lines":len(doc["lines"]),"status":state["status"],"settlement":state["settlementStatus"]}
        self.save()


def wait_postings(observer, fixture, orders):
    facts=[]
    for r in orders.values():
        if r["type"]=="sale":
            facts.extend([("shipment_post",r["movement"]),("shipment_sign",r["movement"]),("receipt",r["cash"])])
        else:
            facts.extend([("goods_receipt",r["movement"]),("payment",r["cash"])])
    require(len(facts)==len(set(facts))==17, "5 销售和 1 采购必须唯一对应 17 个来源")
    until=time.monotonic()+100
    while True:
        actual=observer.postings(fixture,facts)
        finished=[r for r in actual if r["eventStatus"]=="done" and r["voucherStatus"]=="posted" and r["debit"]==r["credit"]]
        if len(actual)==len(finished)==17 and {(r["type"],r["source"]) for r in actual}==set(facts):
            require(all(len({r[k] for r in actual})==17 for k in ("event","voucher","journal")), "总账事件或凭证重复")
            return actual
        require(time.monotonic()<until, f"总账消费超时：本组来源 17，已过账 {len(finished)}，已找到分录 {len(actual)}")
        time.sleep(1)


def reconcile(group):
    result={}
    api=group.sessions["maker"]
    for check in ["gl_trial","gl_operational"]:
        run=group.step("check."+check,"maker","/reconciliation-runs",{"checkType":check})
        url="/reconciliation-runs/"+run["id"]
        until=time.monotonic()+100
        while True:
            info=api.request("GET",url)
            if info["status"] in ("passed","failed","error"): break
            require(time.monotonic()<until,"对账任务超时："+check)
            time.sleep(1)
        diffs=api.request("GET",url+"/diffs?limit=1")
        result[check]={"id":run["id"],"status":info["status"],"diffCount":info["diffCount"]}
        require(info["status"]=="passed" and info["diffCount"]==0 and diffs["items"]==[] and not diffs.get("nextCursor"),
                f"{check} 对账未通过，任务 {run['id']}")
    return result


def main():
    root = os.getenv("S09_ISOLATION_ROOT") or os.getenv("ERP_TEST_ROOT")
    output = isolated_directory(root, os.environ["S09_OUTPUT"], "证据目录")
    base=os.environ["S09_BASE"]
    require(re.fullmatch(r"http://127\.0\.0\.1:[1-9]\d{3,4}/api/v1",base),"仅支持隔离回环 HTTP 服务")
    tenant=os.environ["ERP_E2E_TENANT_CODE"]
    require(re.fullmatch(r"E2E-[a-f0-9]{12}",tenant),"只能使用随机 E2E 企业")
    with exclusive(output/"driver.lock"):
        observer=Observer(tenant,root)
        fixture=observer.fixture()
        actors={role:Session(base,tenant,fixture["company"],user,os.environ["ERP_E2E_PASSWORD"])
                for role,user in [("maker","admin"),("reviewer","reviewer")]}
        require(actors["maker"].user_id!=actors["reviewer"].user_id,"经办与复核须不同账号")
        book=actors["maker"].request("GET","/gl/book")
        require(book.get("status")=="active" and str(book["id"])==fixture["book"],"API 的启用账套与隔离库不一致")
        identity={**fixture,"tenantCode":tenant,"book":book["id"],"api":base,"database":observer.name,
                  "maker":actors["maker"].user_id,"reviewer":actors["reviewer"].user_id}
        group=Group(output,identity,actors)
        try:
            for i in range(len(TYPES)):
                group.check_pause()
                group.wait_slot(i)
                group.document(i,fixture)
            postings=wait_postings(observer,fixture,group.state["orders"])
            checks=reconcile(group)
            starts=[group.state["starts"][str(i)] for i in range(len(TYPES))]
            result={"scope":identity,"day":group.state["day"],"group":group.state["group"],"orders":group.state["orders"],
                    "starts":starts,"intervalSeconds":[round(b-a,3) for a,b in zip(starts,starts[1:])],
                    "gl":postings,"reconciliation":checks}
            atomic(output/"result.json",result)
            group.state["status"]="complete"
        except (KeyboardInterrupt,Paused) as error:
            group.state.update(status="paused",diagnostic=str(error) or "收到停止信号")
            raise
        except Exception as error:
            group.state.update(status="failed",diagnostic=str(error))
            raise
        finally:
            group.save()
    print("5 销售、1 采购共 60 行闭环完成，17 个真实总账事件及凭证均已核验，双项对账无差异。")


if __name__ == "__main__":
    main()
