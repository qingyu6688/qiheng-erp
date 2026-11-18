import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from data import (attachment_check, capture_table_fingerprints, compare_permissions, compare_scopes,
                  convert_permissions, permission_codes, retired_calls, running_work, verify_business, verify_copy)
from engine import (DATABASE, MySql, UpgradeError, atomic_json, checked_test_output, configured_test_root,
                    copy_objects, identifier, literal, load_json, object_manifest, read_settings, sha256,
                    temporary_environment)


SOURCE_MIN = 87
SOURCE_MAX = 97
LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"
UPGRADE_MAIN = "cn.erp.upgrade.UpgradeMain"
READY_PHASES = frozenset({"ready", "rollback-ready"})


def utc(timespec="seconds"):
    return datetime.now(timezone.utc).isoformat(timespec=timespec)


class Upgrade:
    def __init__(self, args):
        self.args = args
        self.settings = read_settings(args.settings)
        self.source = self.settings["DB_NAME"]
        self.jar = args.jar.resolve(strict=True)
        self.objects = args.objects.absolute()
        self.work = args.work_dir.absolute()
        self.temp_dir = None
        self.test_environment = {}
        test_root = configured_test_root()
        if test_root is not None:
            for directory in (self.work, self.objects, args.target_objects, args.rollback_objects):
                if directory is not None:
                    checked_test_output(directory)
            self.temp_dir = checked_test_output(self.work / "tmp")
            self.test_environment = temporary_environment(self.temp_dir)
            self.test_environment["ERP_HTTP_LOG_FILE"] = str(checked_test_output(self.work / "upgrade-http.log"))
        if self.work.resolve().is_relative_to(self.objects.resolve()) or self.objects.resolve().is_relative_to(self.work.resolve()):
            raise UpgradeError("升级记录和源对象仓必须使用各自的目录")
        self.work.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state_path = self.work / "state.json"
        self.state = load_json(self.state_path) if self.state_path.exists() else {}
        if test_root is not None:
            for key in ("targetObjects", "backup"):
                if self.state.get(key):
                    checked_test_output(Path(self.state[key]))
            self.temp_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.mysql = MySql(self.settings, args.mysql, args.mysqldump, temp_dir=self.temp_dir)
        self.server_id = self.mysql.server()[0]
        if self.state:
            identity = (self.server_id, self.source, str(self.objects), sha256(self.jar))
            saved = tuple(self.state[key] for key in ("serverId", "source", "objects", "jarSha256"))
            if identity != saved:
                raise UpgradeError("升级记录属于其他实例、源库、对象仓或候选包")

    def java(self, action, database, objects, name):
        report = self.work / (name + ".json")
        log = self.work / (name + ".log")
        executable = self.settings.get("JAVA_HOME", os.environ.get("JAVA_HOME"))
        executable = str(Path(executable) / "bin" / ("java.exe" if os.name == "nt" else "java")) if executable else "java"
        # 不复用服务进程的 Spring 和外部对象仓配置，也不在命令行传递口令。
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("SPRING_", "ERP_REPORT_", "STORAGE_", "DB_"))}
        environment.update({key: value for key, value in self.settings.items()
                            if key in {"DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_SSL_MODE", "UPGRADE_DIAGNOSTICS"}})
        environment.update(self.test_environment)
        environment["REDIS_PASSWORD"] = ""
        command = [executable, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                   "-Dloader.main=" + UPGRADE_MAIN,
                   "-cp", str(self.jar), LAUNCHER, action, database, self.server_id, str(objects), str(report)]
        if self.temp_dir is not None:
            command.insert(1, "-Djava.io.tmpdir=" + str(self.temp_dir))
        with log.open("wb") as output:
            result = subprocess.run(command, env=environment, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise UpgradeError(f"步骤 {name} 失败，请查看 {log.name}")
        return load_json(report)

    def preflight(self, name, frozen=False):
        schema = self.java("schema", self.source, self.objects, name + "-schema")
        if not SOURCE_MIN <= schema["version"] <= SOURCE_MAX:
            raise UpgradeError("源库须是 V87 至 V97 的最后一个 1.x 版本或后续候选")
        jobs = running_work(self.mysql, self.source)
        retired = retired_calls(self.mysql, self.source)
        inspection = self.java("inspect", self.source, self.objects, name + "-inspection")
        blockers = []
        if any(jobs.values()):
            blockers.append("仍有运行中的文件任务或未释放的后台租约")
        if retired:
            blockers.append("最近 30 天仍有废弃接口调用")
        if inspection["mappingsMissing"] or inspection["invalidJournals"]:
            blockers.append("运营科目映射不完整")
        if any(check["differenceCount"] for check in inspection["reconciliation"]):
            blockers.append("源库业务或总账存在差异")
        if any(row["invalidReference"] for row in inspection["pending"]):
            blockers.append("待审单据存在失效的工作流关联")
        report = {"checkedAt": utc(), "source": self.source, "schema": schema, "jobs": jobs,
                  "retiredCalls": retired, "blocked": blockers, "maintenance": frozen, **inspection}
        atomic_json(self.work / (name + ".json"), report)
        if blockers:
            raise UpgradeError("升级前检查未通过，" + "、".join(blockers))
        return report

    def change_stage(self, phase, **details):
        self.state.update(phase=phase, changedAt=utc(), **details)
        atomic_json(self.state_path, self.state)

    @contextmanager
    def measure(self, step):
        began = utc("milliseconds")
        start_ns = time.monotonic_ns()
        status = "failed"
        try:
            yield
            status = "success"
        finally:
            attempt = {"step": step, "startedAt": began, "endedAt": utc("milliseconds"),
                       "elapsedMs": (time.monotonic_ns() - start_ns) // 1_000_000, "status": status}
            self.record_timing(attempt)

    def record_timing(self, attempt):
        path = self.work / "upgrade-timing.json"
        try:
            run_id = self.state["runId"]
            data = load_json(path) if path.exists() else {"format": 1, "runId": run_id, "steps": []}
            if data["runId"] != run_id:
                raise UpgradeError("耗时报告属于另一次演练")
            data["steps"].append(attempt)
            start = self.state["startedAt"]
            finish = self.state.get("activatedAt")
            window = {"startedAt": start, "mysqlFrozenAt": self.state.get("frozenAt"),
                      "activationCompletedAt": finish}
            if finish:
                elapsed = datetime.fromisoformat(finish) - datetime.fromisoformat(start)
                if elapsed.total_seconds() >= 0:
                    window["elapsedMs"] = round(elapsed.total_seconds() * 1_000)
            data["maintenanceWindow"] = window
            atomic_json(path, data)
        except (OSError, UpgradeError, ValueError, KeyError, TypeError, AttributeError):
            # 计时失败不改变核验、放行和回退结论，错误内容不写入公开输出。
            print("未能记录阶段耗时，请检查私有记录目录中的 upgrade-timing.json。", file=sys.stderr)

    def record_flyway_timing(self, target):
        try:
            source_version = int(load_json(self.work / "frozen-preflight.json")["schema"]["version"])
            rows = self.mysql.query(f"""
                SELECT installed_rank,version,script,execution_time,success
                  FROM flyway_schema_history
                 WHERE type='SQL' AND CAST(version AS UNSIGNED)>{source_version}
                 ORDER BY installed_rank
                """, target)
            migrations = [{"installedRank": int(rank), "version": version, "script": script,
                           "elapsedMs": int(elapsed), "status": "success" if ok == "1" else "failed"}
                          for rank, version, script, elapsed, ok in rows]
            atomic_json(self.work / "flyway-timing.json", {
                "format": 1, "runId": self.state["runId"], "sourceVersion": source_version,
                "collectedAt": utc("milliseconds"), "migrations": migrations})
        except (OSError, UpgradeError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
            print("未能记录 Flyway 耗时，请核对目标库 flyway_schema_history.execution_time。", file=sys.stderr)

    def hook(self, phase, path):
        try:
            command = json.loads(self.settings.get("UPGRADE_MAINTENANCE_HOOK", "null"))
        except json.JSONDecodeError:
            raise UpgradeError("维护命令必须是 JSON 参数数组") from None
        if not isinstance(command, list) or not command or not all(isinstance(arg, str) and arg for arg in command):
            raise UpgradeError("请配置 UPGRADE_MAINTENANCE_HOOK，接管 API、Worker 和入口停写")
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("DB_", "MYSQL_PWD"))}
        environment.update(self.test_environment)
        with (self.work / f"maintenance-{phase}.log").open("ab") as output:
            output.write(f"\n[{utc('milliseconds')}] {phase}\n".encode("utf-8"))
            output.flush()
            result = subprocess.run([*command, phase, str(path)], stdout=output, stderr=subprocess.STDOUT,
                                    env=environment, timeout=900)
        if result.returncode:
            raise UpgradeError("维护命令未通过：" + phase)

    def start_run(self):
        target = self.args.target_db
        target_objects = self.args.target_objects
        if not target or not target_objects or target == self.source:
            raise UpgradeError("需指定独立的候选库和候选对象目录")
        if not DATABASE.fullmatch(target) or self.mysql.occupied(target) or target_objects.exists():
            raise UpgradeError("候选库或对象仓已存在，或名称无效")
        target_objects = self.safe_target_objects(target_objects)
        if not self.args.dedicated_instance:
            raise UpgradeError("需确认停写作用于专用 MySQL 实例，提供 --dedicated-instance")
        server = self.mysql.server()
        if server[1] != "8.0.46" or server[2:5] != ["1", "ON", "0"] or server[6:] != ["0", "0", "1"]:
            raise UpgradeError("需使用开启 binlog、GTID 和持久配置的 MySQL 8.0.46 可写专用实例")
        self.require_no_background_writes()
        self.preflight("preflight")
        self.state = {"runId": uuid.uuid4().hex, "source": self.source, "serverId": self.server_id,
                      "objects": str(self.objects), "jarSha256": sha256(self.jar),
                      "target": target, "targetObjects": str(target_objects), "startedAt": utc("milliseconds")}
        self.change_stage("stopping")

    def require_no_background_writes(self):
        enabled_events = self.mysql.scalar("SELECT COUNT(*) FROM information_schema.events WHERE STATUS='ENABLED'")
        replication = self.mysql.scalar("SELECT COUNT(*) FROM performance_schema.replication_applier_status WHERE SERVICE_STATE='ON'")
        if enabled_events != "0" or replication != "0":
            raise UpgradeError("仍有 MySQL 事件或复制写入方，请纳入维护并停止")

    def drain(self):
        until = time.monotonic() + 60
        while self.mysql.sessions() or self.mysql.scalar("SELECT COUNT(*) FROM information_schema.innodb_trx") != "0":
            if time.monotonic() >= until:
                raise UpgradeError("仍有其他 MySQL 客户端或事务，停写未完成")
            time.sleep(2)

    def freeze(self):
        self.hook("stop", self.state_path)
        self.hook("stopped", self.state_path)
        self.drain()
        self.mysql.execute("SET PERSIST offline_mode=ON;")
        self.require_frozen()
        self.change_stage("frozen", frozenAt=utc("milliseconds"), cutoff=self.mysql.position())

    def require_frozen(self):
        server = self.mysql.server()
        if server[0] != self.server_id or server[4] != "1" or self.mysql.scalar("""
                SELECT COUNT(*) FROM performance_schema.persisted_variables
                WHERE VARIABLE_NAME='offline_mode' AND VARIABLE_VALUE='ON'
                """) != "1":
            raise UpgradeError("无法验证持久停写状态")
        self.hook("stopped", self.state_path)
        self.drain()

    def safe_target_objects(self, value):
        target = value.absolute()
        for protected in (self.objects.resolve(), self.work.resolve()):
            actual = target.resolve()
            if target == self.work or actual.is_relative_to(protected) or protected.is_relative_to(actual):
                raise UpgradeError("候选或回退对象仓须使用独立目录")
        return target

    def snapshot(self):
        cutoff = self.state["cutoff"]
        if self.mysql.position() != cutoff:
            raise UpgradeError("停写点的 binlog 或 GTID 已变化")
        frozen_report = self.preflight("frozen-preflight", frozen=True)
        # 每次重新备份使用新目录，残缺备份不会冒充原停写点。
        folder = self.work / ("backup-" + uuid.uuid4().hex)
        folder.mkdir(mode=0o700)
        data = capture_table_fingerprints(self.mysql, self.source)
        charset, collation = self.mysql.query("""
            SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME FROM information_schema.schemata WHERE schema_name=DATABASE()
            """, self.source)[0]
        manifest = object_manifest(self.objects)
        attachments = attachment_check(self.mysql, self.source, manifest)
        copy_objects(self.objects, folder / "objects", manifest)
        self.mysql.dump_to(self.source, folder / "database.sql")
        if self.mysql.position() != cutoff or object_manifest(self.objects) != manifest:
            raise UpgradeError("备份过程中数据库或私有对象发生变化")
        manifest_path = folder / "manifest.json"
        atomic_json(manifest_path, {"format": 1, "source": self.source, "frozenAt": self.state["frozenAt"],
                                    "serverId": self.server_id, "cutoff": cutoff, "charset": charset, "collation": collation,
                                    "databaseSha256": sha256(folder / "database.sql"), "objects": manifest, "tables": data,
                                    "activeAttachments": attachments, "sourceVersion": frozen_report["schema"]["version"]})
        self.change_stage("snapshot", backup=str(folder), manifestSha256=sha256(manifest_path))

    def backup(self):
        folder = Path(self.state["backup"])
        manifest_path = folder / "manifest.json"
        if sha256(manifest_path) != self.state["manifestSha256"]:
            raise UpgradeError("停写备份的清单摘要不符")
        manifest = load_json(manifest_path)
        if manifest["source"] != self.source or manifest["serverId"] != self.server_id or manifest["cutoff"] != self.state["cutoff"]:
            raise UpgradeError("停写备份的源库和冻结水位不匹配")
        if sha256(folder / "database.sql") != manifest["databaseSha256"] or object_manifest(folder / "objects") != manifest["objects"]:
            raise UpgradeError("停写备份的数据库或对象摘要不匹配")
        logs = {row[0]: int(row[1]) for row in self.mysql.query("SHOW BINARY LOGS")}
        if logs.get(manifest["cutoff"]["binlog"], 0) < manifest["cutoff"]["position"] or self.mysql.scalar(
                "SELECT GTID_SUBSET(" + literal(manifest["cutoff"]["gtid"]) + ",@@GLOBAL.gtid_executed)") != "1":
            raise UpgradeError("无法核对停写点的 binlog 文件和 GTID")
        return folder, manifest

    def restore(self, database, objects, name):
        folder, manifest = self.backup()
        if objects.exists():
            raise UpgradeError("目标对象仓已存在，禁止覆盖")
        self.mysql.restore(folder / "database.sql", database, manifest["charset"], manifest["collation"])
        copy_objects(folder / "objects", objects, manifest["objects"])
        verify_copy(self.mysql, database, manifest["tables"])
        if self.mysql.scalar("""
                SELECT COUNT(*) FROM information_schema.VIEW_TABLE_USAGE
                  WHERE VIEW_SCHEMA=DATABASE() AND TABLE_SCHEMA<>DATABASE()
                """, database) != "0":
            raise UpgradeError("恢复库的视图仍跨库引用，请核对定义")
        schema = self.java("schema", database, objects, name + "-schema")
        if schema["version"] != manifest["sourceVersion"] or attachment_check(
                self.mysql, database, manifest["objects"]) != manifest["activeAttachments"]:
            raise UpgradeError("恢复对象或 Flyway 版本与停写点不符")
        atomic_json(self.work / (name + ".json"), {"database": database, "objects": str(objects),
                    "restoreVerifiedAt": utc(), "verifiedTables": len(manifest["tables"]), "schema": schema})

    def journal(self, database, step, result):
        row = [literal(self.state["runId"]), literal(step), literal(json.dumps(result, ensure_ascii=False))]
        self.mysql.execute("INSERT INTO upgrade_log(run_id,step,details) VALUES(" + ",".join(row)
                           + ") ON DUPLICATE KEY UPDATE completed_at=completed_at", database)

    def migrate(self, target, objects):
        with self.measure("flyway"):
            try:
                schema = self.java("migrate", target, objects, "flyway")
                self.journal(target, "flyway", {**schema, "cutoff": self.state["cutoff"],
                             "manifestSha256": self.state["manifestSha256"]})
                self.change_stage("migrated")
            finally:
                self.record_flyway_timing(target)
        with self.measure("data-conversion"):
            return self.convert_data(target, objects)

    def convert_data(self, target, objects):
        workflows = self.java("01-default-workflows", target, objects, "01-default-workflows")
        self.journal(target, "01-default-workflows", {"bindings": workflows["bindings"], "resumed": len(workflows["resumed"])})
        scopes = self.java("scope", target, objects, "02-data-scope-after")
        baseline = load_json(self.work / "frozen-preflight.json")
        conversion = compare_scopes(baseline["scopes"], scopes)
        atomic_json(self.work / "02-data-scope.json", conversion)
        if conversion["differences"]:
            raise UpgradeError("数据范围转换结果存在差异")
        self.journal(target, "02-data-scope", {"contexts": len(scopes)})
        mapping = permission_codes()
        permissions = convert_permissions(self.mysql, target, mapping)
        permissions.update(compare_permissions(self.mysql, self.source, target, mapping))
        atomic_json(self.work / "03-permission-codes.json", permissions)
        self.journal(target, "03-permission-codes", {"legacyGrants": permissions["legacyGrants"],
                     "openApplications": permissions["openApplications"]})
        return workflows, permissions

    def upgrade(self):
        if not self.state:
            self.start_run()
        if self.state["phase"] == "stopping":
            with self.measure("freeze"):
                self.freeze()
        self.require_frozen()
        if self.state["phase"] == "frozen":
            with self.measure("backup"):
                self.snapshot()
        target, objects = self.state["target"], Path(self.state["targetObjects"])
        if self.state["phase"] == "snapshot":
            with self.measure("restore"):
                self.restore(target, objects, "restore-before-migration")
                self.change_stage("restored")
        if self.state["phase"] not in {"restored", "migrated"}:
            raise UpgradeError("当前阶段无需再次迁移，请使用相应的放行或回退入口")
        workflows, permissions = self.migrate(target, objects)
        with self.measure("verification"):
            self.verify_upgrade(target, objects, workflows, permissions)

    def verify_upgrade(self, target, objects, workflows, permissions):
        inspection = self.java("inspect", target, objects, "upgrade-inspection")
        flow_check = self.java("workflow-check", target, objects, "workflow-check")
        if inspection["mappingsMissing"] or inspection["invalidJournals"] or any(
                check["differenceCount"] for check in inspection["reconciliation"]):
            raise UpgradeError("目标库的科目映射或对账未通过")
        if flow_check["expectedBindings"] != len(flow_check["bindings"]) or flow_check["pendingWithoutInstance"]:
            raise UpgradeError("默认流程绑定或待审单据续接未通过")
        _, manifest = self.backup()
        if object_manifest(objects) != manifest["objects"]:
            raise UpgradeError("目标对象仓与停写备份不一致")
        tables = verify_business(self.mysql, self.source, target, manifest["tables"])
        atomic_json(self.work / "upgrade-verification.json", {"database": target, "tables": tables, "workflow": flow_check,
                    "resumed": len(workflows["resumed"]), "reconciliationChecks": len(inspection["reconciliation"]),
                    "permissionGrants": permissions["legacyGrants"], "completedAt": utc()})
        self.journal(target, "verified", {"businessTables": len(tables), "bindings": len(flow_check["bindings"]),
                                         "reconciliationChecks": len(inspection["reconciliation"])})
        self.require_frozen()
        self.ready(target, objects, 2, "ready", manifest["objects"])

    def ready(self, database, objects, major, phase, files):
        path = self.work / "handoff.json"
        atomic_json(path, {"database": database, "objectRoot": str(objects), "apiMajor": major,
                    "source": self.source, "runId": self.state["runId"], "candidateJar": str(self.jar) if major == 2 else None,
                    "reportDirectory": str(self.work)})
        self.change_stage(phase, readyPosition=self.mysql.position(), handoffSha256=sha256(path), handoffObjects=files)

    def rollback(self):
        if self.state.get("phase") == "opening":
            raise UpgradeError("上次放行未完成，请先执行 recover 核对停写和交易水位")
        if not self.state or self.state["phase"] in {"active", "rollback-active"}:
            raise UpgradeError("回退须在同一演练的停写窗口内进行")
        if self.state["phase"] == "stopping":
            with self.measure("freeze"):
                self.freeze()
        self.require_frozen()
        if self.state["phase"] in {"frozen", "snapshot"}:
            with self.measure("rollback-verification"):
                return self.verify_rollback_source()
        database = self.args.rollback_db
        if not database or not DATABASE.fullmatch(database) or database in {self.source, self.state["target"]} \
                or self.args.rollback_objects is None:
            raise UpgradeError("请为回退指定新的恢复库和对象目录")
        objects = self.safe_target_objects(self.args.rollback_objects)
        with self.measure("rollback-restore"):
            self.restore(database, objects, "rollback-restore")
        with self.measure("rollback-verification"):
            self.verify_rollback_restore(database, objects)

    def verify_rollback_source(self):
        if self.state["phase"] == "frozen" and self.mysql.position() != self.state["cutoff"]:
            raise UpgradeError("原 1.x 停写水位已变化，请先核对原库")
        if self.state["phase"] == "snapshot":
            _, manifest = self.backup()
            verify_copy(self.mysql, self.source, manifest["tables"])
        report = self.preflight("rollback-source", frozen=True)
        self.ready(self.source, self.objects, 1, "rollback-ready", object_manifest(self.objects))
        return report

    def verify_rollback_restore(self, database, objects):
        original = load_json(self.work / "frozen-preflight.json")
        restored = self.java("inspect", database, objects, "rollback-inspection")
        for area in ("reconciliation", "pending", "scopes", "mappingsMissing", "invalidJournals"):
            if original[area] != restored[area]:
                raise UpgradeError("1.x 回退后的权限、业务或账务数据不一致")
        self.require_frozen()
        _, manifest = self.backup()
        self.ready(database, objects, 1, "rollback-ready", manifest["objects"])

    def finish(self):
        phase = self.state.get("phase")
        if phase == "opening":
            raise UpgradeError("上次放行未完成，请先执行 recover")
        if phase not in READY_PHASES:
            raise UpgradeError("当前升级或回退尚未通过核验")
        if phase == "ready" and not self.args.approve_scopes:
            raise UpgradeError("请审阅 02-data-scope.json 后提供 --approve-scopes")
        self.require_frozen()
        path = self.verified_handoff(phase)
        if self.mysql.position() != self.state["readyPosition"]:
            raise UpgradeError("核验后的 MySQL 水位已变化")
        with self.measure("activation" if phase == "ready" else "rollback-activation"):
            self.activate(phase, path)

    def verified_handoff(self, phase):
        path = self.work / "handoff.json"
        if sha256(path) != self.state["handoffSha256"]:
            raise UpgradeError("切换目标的交接清单已变化")
        target = load_json(path)
        if target["apiMajor"] != (2 if phase == "ready" else 1):
            raise UpgradeError("切换目标的版本与停写核验状态不符")
        objects = Path(target["objectRoot"])
        if configured_test_root() is not None:
            checked_test_output(objects)
        if object_manifest(objects) != self.state["handoffObjects"]:
            raise UpgradeError("核验后的对象仓已变化")
        return path

    def activate(self, phase, path):
        self.change_stage("opening", previousReady=phase)
        try:
            self.mysql.execute("SET PERSIST offline_mode=OFF;")
            self.hook("activate", path)
        except (UpgradeError, OSError, subprocess.TimeoutExpired) as failure:
            try:
                self.recover()
            except (UpgradeError, OSError, ValueError, subprocess.TimeoutExpired) as recovery:
                raise UpgradeError(f"放行失败，恢复检查未通过。{recovery}") from failure
            raise
        self.change_stage("active" if phase == "ready" else "rollback-active", activatedAt=utc("milliseconds"))

    def recover(self):
        phase = self.state.get("phase")
        if phase in READY_PHASES:
            self.change_stage("opening", previousReady=phase)
        elif phase != "opening":
            raise UpgradeError("当前状态不支持放行中断恢复，已接收的交易须先保全和核对")
        with self.measure("activation-recovery"):
            self.recover_activation(self.state.get("previousReady"))

    def recover_activation(self, destination):
        report_path = self.work / f"activation-recovery-{uuid.uuid4().hex}.json"
        report = {"runId": self.state["runId"], "startedAt": utc("milliseconds"),
                  "serverId": self.server_id, "status": "running",
                  "expectedPosition": self.state.get("readyPosition"), "resumePhase": destination}
        atomic_json(report_path, report)
        try:
            report["step"] = "verify-instance"
            server = self.mysql.server()
            if server[:4] != [self.server_id, "8.0.46", "1", "ON"] or server[6:] != ["0", "0", "1"]:
                raise UpgradeError("实例身份或持久停写、binlog、GTID 配置已变化，请先核对现场状态")
            report["step"] = "stop-writers"
            try:
                self.hook("stop", self.state_path)
            finally:
                # 写入方状态不明时也先拦住新连接，后续核验仍须全部通过才能恢复。
                self.mysql.execute("SET PERSIST offline_mode=ON;")
            self.require_frozen()
            self.require_no_background_writes()
            report["step"] = "verify-watermark"
            report["observedPosition"] = self.mysql.position()
            if destination not in READY_PHASES or report["observedPosition"] != report["expectedPosition"]:
                raise UpgradeError("无法证明放行后水位未变化，请保留当前数据、对象及 binlog，人工对账后决定回退")
            report["step"] = "verify-handoff"
            self.verified_handoff(destination)
            report["step"] = "verify-watermark"
            report["observedPosition"] = self.mysql.position()
            if report["observedPosition"] != report["expectedPosition"]:
                raise UpgradeError("交接核验期间 MySQL 水位已变化，请保留数据并人工对账")
        except (UpgradeError, OSError, ValueError, subprocess.TimeoutExpired):
            report.update(status="blocked", checkedAt=utc("milliseconds"))
            atomic_json(report_path, report)
            raise
        report.update(status="verified", step="complete", checkedAt=utc("milliseconds"))
        atomic_json(report_path, report)
        self.change_stage(destination, activationRecovery=report_path.name)


def arguments():
    parser = argparse.ArgumentParser(description="柒衡 ERP 2.0 停写升级与回退")
    parser.add_argument("action", choices=("preflight", "upgrade", "rollback", "finish", "recover"))
    for option in ("settings", "jar", "objects", "work-dir"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--mysqldump", default="mysqldump")
    parser.add_argument("--target-db")
    parser.add_argument("--target-objects", type=Path)
    parser.add_argument("--rollback-db")
    parser.add_argument("--rollback-objects", type=Path)
    parser.add_argument("--dedicated-instance", action="store_true")
    parser.add_argument("--approve-scopes", action="store_true")
    return parser.parse_args()


def main():
    args = arguments()
    try:
        task = Upgrade(args)
        if args.action == "preflight":
            task.preflight("preflight")
        else:
            with task.mysql.lock():
                task = Upgrade(args)
                getattr(task, args.action)()
        print("步骤通过，报告位于 " + str(task.work))
    except (UpgradeError, OSError, ValueError, subprocess.TimeoutExpired) as failure:
        print("升级工具已停止。", str(failure), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
