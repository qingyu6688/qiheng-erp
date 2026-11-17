"""在本机独立测试企业中验证 OTLP 链路和 Outbox 告警，全程只使用 Python 标准库。"""

import argparse
import base64
import gzip
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen


ROOT = Path(__file__).resolve().parents[1]


def protobuf_fields(data: bytes) -> dict[int, list]:
    """读取 OTLP 使用的基本字段，保留长度字段供下一层消息解码。"""
    position = 0
    fields = {}

    def varint() -> int:
        nonlocal position
        value = 0
        for shift in range(0, 70, 7):
            part = data[position]
            position += 1
            value |= (part & 127) << shift
            if part < 128:
                return value
        raise ValueError("OTLP 整数字段过长")

    while position < len(data):
        tag = varint()
        wire = tag & 7
        if wire == 0:
            value = varint()
        else:
            lengths = {1: 8, 5: 4}
            length = varint() if wire == 2 else lengths[wire]
            value = data[position:position + length]
            if len(value) != length:
                raise ValueError("OTLP 字段被截断")
            position += length
        fields.setdefault(tag >> 3, []).append(value)
    return fields


def decode_spans(payload: bytes) -> list[dict]:
    spans = []
    for resource in protobuf_fields(payload).get(1, []):
        for scope in protobuf_fields(resource).get(2, []):
            for encoded in protobuf_fields(scope).get(2, []):
                fields = protobuf_fields(encoded)
                spans.append({
                    "traceId": fields[1][0].hex(),
                    "spanId": fields[2][0].hex(),
                    "parentSpanId": fields.get(4, [b""])[0].hex(),
                    "name": fields[5][0].decode(),
                    "kind": fields.get(6, [0])[0],
                    "durationMs": (int.from_bytes(fields[8][0], "little")
                                   - int.from_bytes(fields[7][0], "little")) / 1_000_000,
                })
    return spans


class Receiver(BaseHTTPRequestHandler):
    spans = []
    alerts = []
    lock = threading.Lock()

    def do_POST(self) -> None:
        payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.headers.get("Content-Encoding") == "gzip":
            payload = gzip.decompress(payload)
        with self.lock:
            if self.path == "/v1/traces":
                self.spans.extend(decode_spans(payload))
            elif self.path == "/alerts":
                self.alerts.append(json.loads(payload))
            else:
                self.send_error(404)
                return
        self.send_response(200)
        self.send_header("Content-Type", "application/x-protobuf")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, format: str, *arguments) -> None:
        return


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def read_settings(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
        if match:
            values[match[1]] = match[2]
    if values.get("DB_HOST") not in ("localhost", "127.0.0.1"):
        raise ValueError("监控演练只允许连接本机数据库")
    if not re.fullmatch(r"erp_test(?:_[a-z0-9]+)*", values.get("TEST_DB_NAME", "")):
        raise ValueError("监控演练需要项目专用测试库")
    if values["TEST_DB_NAME"] == values.get("DB_NAME"):
        raise ValueError("测试库必须与开发库分离")
    return values


def sql(settings: dict, query: str) -> str:
    environment = dict(os.environ, MYSQL_PWD=settings["TEST_DB_PASSWORD"])
    result = subprocess.run([
        "mysql", "--no-defaults", "--host=127.0.0.1", "--port=" + settings["DB_PORT"],
        "--user=" + settings["TEST_DB_USER"], "--database=" + settings["TEST_DB_NAME"],
        "--default-character-set=utf8mb4", "--batch", "--skip-column-names",
    ], input=query, text=True, encoding="utf-8", capture_output=True, env=environment)
    if result.returncode:
        raise RuntimeError("演练 SQL 执行失败，请检查本机测试库连接和权限")
    return result.stdout.strip()


def wait_until(check, description: str, timeout: int = 90):
    deadline = time.monotonic() + timeout
    next_update = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (HTTPError, URLError, ConnectionError, TimeoutError):
            pass
        if time.monotonic() >= next_update:
            print("正在等待" + description, flush=True)
            next_update += 30
        time.sleep(1)
    raise TimeoutError("等待" + description + "超时")


def fetch(url: str, headers: dict | None = None) -> str:
    with urlopen(Request(url, headers=headers or {}), timeout=5) as response:
        return response.read().decode()


def start_process(command: list[str], environment: dict, output: Path):
    handle = output.open("w", encoding="utf-8")
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=handle,
                               stderr=subprocess.STDOUT, creationflags=flags)
    return process, handle


