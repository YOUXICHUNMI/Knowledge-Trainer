#!/usr/bin/env python3
"""`.qbext` 坏包回归：每条用例只破坏一个规则，断言稳定错误码。

跑法：python tools/qbank/tests/run_bad_qbext.py

约定：用例函数返回 (entries, manifest, auto_hash)。
`auto_hash=True` 时由 runner 按实际字节重算清单哈希 —— 否则"改了内容"会先撞上 HASH_MISMATCH，
测不到后面的语义规则；只有专门测清单的用例才置 False。
"""

from __future__ import annotations

import copy
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ktqbf.package import sha256_bytes, write_package
from ktqbf.qbext import (
    ANNOTATIONS_FILE,
    FORMAT_ID,
    RELATIONS_FILE,
    validate_entries,
    validate_package,
)

BANK_A = "3f2b1d7a-9c4e-4a61-8b52-0d7e6c91a4f5"
BANK_B = "7c1e5a92-4b3d-4f80-9a6e-2d5b8c1f0e37"

GOOD_RELATION = {
    "relationId": "r1",
    "source": {"questionId": "q1"},
    "target": {"bankId": BANK_B, "questionId": "t1"},
    "relation": "prerequisite",
    "dependency": "hard",
}
GOOD_ANNOTATION = {"annotationId": "a1", "questionId": "q1", "title": "标题", "bodyMarkdown": "正文"}


def base():
    entries = {
        RELATIONS_FILE: (json.dumps(GOOD_RELATION, ensure_ascii=False) + "\n").encode(),
        ANNOTATIONS_FILE: (json.dumps(GOOD_ANNOTATION, ensure_ascii=False) + "\n").encode(),
    }
    manifest = {
        "schemaVersion": "1.0.0",
        "format": FORMAT_ID,
        "packageId": "demo-ext",
        "name": "示例扩展",
        "version": "1.0.0",
        "author": {"displayName": "测试"},
        "binds": {"bankId": BANK_A, "bankTitle": "生理学"},
        "files": [],
    }
    return entries, manifest


def stream(*objects) -> bytes:
    return "".join(json.dumps(o, ensure_ascii=False) + "\n" for o in objects).encode()


CASES = []


def case(name: str, code: str):
    def wrap(fn):
        CASES.append((name, code, fn))
        return fn

    return wrap


