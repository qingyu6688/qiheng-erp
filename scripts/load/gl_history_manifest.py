"""从已完成的正式总账造数证据生成采集清单，不代替数据库及记账验收。"""

import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import sys

if __name__ == "__main__":
    sys.dont_write_bytecode = True
if __package__:
    from .gl_capacity import local_settings, positive_id, read_json, require, validate_manifest
    from .local_test_storage import checked_test_output
else:
    from gl_capacity import local_settings, positive_id, read_json, require, validate_manifest
    from local_test_storage import checked_test_output

TARGET_PER_PERIOD = 100_000
MIN_POST_SAMPLES = 100
HASH_CHUNK_BYTES = 1_048_576
LINES_PER_VOUCHER = 2
RECEIPT_AMOUNT = Decimal("2.00")
PAYMENT_AMOUNT = Decimal("1.00")


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(HASH_CHUNK_BYTES), b""):
            result.update(block)
    return result.hexdigest()


def exact_integer(value, expected):
    return type(value) is int and value == expected


def generated(value, stage, period_count):
    require(isinstance(value, dict) and value.get("stage") == stage, "造数阶段不符")
    require(value.get("complete") is True and exact_integer(value.get("targetPerPeriod"), TARGET_PER_PERIOD),
            "仅接受每期十万张且已经完成的正式造数证据")
    for key in ("tenantId", "companyId"):
        require(positive_id(value.get(key)), "造数证据的租户或公司编号无效")
    periods = value.get("periods")
    require(isinstance(periods, list) and len(periods) == period_count, "造数证据的期间数量不符")
    normalized = []
    receipt_count, payment_count = (TARGET_PER_PERIOD + 1) // 2, TARGET_PER_PERIOD // 2
    expected_amount = str(receipt_count * RECEIPT_AMOUNT + payment_count * PAYMENT_AMOUNT)
    for row in periods:
        require(isinstance(row, dict), "造数期间记录无效")
        identifier = row.get("id")
        require(type(identifier) in (int, str) and positive_id(str(identifier)), "期间编号无效，不能使用浮点数")
        for key in ("journals", "deliveredLedger", "deliveredGl", "posted"):
            require(exact_integer(row.get(key), TARGET_PER_PERIOD), "业务、两级消费及总账数量必须全部达到十万")
        require(exact_integer(row.get("confirmedReceipts"), receipt_count)
                and exact_integer(row.get("confirmedPayments"), payment_count), "双人确认收付数量不符")
        require(exact_integer(row.get("lines"), TARGET_PER_PERIOD * LINES_PER_VOUCHER)
                and type(row.get("balances")) is int and row["balances"] > 0, "总账明细或余额样本不足")
        require(exact_integer(row.get("mismatchedLines"), 0), "来源逐行核对仍有差异")
        require(row.get("debit") == expected_amount and row.get("credit") == expected_amount, "借贷金额与真实收付配比不符")
        normalized.append({"id": str(identifier), "period": row.get("period")})
    samples = value.get("postVouchers")
    require(isinstance(samples, list) and len(samples) >= MIN_POST_SAMPLES, "至少需要一百张已复核手工样本")
    require(all(isinstance(row, dict) and positive_id(row.get("id")) and type(row.get("version")) is int
                and row["version"] >= 0 for row in samples), "手工样本编号或版本无效")
    require(len({row["id"] for row in samples}) == len(samples), "手工样本编号重复")
    return normalized, [{"id": row["id"], "version": row["version"]} for row in samples]


