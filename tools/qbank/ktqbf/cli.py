"""校验 / 检视 / 解包 / 指纹 命令行入口（validate / inspect / unpack / digest / answer-hash）。"""

from __future__ import annotations

import argparse
import json
import os
import sys

from .package import PackageError, read_package, safe_relpath, sha256_bytes
from .validator import validate_package

SPEC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "spec")


def _print_human(report) -> None:
    if report.ok:
        print(f"OK  {report.package}")
    else:
        print(f"FAILED  {report.package}")
    if report.schema_version:
        print(f"    schema={report.schema_version}  bankId={report.bank_id}")
        print(f"    title={report.title}")
    c = report.counts
    print(
        f"    题目 {c.get('questions', 0)}：单选 {c.get('singleChoice', 0)} / 多选 {c.get('multipleChoice', 0)}"
        f" / 判断 {c.get('trueFalse', 0)} / 简答 {c.get('shortAnswer', 0)}；媒体 {c.get('assets', 0)}"
    )
    for loc, cov in report.locales.items():
        print(f"    语言 {loc}: {cov['covered']}/{cov['total']}")
    if report.enhancements:
        print(f"    增强：{report.enhancements}")
    p = report.provenance
    if p:
        # "未标注"单独一位，不与各档相加：它说的是"作者没说"，不是"作者选了某一档"。
        by = p.get("byOrigin") or {}
        print(
            f"    来源 {p.get('sources', 0)} 条：原创 {by.get('authored', 0)} / 改编 {by.get('adapted', 0)}"
            f" / 引用 {by.get('imported', 0)} / AI 参与 {by.get('ai-assisted', 0)}"
            f"；来源未标注 {p.get('originUndeclared', 0)}；{p.get('withSource', 0)} 题指向具体来源"
        )
    if report.digest:
        print(f"    digest={report.digest}")
    print(
        f"    内容摘要={report.content_digest}" if report.content_digest
        else "    内容摘要=（未提供，老包或未用打包器生成）"
    )
    for e in report.errors:
        loc = f" ({e.path}:{e.line})" if e.path and e.line else f" ({e.path})" if e.path else ""
        print(f"  ERROR [{e.code}] {e.message}{loc}")
    for w in report.warnings:
        print(f"  WARN  [{w.code}] {w.message}")


def cmd_validate(args) -> int:
    report = validate_package(args.package, SPEC_DIR)
    if args.json:
        print(json.dumps(report.as_dict(), ensure_ascii=False, indent=2))
    else:
        _print_human(report)
    return 0 if report.ok else 1


