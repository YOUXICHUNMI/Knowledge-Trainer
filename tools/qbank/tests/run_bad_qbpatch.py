#!/usr/bin/env python3
"""`.qbpatch` 坏包回归：每条用例只破坏一个规则，断言稳定错误码。

跑法：python tools/qbank/tests/run_bad_qbpatch.py

与 `run_bad_qbext.py` 同一套约定：用例返回 (entries, manifest, auto_hash)。
`auto_hash=True` 时由 runner 按实际字节重算 files 清单 —— 否则"改了内容"会先撞上
HASH_MISMATCH，测不到后面的语义规则；只有专门测清单的用例才置 False。
"""

from __future__ import annotations

import copy
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ktqbf.package import read_package, sha256_bytes, write_package
from ktqbf.qbpatch import FORMAT_ID, validate_entries, validate_package

BANK = "550e8400-e29b-41d4-a716-446655440000"
FP = "0123456789abcdef"

GOOD_QUESTION = {
    "questionId": "q9",
    "type": "single_choice",
    "promptMarkdown": "新增题目的题干",
    "options": [
        {"optionId": "o1", "contentMarkdown": "干扰项"},
        {"optionId": "o2", "contentMarkdown": "正确答案"},
    ],
    "correctOptionIds": ["o2"],
}


def base():
    manifest = {
        "schemaVersion": "1.0.0",
        "format": FORMAT_ID,
        "patchId": "demo-v2",
        "version": "2.0.0",
        "name": "示例补丁",
        "bankId": BANK,
        "baseRevision": 1,
        "targetRevision": 2,
        "baseFingerprint": FP,
        "notes": "",
        "files": [],
        "operations": [
            {"op": "update", "questionId": "q1", "expect": FP, "set": {"promptMarkdown": "新题干"}},
            {"op": "add", "questionId": "q9", "question": copy.deepcopy(GOOD_QUESTION)},
        ],
    }
    return {}, manifest


def refresh_hashes(entries, manifest):
    manifest = copy.deepcopy(manifest)
    manifest["files"] = [{"path": p, "sha256": sha256_bytes(entries[p])} for p in sorted(entries)]
    return manifest


CASES = []


def case(name: str, code: str):
    def wrap(fn):
        CASES.append((name, code, fn))
        return fn

    return wrap


# ---------------------------------------------------------------- 清单层