def monitoring_config(output: Path, ports: dict, token: str) -> None:
    (output / "metrics-token").write_text(token, encoding="utf-8")
    rules = (ROOT / "deploy/observability/alerts.yaml").resolve().as_posix()
    credentials = (output / "metrics-token").as_posix()
    (output / "prometheus.yaml").write_text(f"""global:
  scrape_interval: 2s
  evaluation_interval: 2s
rule_files: ['{rules}']
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['127.0.0.1:{ports['alertmanager']}']
scrape_configs:
  - job_name: erp
    metrics_path: /actuator/prometheus
    authorization:
      credentials_file: '{credentials}'
    static_configs:
      - targets: ['127.0.0.1:{ports['management']}']
        labels:
          role: api
""", encoding="utf-8")
    (output / "alertmanager.yaml").write_text(f"""route:
  receiver: local
  group_by: [alertname, instance]
  group_wait: 1s
  group_interval: 1s
  repeat_interval: 1h
receivers:
  - name: local
    webhook_configs:
      - url: http://127.0.0.1:{ports['receiver']}/alerts
        send_resolved: true
""", encoding="utf-8")


def trace_request(port: int, tenant_code: str, password: str) -> tuple[str, str]:
    client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    payload = json.dumps({"tenantCode": tenant_code, "username": "admin", "password": password}).encode()
    with client.open(Request(f"http://127.0.0.1:{port}/api/v1/auth/login", data=payload,
                             headers={"Content-Type": "application/json"}), timeout=10) as response:
        if response.status != 200:
            raise RuntimeError("演练账号登录失败")
    trace_id = secrets.token_hex(16)
    headers = {"traceparent": "00-" + trace_id + "-" + secrets.token_hex(8) + "-01"}
    with client.open(Request(f"http://127.0.0.1:{port}/api/v1/auth/me", headers=headers), timeout=10) as response:
        response.read()
        return trace_id, response.headers["X-Request-Id"]


def verify_trace(trace_id: str, request_id: str, output: Path) -> list[dict]:
    spans = wait_until(lambda: [span for span in Receiver.spans if span["traceId"] == trace_id
                               and span["name"].upper().startswith("SELECT")], "JDBC 链路导出")
    trace = [span for span in Receiver.spans if span["traceId"] == trace_id]
    if not any(span["kind"] == 2 for span in trace):
        raise AssertionError("请求链路缺少入口片段")
    if not any("HGETALL" in span["name"].upper() for span in trace):
        raise AssertionError("请求链路缺少 Redis 会话读取")
    wait_until(lambda: request_id in (output / "http.log").read_text(encoding="utf-8")
               and trace_id in (output / "http.log").read_text(encoding="utf-8"), "请求号与链路日志关联")
    span_ids = {span["spanId"] for span in trace}
    if not all(span["parentSpanId"] in span_ids for span in spans):
        raise AssertionError("SQL 片段与入口调用链未连通")
    return trace


def cleanup_tenant(settings: dict, tenant_code: str) -> None:
    tenant = sql(settings, f"SELECT id FROM tenant WHERE code='{tenant_code}'")
    if not tenant:
        return
    for table in ("outbox_event", "audit_log", "org_user_data_scope", "sys_user_role",
                  "sys_role_permission", "sys_role", "sys_user", "company"):
        sql(settings, f"DELETE FROM {table} WHERE tenant_id={int(tenant)}")
    sql(settings, f"DELETE FROM tenant WHERE id={int(tenant)}")