def identity(settings, run, environment, artifact_sha, environment_sha):
    require(all(isinstance(value, dict) for value in (settings, run, environment)), "配置及环境记录必须是对象")
    source = settings.get("ERP_O30_SOURCE", "")
    require(bool(re.fullmatch(r"[a-f0-9]{40}", source)), "缺少完整固定源码提交")
    require(bool(re.fullmatch(r"[a-z0-9_-]{1,64}", settings.get("ERP_O30_DATASET", ""))), "数据集标识无效")
    run_id = settings.get("ERP_O30_RUN", "")
    require(bool(re.fullmatch(r"[a-f0-9]{12}", run_id)) and run.get("runId") == run_id, "配置与容量运行编号不符")
    database = run.get("database", "")
    require(bool(re.fullmatch(r"erp_load_o30_[a-z0-9]+", database))
            and settings.get("TEST_DB_NAME") == database, "必须使用同一个专用总账容量库")
    require(artifact_sha == settings.get("ERP_O30_ARTIFACT") == run.get("artifactSha256")
            == environment.get("artifactSha256"), "候选包实际摘要与配置不符")
    require(environment_sha == settings.get("ERP_O30_ENVIRONMENT")
            and environment.get("sourceCommit") == source, "运行环境记录或源码提交不符")
    start = settings.get("ERP_O30_START", "")
    require(bool(re.fullmatch(r"[0-9]{4}-[0-9]{2}", start)), "首期配置必须使用YYYY-MM")
    return {"dataset": settings["ERP_O30_DATASET"], "sourceCommit": source,
            "artifactSha256": artifact_sha, "environmentSha256": environment_sha,
            "tenantCode": "LOAD-" + run_id + "-0", "startPeriod": start.replace("-", "")}


def build_manifest(current, first, phase, expected):
    require(phase in ("month01", "month12"), "采集阶段无效")
    if phase == "month01":
        require(first is None, "首期清单不接受其它阶段的造数证据")
        periods, samples = generated(current, "first", 1)
    else:
        require(first is not None, "末期清单需要原始首期造数证据")
        before, previous_samples = generated(first, "first", 1)
        later, samples = generated(current, "remaining", 11)
        for key in ("tenantId", "companyId", "tenantCode"):
            require(first.get(key) == current.get(key), "首末期的企业或公司不符")
        require(not {row["id"] for row in previous_samples} & {row["id"] for row in samples}, "首末期不能复用记账样本")
        periods = before + later
    require(current.get("tenantCode") == expected["tenantCode"], "造数企业不属于本轮运行编号")
    require(periods[0]["period"] == expected["startPeriod"], "首期与固定配置不符")
    result = {key: expected[key] for key in ("dataset", "sourceCommit", "artifactSha256", "environmentSha256", "tenantCode")}
    result.update(tenantId=current["tenantId"], companyId=current["companyId"], phase=phase,
                  periods=periods, postVouchers=samples)
    return validate_manifest(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("month01", "month12"), required=True)
    parser.add_argument("--generation", required=True)
    parser.add_argument("--first-generation")
    parser.add_argument("--settings", required=True)
    parser.add_argument("--run-file", required=True)
    parser.add_argument("--environment-record", required=True)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = checked_test_output(args.output)
    require(output.parent.is_dir() and not output.exists(), "输出目录必须已存在，且不能覆盖原清单")
    expected = identity(local_settings(args.settings), read_json(args.run_file), read_json(args.environment_record),
                        digest(args.artifact), digest(args.environment_record))
    current = read_json(args.generation)
    first = read_json(args.first_generation) if args.first_generation else None
    result = build_manifest(current, first, args.phase, expected)
    result["generationSha256"] = digest(args.generation)
    if args.first_generation:
        result["firstGenerationSha256"] = digest(args.first_generation)
    result["formatterSha256"] = digest(__file__)
    # 独占创建，防止校验和写入之间覆盖另一轮已经保存的清单。
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
    print("正式采集清单已生成；仍须读取真实数据库并执行记账、队列、对账及完整规模验收。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print("总账采集清单未生成，请检查正式证据及固定构建配置。", file=sys.stderr)
        print(str(failure) if isinstance(failure, ValueError) else "输入文件或字段不完整", file=sys.stderr)
        raise SystemExit(2)