@case("format 写错（qbank 改名）", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["format"] = "knowledge-trainer-question-bank"
    return entries, manifest, True


@case("schemaVersion 不支持", "SCHEMA_VERSION_UNSUPPORTED")
def _(entries, manifest):
    manifest["schemaVersion"] = "2.0.0"
    return entries, manifest, True


@case("bankId 不是 UUID", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["bankId"] = "starter-bank"
    return entries, manifest, True


@case("version 不是 主.次.修", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["version"] = "2.0"
    return entries, manifest, True


@case("patchId 含大写", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["patchId"] = "Demo-V2"
    return entries, manifest, True


@case("缺少 name", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest.pop("name")
    return entries, manifest, True


@case("baseRevision 为 0", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["baseRevision"] = 0
    return entries, manifest, True


@case("targetRevision 不大于 baseRevision", "MANIFEST_INVALID")
def _(entries, manifest):
    manifest["targetRevision"] = 1
    return entries, manifest, True


@case("缺少 baseFingerprint", "BASE_FINGERPRINT_MISSING")
def _(entries, manifest):
    manifest.pop("baseFingerprint")
    return entries, manifest, True


@case("baseFingerprint 不是 16 位", "BASE_FINGERPRINT_MISSING")
def _(entries, manifest):
    manifest["baseFingerprint"] = "sha256-looking-but-wrong"
    return entries, manifest, True


@case("operations 为空", "PATCH_EMPTY")
def _(entries, manifest):
    manifest["operations"] = []
    return entries, manifest, True


@case("update 的 set 是空对象", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"] = [{"op": "update", "questionId": "q1", "set": {}}]
    return entries, manifest, True


@case("包内有未列入清单的文件", "FILE_NOT_LISTED")
def _(entries, manifest):
    entries = dict(entries)
    entries["media/extra.png"] = b"\x89PNG\r\n\x1a\n"
    return entries, manifest, False


@case("清单哈希不符", "HASH_MISMATCH")
def _(entries, manifest):
    entries = dict(entries)
    entries["media/a.png"] = b"\x89PNG\r\n\x1a\n"
    manifest = refresh_hashes(entries, manifest)
    entries["media/a.png"] = b"\x89PNG\r\n\x1a\nTAMPERED"
    return entries, manifest, False


@case("清单声明了不存在的文件", "FILE_MISSING")
def _(entries, manifest):
    manifest = copy.deepcopy(manifest)
    manifest["files"].append({"path": "media/ghost.png", "sha256": "1" * 64})
    return entries, manifest, False


# ---------------------------------------------------------------- 操作层


@case("op 不认识", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"].append({"op": "retitle", "questionId": "q3", "title": "新标题"})
    return entries, manifest, True


@case("同一道题出现两条操作", "OPERATION_CONFLICT")
def _(entries, manifest):
    manifest["operations"].append({"op": "remove", "questionId": "q1"})
    return entries, manifest, True


@case("questionId 非法", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"].append({"op": "remove", "questionId": "Q-1"})
    return entries, manifest, True


@case("update 缺 set", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0].pop("set")
    return entries, manifest, True


@case("update 试图改题型", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0]["set"]["type"] = "multiple_choice"
    return entries, manifest, True


@case("update 试图改判题策略", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0]["set"]["grading"] = {"strategy": "keywords"}
    return entries, manifest, True


@case("update 试图改 questionId", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0]["set"]["questionId"] = "q2"
    return entries, manifest, True


@case("update 的 correctOptionIds 是空数组", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0]["set"]["correctOptionIds"] = []
    return entries, manifest, True


@case("update 的 options 只有一项", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0]["set"]["options"] = [{"optionId": "o1", "contentMarkdown": "唯一"}]
    return entries, manifest, True


@case("update 的 expect 不是 16 位", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][0]["expect"] = "abc"
    return entries, manifest, True


@case("add 缺 question", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][1].pop("question")
    return entries, manifest, True


@case("add 的 questionId 与 question 内不一致", "OPERATION_INVALID")
def _(entries, manifest):
    manifest["operations"][1]["question"]["questionId"] = "q10"
    return entries, manifest, True


@case("add 的多选题只有一个正确答案", "QUESTION_INVALID")
def _(entries, manifest):
    q = copy.deepcopy(GOOD_QUESTION)
    q["type"] = "multiple_choice"
    q["correctOptionIds"] = ["o2"]
    manifest["operations"][1]["question"] = q
    return entries, manifest, True


@case("add 的判断题有三个选项", "QUESTION_INVALID")
def _(entries, manifest):
    q = copy.deepcopy(GOOD_QUESTION)
    q["type"] = "true_false"
    q["options"].append({"optionId": "o3", "contentMarkdown": "第三个"})
    manifest["operations"][1]["question"] = q
    return entries, manifest, True


@case("add 的正确答案不在选项里", "QUESTION_INVALID")
def _(entries, manifest):
    q = copy.deepcopy(GOOD_QUESTION)
    q["correctOptionIds"] = ["o9"]
    manifest["operations"][1]["question"] = q
    return entries, manifest, True


@case("add 的简答题没有答案", "QUESTION_INVALID")
def _(entries, manifest):
    q = copy.deepcopy(GOOD_QUESTION)
    q["type"] = "short_answer"
    q.pop("options")
    q.pop("correctOptionIds")
    manifest["operations"][1]["question"] = q
    return entries, manifest, True


@case("add 的选项 id 重复", "QUESTION_INVALID")
def _(entries, manifest):
    q = copy.deepcopy(GOOD_QUESTION)
    q["options"][1]["optionId"] = "o1"
    manifest["operations"][1]["question"] = q
    return entries, manifest, True


@case("add 的题干为空", "QUESTION_INVALID")
def _(entries, manifest):
    q = copy.deepcopy(GOOD_QUESTION)
    q["promptMarkdown"] = "   "
    manifest["operations"][1]["question"] = q
    return entries, manifest, True


@case("media 超大", "MEDIA_TOO_LARGE")
def _(entries, manifest):
    entries = dict(entries)
    entries["media/huge.bin"] = b"\x00" * (20 * 1024 * 1024 + 1)
    return entries, manifest, False


def run() -> int:
    passed = 0
    failed = 0

    # 正向：base() 本身必须通过
    entries, manifest = base()
    report = validate_entries(entries, refresh_hashes(entries, manifest))
    if report.ok:
        passed += 1
    else:
        failed += 1
        print(f"FAIL  基准包应当通过: {report.errors}")

    # 正向：示例补丁文件必须通过，且指纹与旧包一致
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sample = os.path.join(here, "examples", "starter-v2.qbpatch")
    if os.path.isfile(sample):
        report = validate_package(sample)
        if report.ok and report.base_revision == 1 and report.target_revision == 2:
            passed += 1
        else:
            failed += 1
            print(f"FAIL  示例补丁校验失败: {report.errors}")
    else:
        print(f"skip  示例补丁不存在: {sample}")

    for name, expected, fn in CASES:
        entries, manifest = base()
        entries, manifest, auto_hash = fn(entries, manifest)
        if auto_hash:
            manifest = refresh_hashes(entries, manifest)
        report = validate_entries(entries, manifest)
        codes = [e["code"] for e in report.errors]
        if expected in codes:
            passed += 1
        else:
            failed += 1
            print(f"FAIL  {name}: 期望 {expected}，实际 {codes}")

    # ZIP 层：顶层目录越界必须拒绝
    import tempfile

    tmp_dir = tempfile.mkdtemp(prefix="kt-qbpatch-")
    entries, manifest = base()
    entries["questions/part-0001.ndjson"] = b'{"questionId":"q1"}\n'
    manifest = refresh_hashes(entries, manifest)
    path = os.path.join(tmp_dir, "bad-toplevel.qbpatch")
    write_package(entries, manifest, path)
    try:
        read_package(path, ("manifest.json", "media/"))
        failed += 1
        print("FAIL  顶层目录越界: 没有被拒绝")
    except Exception:
        passed += 1

    # ZIP 层：没有 manifest 必须整包拒绝（而不是当成空补丁）
    import zipfile

    path = os.path.join(tmp_dir, "no-manifest.qbpatch")
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("media/a.png", b"\x89PNG")
    codes = [e["code"] for e in validate_package(path).errors]
    if "MANIFEST_MISSING" in codes:
        passed += 1
    else:
        failed += 1
        print(f"FAIL  缺清单: 实际 {codes}")

    # 不是 ZIP 的文件
    path = os.path.join(tmp_dir, "not-zip.qbpatch")
    with open(path, "wb") as fh:
        fh.write(b'{"format":"knowledge-trainer-bank-patch"}')
    codes = [e["code"] for e in validate_package(path).errors]
    if "ZIP_NOT_ZIP" in codes:
        passed += 1
    else:
        failed += 1
        print(f"FAIL  非 ZIP: 实际 {codes}")

    print(f"{passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(run())
