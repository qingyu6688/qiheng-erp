import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath


class UpgradeError(RuntimeError):
    pass


IDENTIFIER = re.compile(r"[a-zA-Z][a-zA-Z0-9_]{0,63}\Z")
DATABASE = re.compile(r"[a-z][a-z0-9_]{1,63}\Z")
LOCK = "qiheng_erp_upgrade_2_0"


def checked_test_output(path):
    location = path.resolve()
    if os.name == "nt" and (not re.fullmatch(r"[A-Z]:", location.drive.upper()) or location.drive.upper() == "C:"):
        raise UpgradeError("测试数据目录必须位于本机非 C 盘，且目录联接不能指回 C 盘")
    return location


def configured_test_root(*, required_on_windows=False):
    value = os.environ.get("ERP_TEST_ROOT", "").strip()
    if not value:
        if required_on_windows and os.name == "nt":
            raise UpgradeError("请设置 ERP_TEST_ROOT 指向非 C 盘，再运行升级自测")
        return None
    path = Path(value)
    if not path.is_absolute():
        raise UpgradeError("ERP_TEST_ROOT 必须使用绝对路径")
    return checked_test_output(path)


def temporary_environment(directory):
    return {name: str(directory) for name in ("TMPDIR", "TEMP", "TMP")} if directory else {}


def identifier(value):
    if not IDENTIFIER.fullmatch(value):
        raise UpgradeError("数据库对象名无效")
    return "`" + value + "`"


def literal(value):
    # 用二进制文本字面量传递数据，避免连接的转义模式影响 SQL 参数。
    return "CONVERT(X'" + str(value).encode("utf-8").hex() + "' USING utf8mb4)"


def read_settings(path):
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values:
            raise UpgradeError("配置文件必须为不重复的 KEY=VALUE 行")
        values[key] = value
    for key in ("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD"):
        if key not in values or not values[key]:
            raise UpgradeError("升级配置缺少 " + key)
    if not re.fullmatch(r"[A-Za-z0-9.-]+", values["DB_HOST"]):
        raise UpgradeError("数据库主机地址无效")
    if not values["DB_PORT"].isdigit() or not 1 <= int(values["DB_PORT"]) <= 65535:
        raise UpgradeError("数据库端口无效")
    if not DATABASE.fullmatch(values["DB_NAME"]):
        raise UpgradeError("源库名称无效")
    mode = values.setdefault("DB_SSL_MODE", "REQUIRED")
    if mode not in {"DISABLED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"}:
        raise UpgradeError("数据库 TLS 模式无效")
    if values["DB_HOST"] not in {"127.0.0.1", "localhost"} and mode == "DISABLED":
        raise UpgradeError("跨主机升级连接必须启用 TLS")
    return values


def atomic_json(path, data):
    pending = path.with_name(path.name + ".partial")
    with pending.open("w", encoding="utf-8", newline="\n") as result:
        json.dump(data, result, ensure_ascii=False, indent=2, sort_keys=True)
        result.write("\n")
        result.flush()
        os.fsync(result.fileno())
    os.replace(pending, path)


def load_json(path):
    with path.open(encoding="utf-8") as content:
        return json.load(content)


def sha256(path):
    with path.open("rb") as content:
        return hashlib.file_digest(content, "sha256").hexdigest()