def run(settings: dict, tools: Path, output: Path) -> dict:
    ports = {key: free_port() for key in ("api", "management", "receiver", "prometheus", "alertmanager")}
    token = secrets.token_urlsafe(32)
    password = secrets.token_urlsafe(24)
    tenant_code = "M10-" + secrets.token_hex(8)
    monitoring_config(output, ports, token)
    receiver = ThreadingHTTPServer(("127.0.0.1", ports["receiver"]), Receiver)
    threading.Thread(target=receiver.serve_forever, daemon=True).start()
    environment = dict(os.environ, **settings)
    environment.update({
        "DB_NAME": settings["TEST_DB_NAME"], "DB_USER": settings["TEST_DB_USER"],
        "DB_PASSWORD": settings["TEST_DB_PASSWORD"], "SPRING_PROFILES_ACTIVE": "dev,observability",
        "SERVER_ADDRESS": "127.0.0.1", "ERP_MANAGEMENT_ADDRESS": "127.0.0.1",
        "APP_ENV": "m10-" + secrets.token_hex(6), "SERVER_PORT": str(ports["api"]),
        "ERP_MANAGEMENT_PORT": str(ports["management"]), "ERP_METRICS_TOKEN": token,
        "ERP_WORKER_ENABLED": "false", "ERP_NODE_ID": "12", "ERP_DEV_TENANT_CODE": tenant_code,
        "ERP_DEV_ADMIN_PASSWORD": password, "ERP_HTTP_LOG_FILE": str(output / "http.log"),
        "ERP_LOGIN_SETTINGS_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        "OTEL_SERVICE_NAME": "qiheng-erp-local-drill", "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
        "OTEL_EXPORTER_OTLP_ENDPOINT": f"http://127.0.0.1:{ports['receiver']}",
        "OTEL_TRACES_SAMPLER": "always_on", "OTEL_METRICS_EXPORTER": "none", "OTEL_LOGS_EXPORTER": "none",
        "OTEL_BSP_SCHEDULE_DELAY": "1000",
        "OTEL_INSTRUMENTATION_JDBC_STATEMENT_SANITIZER_ENABLED": "true",
    })
    processes = []
    jar = ROOT / "erp-server.jar"
    if not jar.is_file():
        candidates = list((ROOT / "erp-server/target").glob("erp-server-*.jar"))
        if not candidates:
            raise FileNotFoundError("请先构建后端 JAR")
        jar = max(candidates, key=lambda path: path.stat().st_mtime)
    try:
        processes.append(start_process([
            str(Path(settings["JAVA_HOME"]) / "bin/java.exe"), "-javaagent:" + str(tools / "opentelemetry-javaagent.jar"),
            "-jar", str(jar),
            "--erp.observability.queue-interval-ms=1000",
        ], environment, output / "api.log"))
        management = f"http://127.0.0.1:{ports['management']}"
        wait_until(lambda: json.loads(fetch(management + "/actuator/health"))["status"] == "UP", "演练 API 启动", 120)
        trace_id, request_id = trace_request(ports["api"], tenant_code, password)
        trace = verify_trace(trace_id, request_id, output)
        tenant = int(sql(settings, f"SELECT id FROM tenant WHERE code='{tenant_code}'"))
        event = secrets.randbelow(1 << 60) + (1 << 60)
        sql(settings, f"""INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,created_at)
            VALUES({event},{tenant},'reconciliation',{event},0,'reconciliation.requested','{{}}',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE))""")
        for name, binary, arguments in (
            ("alertmanager", tools / "alertmanager-0.35.0.windows-amd64/alertmanager.exe", ["--cluster.listen-address="]),
            ("prometheus", tools / "prometheus-3.16.0.windows-amd64/prometheus.exe", []),
        ):
            arguments += [f"--web.listen-address=127.0.0.1:{ports[name]}", f"--config.file={output / (name + '.yaml')}"]
            storage = "--storage.path=" if name == "alertmanager" else "--storage.tsdb.path="
            processes.append(start_process([str(binary), *arguments, storage + str(output / (name + '-data'))],
                                           environment, output / (name + ".log")))
        fired = wait_until(lambda: [alert for notice in Receiver.alerts for alert in notice["alerts"]
                                   if alert["labels"]["alertname"] == "ErpOutboxBacklog" and alert["status"] == "firing"],
                           "Outbox 两分钟告警及通知", 210)
        sql(settings, f"DELETE FROM outbox_event WHERE tenant_id={tenant} AND id={event}")
        resolved = wait_until(lambda: [alert for notice in Receiver.alerts for alert in notice["alerts"]
                                      if alert["labels"]["alertname"] == "ErpOutboxBacklog" and alert["status"] == "resolved"],
                              "告警恢复通知", 90)
        return {"traceId": trace_id, "requestId": request_id, "spans": trace,
                "firing": bool(fired), "resolved": bool(resolved), "ports": ports}
    finally:
        (output / "received-spans.json").write_text(
            json.dumps(Receiver.spans, ensure_ascii=False, indent=2), encoding="utf-8")
        (output / "received-alerts.json").write_text(
            json.dumps(Receiver.alerts, ensure_ascii=False, indent=2), encoding="utf-8")
        for process, handle in reversed(processes):
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
            handle.close()
        receiver.shutdown()
        receiver.server_close()
        cleanup_tenant(settings, tenant_code)


def main() -> None:
    parser = argparse.ArgumentParser(description="柒衡 ERP 本机链路和告警演练")
    parser.add_argument("--settings", type=Path, default=ROOT / ".env")
    parser.add_argument("--tools", type=Path, default=ROOT / ".local/tools/observability")
    options = parser.parse_args()
    settings = read_settings(options.settings)
    if not sql(settings, "SELECT VERSION()").startswith("8.0.46"):
        raise ValueError("演练需要 MySQL 8.0.46")
    output = ROOT / ".local" / ("observability-" + time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(3))
    output.mkdir()
    result = run(settings, options.tools.resolve(), output.resolve())
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("本机链路、Outbox 告警及恢复通知已通过，证据目录 " + str(output), flush=True)


if __name__ == "__main__":
    main()