@case("format 写错（qbank 改名）", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["format"] = "knowledge-trainer-question-bank"
    return entries, manifest, True


@case("schemaVersion 不支持", "SCHEMA_VERSION_UNSUPPORTED")
def _(entries, manifest):
    manifest["schemaVersion"] = "2.0.0"
    return entries, manifest, True


@case("绑定不是 UUID", "BINDING_INVALID")
def _(entries, manifest):
    manifest["binds"]["bankId"] = "not-a-uuid"
    return entries, manifest, True


@case("版本号不是 主.次.修", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["version"] = "1.0"
    return entries, manifest, True


@case("包内有未列入清单的文件", "FILE_NOT_LISTED")
def _(entries, manifest):
    entries = dict(entries)
    entries["relations/extra.ndjson"] = b"{}\n"
    return entries, manifest, False


@case("清单哈希不符", "HASH_MISMATCH")
def _(entries, manifest):
    return entries, manifest, True


@case("清单声明了不存在的文件", "FILE_MISSING")
def _(entries, manifest):
    manifest = copy.deepcopy(manifest)
    manifest["files"].append({"path": "relations/ghost.ndjson", "sha256": "1" * 64})
    return entries, manifest, False


@case("关系起点属于别的题库", "EXT_FOREIGN_SOURCE")
def _(entries, manifest):
    bad = dict(GOOD_RELATION, relationId="r2", source={"bankId": BANK_B, "questionId": "q9"})
    entries[RELATIONS_FILE] = stream(GOOD_RELATION, bad)
    return entries, manifest, True


@case("对称关系挂硬依赖", "EXT_HARD_NEEDS_DIRECTION")
def _(entries, manifest):
    entries[RELATIONS_FILE] = stream(dict(GOOD_RELATION, relation="related", dependency="hard"))
    return entries, manifest, True


@case("关系自指", "EXT_RELATION_SELF")
def _(entries, manifest):
    entries[RELATIONS_FILE] = stream(dict(GOOD_RELATION, target={"bankId": BANK_A, "questionId": "q1"}))
    return entries, manifest, True


@case("relationId 重复", "EXT_RELATION_DUPLICATE")
def _(entries, manifest):
    entries[RELATIONS_FILE] = stream(GOOD_RELATION, GOOD_RELATION)
    return entries, manifest, True


@case("既没有关系也没有注释", "EXT_EMPTY")
def _(entries, manifest):
    return {}, manifest, True


@case("注释正文为空", "SCHEMA_INVALID")
def _(entries, manifest):
    entries[ANNOTATIONS_FILE] = stream(dict(GOOD_ANNOTATION, bodyMarkdown="   "))
    return entries, manifest, True


@case("坏行不是 JSON", "NDJSON_INVALID")
def _(entries, manifest):
    entries[RELATIONS_FILE] = b'{"relationId":"r1", not json\n'
    return entries, manifest, True


@case("关系超过上限", "EXT_TOO_MANY_RELATIONS")
def _(entries, manifest):
    entries[RELATIONS_FILE] = stream(*(dict(GOOD_RELATION, relationId=f"r{i}") for i in range(5001)))
    return entries, manifest, True


def refresh_hashes(entries: dict, manifest: dict) -> dict:
    manifest = copy.deepcopy(manifest)
    manifest["files"] = [{"path": p, "sha256": sha256_bytes(b)} for p, b in sorted(entries.items())]
    return manifest


def run() -> int:
    passed = 0
    failed = 0

    for name, expected, mutate in CASES:
        entries, manifest = base()
        entries, manifest, auto_hash = mutate(entries, manifest)
        if manifest is None:
            report = validate_entries(entries, {})
        else:
            if auto_hash:
                manifest = refresh_hashes(entries, manifest)
                if name == "清单哈希不符":
                    manifest["files"][0]["sha256"] = "0" * 64
            report = validate_entries(entries, manifest)
        codes = [e["code"] for e in report.errors]
        if report.ok or expected not in codes:
            failed += 1
            print(f"FAIL  {name}: 期望 {expected}，实际 {codes or '通过'}")
        else:
            passed += 1

    # ZIP 层：扩展包不允许 questions/ 这种别家目录
    tmp_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    path = os.path.join(tmp_dir, "bad-toplevel.qbext")
    entries, manifest = base()
    entries["questions/part-0001.ndjson"] = b'{"questionId":"q1"}\n'
    manifest = refresh_hashes(entries, manifest)
    write_package(entries, manifest, path)
    codes = [e["code"] for e in validate_package(path).errors]
    if "ZIP_ENTRY_UNEXPECTED" in codes:
        passed += 1
    else:
        failed += 1
        print(f"FAIL  顶层目录越界: 实际 {codes}")

    # ZIP 层：没有 manifest 的包必须整包拒绝（而不是当成空扩展）
    path = os.path.join(tmp_dir, "no-manifest.qbext")
    import zipfile

    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("relations/edges.ndjson", stream(GOOD_RELATION))
    codes = [e["code"] for e in validate_package(path).errors]
    if "MANIFEST_MISSING" in codes:
        passed += 1
    else:
        failed += 1
        print(f"FAIL  缺清单: 实际 {codes}")

    # 正向：base() 本身必须通过
    entries, manifest = base()
    report = validate_entries(entries, refresh_hashes(entries, manifest))
    if report.ok:
        passed += 1
    else:
        failed += 1
        print(f"FAIL  基准包应当通过: {report.errors}")

    print(f"{passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(run())