def reparse(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


def object_manifest(root):
    if not root.is_dir() or reparse(root):
        raise UpgradeError("对象仓必须是实际目录")
    entries = {}
    for current, dirs, files in os.walk(root, onerror=raise_os_error):
        parent = Path(current)
        for directory in dirs:
            if reparse(parent / directory):
                raise UpgradeError("对象仓不能包含符号链接或目录联接")
        for name in files:
            path = parent / name
            if reparse(path) or not path.is_file():
                raise UpgradeError("对象仓只能包含常规文件")
            rel = path.relative_to(root).as_posix()
            entries[rel] = {"size": path.stat().st_size, "sha256": sha256(path)}
    return entries


def raise_os_error(error):
    raise error


def copy_objects(source, target, manifest):
    if target.exists() or target.is_symlink():
        raise UpgradeError("目标对象目录已存在，禁止覆盖")
    target.mkdir(parents=True)
    for key in manifest:
        child = PurePosixPath(key)
        if child.is_absolute() or ".." in child.parts or "\\" in key or ":" in key or not child.parts:
            raise UpgradeError("备份清单的对象路径无效")
        origin = source.joinpath(*child.parts)
        destination = target.joinpath(*child.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, destination)
    if object_manifest(target) != manifest:
        raise UpgradeError("恢复对象摘要与停写备份不一致")


class MySql:
    def __init__(self, settings, mysql, dump, *, temp_dir=None):
        self.settings = settings
        self.mysql = mysql
        self.mysqldump = dump
        self.temp_dir = temp_dir
        self.environment = {**os.environ, **temporary_environment(temp_dir), "MYSQL_PWD": settings["DB_PASSWORD"]}
        self.connection = [
            "--no-defaults", "--protocol=tcp",
            "--host=" + settings["DB_HOST"], "--port=" + settings["DB_PORT"],
            "--user=" + settings["DB_USER"], "--ssl-mode=" + settings["DB_SSL_MODE"],
            "--default-character-set=utf8mb4",
        ]
        if settings.get("DB_SSL_CA"):
            self.connection.append("--ssl-ca=" + settings["DB_SSL_CA"])

    def command(self, database=None):
        return [self.mysql, *self.connection, "--connect-timeout=10", "--batch", "--skip-column-names", "--skip-reconnect",
                *(["--database=" + database] if database else [])]

    def query(self, sql, database=None):
        result = subprocess.run(self.command(database), input=sql.encode("utf-8"), capture_output=True,
                                env=self.environment, timeout=120)
        if result.returncode:
            self.failure(result.stderr, "MySQL 核验失败")
        return [row.rstrip("\r").split("\t") for row in result.stdout.decode("utf-8").split("\n") if row]

    def scalar(self, sql, database=None):
        values = self.query(sql, database)
        if len(values) != 1 or len(values[0]) != 1:
            raise UpgradeError("MySQL 返回的核验结果不完整")
        return values[0][0]

    def execute(self, sql, database=None):
        self.query(sql, database)

    def failure(self, error, message):
        forbidden = self.settings["DB_PASSWORD"].encode("utf-8")
        # 仅取错误编号与通用错误说明，避免服务器回显业务 SQL 内容。
        match = re.search(rb"(?:ERROR |Got error: )(\d+)", error.replace(forbidden, b"***"))
        raise UpgradeError(message + (f"（MySQL {match[1].decode()}）" if match else ""))

    def position(self):
        rows = self.query("SHOW MASTER STATUS;")
        if len(rows) != 1 or len(rows[0]) != 5 or not rows[0][4]:
            raise UpgradeError("无法取得最终 binlog 文件、位置和 GTID")
        file, offset, _, _, gtid = rows[0]
        return {"binlog": file, "position": int(offset), "gtid": gtid}

    def server(self):
        return self.query("""
            SELECT @@server_uuid,@@version,@@global.log_bin,@@global.gtid_mode,@@global.offline_mode,
                   @@global.event_scheduler,@@global.read_only,@@global.super_read_only,@@global.persisted_globals_load;
            """)[0]

    def occupied(self, database):
        return int(self.scalar("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name="
                               + literal(database))) != 0

    def sessions(self):
        return int(self.scalar(f"""
            SELECT COUNT(*) FROM performance_schema.threads WHERE TYPE='FOREGROUND'
              AND PROCESSLIST_ID IS NOT NULL AND PROCESSLIST_ID<>CONNECTION_ID()
              AND PROCESSLIST_ID<>COALESCE(IS_USED_LOCK('{LOCK}'),0)
              AND COALESCE(PROCESSLIST_COMMAND,'') NOT IN ('Binlog Dump','Binlog Dump GTID')
              AND COALESCE(PROCESSLIST_USER,'')<>'event_scheduler'
              AND (PROCESSLIST_USER IS NOT NULL OR COALESCE(PROCESSLIST_COMMAND,'')<>'Daemon')
            """))

    @contextmanager
    def lock(self):
        with subprocess.Popen([*self.command(), "--unbuffered"], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.environment) as session:
            try:
                session.stdin.write(f"SELECT GET_LOCK('{LOCK}',0);\n".encode())
                session.stdin.flush()
                if session.stdout.readline().strip() != b"1":
                    raise UpgradeError("当前实例已有升级进程或无法取得升级锁")
                yield
            finally:
                if session.poll() is None:
                    session.terminate()

    def dump_to(self, database, output):
        command = [self.mysqldump, *self.connection, "--single-transaction", "--skip-lock-tables",
                   "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob",
                   "--routines", "--events", "--triggers", database]
        with output.open("xb") as target:
            result = subprocess.run(command, stdout=target, stderr=subprocess.PIPE, env=self.environment)
            if result.returncode:
                self.failure(result.stderr, "数据库停写备份失败")
            target.flush()
            os.fsync(target.fileno())

    def restore(self, output, database, charset, collation):
        if self.occupied(database):
            raise UpgradeError("目标数据库已存在，禁止覆盖")
        self.execute(f"CREATE DATABASE {identifier(database)} CHARACTER SET {identifier(charset)} "
                     f"COLLATE {identifier(collation)}")
        with output.open("rb") as content:
            result = subprocess.run([*self.command(database), "--binary-mode"], stdin=content,
                                    capture_output=True, env=self.environment)
        if result.returncode:
            self.failure(result.stderr, "备份恢复失败，已保留独立目标库供检查")

    def tables(self, database):
        rows = self.query("""
            SELECT c.TABLE_NAME,c.COLUMN_NAME,c.COLUMN_KEY FROM information_schema.columns c
            JOIN information_schema.tables t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME
            WHERE c.TABLE_SCHEMA=DATABASE() AND t.TABLE_TYPE='BASE TABLE'
            ORDER BY c.TABLE_NAME,c.ORDINAL_POSITION
            """, database)
        tables = {}
        for table, column, kind in rows:
            meta = tables.setdefault(table, {"columns": [], "key": []})
            meta["columns"].append(column)
            if kind == "PRI":
                meta["key"].append(column)
        return tables

    def fingerprints(self, database, tables):
        result = {}
        current = None
        count = 0
        digest = hashlib.sha256()
        # 批量发出按主键排序的查询，并按表逐行计算摘要，连接数和内存不随数据量增长。
        with tempfile.TemporaryFile(dir=self.temp_dir) as queries, tempfile.TemporaryFile(dir=self.temp_dir) as errors:
            for table, meta in tables.items():
                columns, key = meta["columns"], meta["key"]
                values = ",".join("HEX(CAST(" + identifier(col) + " AS BINARY))" for col in columns)
                order = ",".join(identifier(col) for col in key) if key else ",".join(map(str, range(1, len(columns) + 1)))
                queries.write((f"SELECT '@{table}';\nSELECT {values} FROM {identifier(table)} "
                               f"{meta.get('condition', '')} ORDER BY {order};\n").encode("utf-8"))
            queries.seek(0)
            with subprocess.Popen([*self.command(database), "--quick"], stdin=queries,
                                  stdout=subprocess.PIPE, stderr=errors, env=self.environment) as child:
                for raw in child.stdout:
                    row = raw.rstrip(b"\r\n")
                    if row.startswith(b"@"):
                        if current is not None:
                            result[current] = {"count": count, "sha256": digest.hexdigest()}
                        current = row[1:].decode("ascii")
                        count, digest = 0, hashlib.sha256()
                    else:
                        count += 1
                        digest.update(row + b"\n")
                if child.wait():
                    errors.seek(0)
                    self.failure(errors.read(), "业务数据摘要计算失败" + ("，表 " + current if current else ""))
        if current is not None:
            result[current] = {"count": count, "sha256": digest.hexdigest()}
        if result.keys() != tables.keys():
            raise UpgradeError("业务数据摘要缺少表")
        return result