def cmd_inspect(args) -> int:
    """不解压、不校验，只列出结构和摘要，用于快速看一个包是什么。"""
    try:
        contents = read_package(args.package)
    except PackageError as exc:
        print(f"FAILED [{exc.code}] {exc.message}")
        return 1
    m = contents.manifest
    if args.json:
        print(
            json.dumps(
                {
                    "bankId": m.get("bankId"),
                    "title": m.get("title"),
                    "schemaVersion": m.get("schemaVersion"),
                    "sourceLocale": m.get("sourceLocale"),
                    "supportedLocales": m.get("supportedLocales"),
                    "entries": [{"path": p, "bytes": len(b)} for p, b in sorted(contents.entries.items())],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    print(f"{m.get('title')}  ({m.get('bankId')})")
    print(f"  schemaVersion={m.get('schemaVersion')}  sourceLocale={m.get('sourceLocale')}")
    print(f"  locales={', '.join(m.get('supportedLocales') or [])}")
    print(f"  author={(m.get('author') or {}).get('displayName')}")
    print(f"  contentDigest={m.get('contentDigest') or '（未提供）'}")
    sources = m.get("sources") or []
    if not sources:
        print("  出处：未声明")
    else:
        titles = "、".join(str(s.get("title", "?")) for s in sources[:5])
        if len(sources) > 5:
            titles += "…"
        print(f"  出处 {len(sources)} 处：{titles}")
    for p, b in sorted(contents.entries.items()):
        print(f"  {len(b):>9}  {p}")
    return 0


def cmd_unpack(args) -> int:
    """安全解包。路径穿越在上游 read_package 已拦截，这里再兜一层，永不信任条目名。"""
    try:
        contents = read_package(args.package)
    except PackageError as exc:
        print(f"FAILED [{exc.code}] {exc.message}")
        return 1
    root = os.path.abspath(args.output_dir)
    os.makedirs(root, exist_ok=True)
    written = 0
    for path, data in sorted(contents.all_entries.items()):
        rel = safe_relpath(path)
        target = os.path.abspath(os.path.join(root, *rel.split("/")))
        if not target.startswith(root + os.sep) and target != root:
            print(f"FAILED [ZIP_PATH_TRAVERSAL] {path}")
            return 1
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as fh:
            fh.write(data)
        written += 1
    print(f"OK  解包 {written} 个文件 → {root}")
    return 0


def cmd_digest(args) -> int:
    try:
        contents = read_package(args.package)
    except PackageError as exc:
        print(f"FAILED [{exc.code}] {exc.message}")
        return 1
    from .package import normalize_package_digest

    print(normalize_package_digest(contents.entries))
    return 0


def _read_authoring_ndjson(path: str) -> list[dict]:
    """读 authoring 源目录里的 NDJSON（与校验器同一套宽松规则：跳过空行与 # 注释行）。"""
    out: list[dict] = []
    with open(path, "r", encoding="utf-8-sig") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            obj = json.loads(line)
            if isinstance(obj, dict):
                out.append(obj)
    return out


def cmd_answer_hash(args) -> int:
    """给 authoring 目录里的每条增强资料算 answerHash。

    作者不必手抄哈希（和 manifest.files 同理，手抄几乎必错）。输出里的 status：
    OK = 记录与当前题干/答案一致；MISSING = 还没写；STALE = 题干或答案改过、旧指纹已失效。
    """
    from .answer_hash import compute_answer_hash  # 延迟导入，避免 --help 时拉起无关模块

    root = args.authoring_dir
    qdir = os.path.join(root, "questions")
    if not os.path.isdir(qdir):
        print(f"FAILED 找不到 {qdir}（这看起来不是一个 authoring 目录）")
        return 1

    questions: dict[str, dict] = {}
    for name in sorted(os.listdir(qdir)):
        if name.endswith(".ndjson"):
            for obj in _read_authoring_ndjson(os.path.join(qdir, name)):
                questions[obj.get("questionId")] = obj

    localizations: dict[tuple[str, str], dict] = {}
    ldir = os.path.join(root, "locales")
    if os.path.isdir(ldir):
        for loc in sorted(os.listdir(ldir)):
            f = os.path.join(ldir, loc, "questions.ndjson")
            if os.path.isfile(f):
                for obj in _read_authoring_ndjson(f):
                    localizations[(loc, obj.get("questionId"))] = obj

    enh_index = os.path.join(root, "enhancements", "index.ndjson")
    if not os.path.isfile(enh_index):
        print(f"OK   {root} 没有增强资料（enhancements/index.ndjson 不存在），无需 answerHash")
        return 0

    rows: list[dict] = []
    uncomputable = 0
    for rec in _read_authoring_ndjson(enh_index):
        qid = rec.get("questionId", "")
        loc = rec.get("locale", "")
        q = questions.get(qid)
        base = localizations.get((loc, qid))
        if q is None or base is None:
            rows.append(
                {
                    "questionId": qid,
                    "locale": loc,
                    "kind": rec.get("kind", ""),
                    "answerHash": "",
                    "declared": rec.get("answerHash", ""),
                    "status": "NO_BASE",
                }
            )
            uncomputable += 1
            continue
        expected = compute_answer_hash(q, base)
        declared = rec.get("answerHash", "") or ""
        if not declared:
            status = "MISSING"
        elif declared == expected:
            status = "OK"
        else:
            status = "STALE"
        rows.append(
            {
                "questionId": qid,
                "locale": loc,
                "kind": rec.get("kind", ""),
                "answerHash": expected,
                "declared": declared,
                "status": status,
            }
        )

    if args.json:
        print(json.dumps({"ok": uncomputable == 0, "records": rows}, ensure_ascii=False, indent=2))
    else:
        if not rows:
            print("OK   enhancements/index.ndjson 里没有记录")
        for r in rows:
            mark = {"OK": "  ok  ", "MISSING": " 缺  ", "STALE": "过期 ", "NO_BASE": "无题 "}[r["status"]]
            print(f"{mark} {r['questionId']:<24} {r['locale']:<10} {r['kind']:<14} {r['answerHash'] or r['declared'] or '-'}")
        if uncomputable:
            print(f"\nWARN  {uncomputable} 条增强绑定不到题目文案，无法计算指纹（先补齐结构层/文案层）")
    return 0 if uncomputable == 0 else 1


def main(argv: list[str] | None = None) -> int:
    # 兼容写法：`validate_qbank.py foo.qbank`（不带子命令）
    if argv is None:
        argv = sys.argv[1:]
    if argv and argv[0].endswith(".qbank"):
        argv = ["validate"] + list(argv)

    parser = argparse.ArgumentParser(prog="validate_qbank", description="KT-QBF 1.0 包校验 / 检视 / 解包")
    sub = parser.add_subparsers(dest="cmd")

    p_validate = sub.add_parser("validate", help="严格校验")
    p_validate.add_argument("package")
    p_validate.add_argument("--json", action="store_true")
    p_validate.set_defaults(func=cmd_validate)

    p_inspect = sub.add_parser("inspect", help="列出结构与摘要")
    p_inspect.add_argument("package")
    p_inspect.add_argument("--json", action="store_true")
    p_inspect.set_defaults(func=cmd_inspect)

    p_unpack = sub.add_parser("unpack", help="安全解包到目录")
    p_unpack.add_argument("package")
    p_unpack.add_argument("output_dir")
    p_unpack.set_defaults(func=cmd_unpack)

    p_digest = sub.add_parser("digest", help="打印规范化 digest")
    p_digest.add_argument("package")
    p_digest.set_defaults(func=cmd_digest)

    p_answer = sub.add_parser("answer-hash", help="给 authoring 目录里的增强资料算 answerHash")
    p_answer.add_argument("authoring_dir")
    p_answer.add_argument("--json", action="store_true")
    p_answer.set_defaults(func=cmd_answer_hash)

    args = parser.parse_args(argv)
    # 子命令自带 package 参数，不再需要父级同名位置参数（会互相覆盖）
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
