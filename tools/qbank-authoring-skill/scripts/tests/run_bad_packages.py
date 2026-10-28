#!/usr/bin/env python3
"""坏包回归测试：每一个构造出来的坏包都必须 fail-closed。

运行：python tools/qbank/tests/run_bad_packages.py
输出：每条用例的 PASS/FAIL，最后打印汇总；有任何一条没被拒绝即以非零退出。

这些用例是规范 §10/§11 的可执行版本 —— 文档写了什么，这里就断言什么。
"""

from __future__ import annotations

import io
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
sys.path.insert(0, TOOL)

from ktqbf.package import sha256_bytes  # noqa: E402
from ktqbf.provenance import content_digest  # noqa: E402
from ktqbf.validator import validate_package  # noqa: E402

SPEC = os.path.join(TOOL, "spec")
TMP = os.path.join(HERE, "_tmp")

BANK = "550e8400-e29b-41d4-a716-446655440000"


# ---------------------------------------------------------------- 基础构造

def base_files():
    q = (
        json.dumps(
            {
                "questionId": "q001",
                "type": "single_choice",
                "optionIds": ["o1", "o2"],
                "correctOptionIds": ["o2"],
                "grading": {"strategy": "exact-set"},
            },
            ensure_ascii=False,
        )
        + "\n"
    )
    loc = (
        json.dumps(
            {
                "questionId": "q001",
                "locale": "zh-Hans",
                "promptMarkdown": "1+1=?",
                "options": [
                    {"optionId": "o1", "contentMarkdown": "1"},
                    {"optionId": "o2", "contentMarkdown": "2"},
                ],
            },
            ensure_ascii=False,
        )
        + "\n"
    )
    return {
        "questions/part-0001.ndjson": q.encode(),
        "locales/zh-Hans/questions.ndjson": loc.encode(),
    }


def base_manifest(files: dict[str, bytes], **over):
    """构造清单。默认把 files 与 contentDigest 都按真实规则算好 ——
    用例要的是"只坏在一处"，清单本身不该顺带出错。

    传 `_no_digest=True` 得到一个没有 contentDigest 的老式包。
    """
    no_digest = over.pop("_no_digest", False)
    m = {
        "format": "knowledge-trainer-question-bank",
        "schemaVersion": "1.0.0",
        "bankId": BANK,
        "contentRevision": 1,
        "title": "测试题库",
        "sourceLocale": "zh-Hans",
        "supportedLocales": ["zh-Hans"],
        "author": {"displayName": "tester", "contacts": []},
        "files": [{"path": p, "sha256": sha256_bytes(b)} for p, b in sorted(files.items())],
    }
    m.update(over)
    if not no_digest:
        m["contentDigest"] = content_digest(m, files)
    return m


def write_zip(path: str, entries: dict[str, bytes], manifest: dict | None, *, raw_names: bool = False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        if manifest is not None:
            zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False).encode())
        for name, data in entries.items():
            zf.writestr(name, data)


def build_ok(path: str, entries: dict[str, bytes] | None = None, manifest: dict | None = None):
    entries = base_files() if entries is None else entries
    manifest = base_manifest(entries) if manifest is None else manifest
    write_zip(path, entries, manifest)
    return path


# ---------------------------------------------------------------- 用例

CASES: list[tuple[str, str, object]] = []


def case(name: str, expect: str):
    def deco(fn):
        CASES.append((name, expect, fn))
        return fn

    return deco


@case("不是 ZIP（只改了扩展名）", "ZIP_NOT_ZIP")
def _(path):
    with open(path, "wb") as fh:
        fh.write(b'{"questions":[]}')
    return path


@case("缺少 manifest.json", "MANIFEST_MISSING")
def _(path):
    e = base_files()
    write_zip(path, e, None)
    return path


@case("manifest 不是合法 JSON", "MANIFEST_INVALID")
def _(path):
    e = base_files()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", b"{not json")
        for p, d in e.items():
            zf.writestr(p, d)
    return path


@case("manifest 缺少必填字段 bankId", "MANIFEST_INVALID")
def _(path):
    e = base_files()
    m = base_manifest(e)
    del m["bankId"]
    return build_ok(path, e, m)


@case("bankId 不是 UUID v4", "MANIFEST_INVALID")
def _(path):
    e = base_files()
    return build_ok(path, e, base_manifest(e, bankId="not-a-uuid"))


@case("schemaVersion 主版本不支持（2.0.0）", "SCHEMA_VERSION_UNSUPPORTED")
def _(path):
    e = base_files()
    return build_ok(path, e, base_manifest(e, schemaVersion="2.0.0"))


@case("哈希不符（改内容不改 manifest）", "HASH_MISMATCH")
def _(path):
    e = base_files()
    m = base_manifest(e)
    e["questions/part-0001.ndjson"] = e["questions/part-0001.ndjson"] + b" "
    return build_ok(path, e, m)


@case("ZIP 里有清单外的文件（未引用媒体）", "FILE_NOT_LISTED")
def _(path):
    e = base_files()
    m = base_manifest(e)
    e["media/img/x.png"] = b"\x89PNG\r\n\x1a\nfake"
    return build_ok(path, e, m)


@case("清单声明的文件不存在", "FILE_MISSING")
def _(path):
    e = base_files()
    m = base_manifest(e)
    m["files"].append({"path": "locales/en/questions.ndjson", "sha256": "0" * 64})
    return build_ok(path, e, m)


@case("questionId 重复", "QID_DUPLICATE")
def _(path):
    e = base_files()
    line = e["questions/part-0001.ndjson"]
    e["questions/part-0001.ndjson"] = line + line
    return build_ok(path, e)


@case("正确答案不在 optionIds 中", "OPTION_SUBSET")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] = json.dumps(
        {"questionId": "q001", "type": "single_choice", "optionIds": ["o1", "o2"], "correctOptionIds": ["o9"]}
    ).encode() + b"\n"
    return build_ok(path, e)


@case("单选题有 2 个正确答案", "ANSWER_COUNT")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] = json.dumps(
        {"questionId": "q001", "type": "single_choice", "optionIds": ["o1", "o2"], "correctOptionIds": ["o1", "o2"]}
    ).encode() + b"\n"
    return build_ok(path, e)


@case("多选题只有 1 个正确答案", "ANSWER_COUNT")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] = json.dumps(
        {"questionId": "q001", "type": "multiple_choice", "optionIds": ["o1", "o2"], "correctOptionIds": ["o1"]}
    ).encode() + b"\n"
    return build_ok(path, e)


@case("判断题有 3 个选项", "OPTION_COUNT")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] = json.dumps(
        {"questionId": "q001", "type": "true_false", "optionIds": ["o1", "o2", "o3"], "correctOptionIds": ["o1"]}
    ).encode() + b"\n"
    l = json.loads(e["locales/zh-Hans/questions.ndjson"])
    l["options"].append({"optionId": "o3", "contentMarkdown": "x"})
    e["locales/zh-Hans/questions.ndjson"] = (json.dumps(l, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("文案层选项与结构层不一致", "OPTION_MISMATCH")
def _(path):
    e = base_files()
    l = json.loads(e["locales/zh-Hans/questions.ndjson"])
    l["options"] = [{"optionId": "o1", "contentMarkdown": "1"}]
    e["locales/zh-Hans/questions.ndjson"] = (json.dumps(l, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("简答题缺 answerText", "ANSWER_MISSING")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] = json.dumps(
        {"questionId": "q001", "type": "short_answer", "grading": {"strategy": "strict-text"}}
    ).encode() + b"\n"
    e["locales/zh-Hans/questions.ndjson"] = (
        json.dumps({"questionId": "q001", "locale": "zh-Hans", "promptMarkdown": "什么是实践？"}, ensure_ascii=False)
        + "\n"
    ).encode()
    return build_ok(path, e)


@case("题型与判题策略不匹配", "GRADING_MISMATCH")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] = json.dumps(
        {
            "questionId": "q001",
            "type": "single_choice",
            "optionIds": ["o1", "o2"],
            "correctOptionIds": ["o1"],
            "grading": {"strategy": "strict-text"},
        }
    ).encode() + b"\n"
    return build_ok(path, e)


@case("manifest 声明的 locale 缺文件", "LOCALE_MISSING")
def _(path):
    e = base_files()
    return build_ok(path, e, base_manifest(e, supportedLocales=["zh-Hans", "en"]))


@case("sourceLocale 未覆盖全部题目", "LOCALE_INCOMPLETE")
def _(path):
    e = base_files()
    extra = json.dumps(
        {"questionId": "q002", "type": "short_answer", "grading": {"strategy": "strict-text"}}
    ).encode() + b"\n"
    e["questions/part-0001.ndjson"] += extra
    return build_ok(path, e)


@case("文案层出现结构层不存在的题", "LOCALE_UNKNOWN_QID")
def _(path):
    e = base_files()
    extra = json.dumps(
        {"questionId": "q999", "locale": "zh-Hans", "promptMarkdown": "x"}, ensure_ascii=False
    ).encode() + b"\n"
    e["locales/zh-Hans/questions.ndjson"] += extra
    return build_ok(path, e)


@case("行内 locale 与目录不一致", "LOCALE_MISMATCH")
def _(path):
    e = base_files()
    obj = json.loads(e["locales/zh-Hans/questions.ndjson"])
    obj["locale"] = "en"
    e["locales/zh-Hans/questions.ndjson"] = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("NDJSON 某行不是合法 JSON", "NDJSON_INVALID")
def _(path):
    e = base_files()
    e["questions/part-0001.ndjson"] += b"{broken\n"
    m = base_manifest(e)
    return build_ok(path, e, m)


@case("内容里含 <script>", "CONTENT_FORBIDDEN")
def _(path):
    e = base_files()
    obj = json.loads(e["locales/zh-Hans/questions.ndjson"])
    obj["promptMarkdown"] = "正常题干<script>alert(1)</script>"
    e["locales/zh-Hans/questions.ndjson"] = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("内容里含外链图片", "CONTENT_FORBIDDEN")
def _(path):
    e = base_files()
    obj = json.loads(e["locales/zh-Hans/questions.ndjson"])
    obj["promptMarkdown"] = "看图 ![](https://evil.example/x.png)"
    e["locales/zh-Hans/questions.ndjson"] = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("媒体哈希不符", "MEDIA_HASH_MISMATCH")
def _(path):
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
    e = base_files()
    e["media/img/a.png"] = png
    e["media/index.ndjson"] = (
        json.dumps(
            {
                "assetId": "a",
                "path": "media/img/a.png",
                "mimeType": "image/png",
                "bytes": len(png),
                "sha256": "0" * 64,
            }
        )
        + "\n"
    ).encode()
    return build_ok(path, e)


@case("媒体类型不符（声明 png 实为 jpeg）", "MEDIA_TYPE_MISMATCH")
def _(path):
    jpg = b"\xff\xd8\xff" + b"\x00" * 32
    e = base_files()
    e["media/img/a.png"] = jpg
    e["media/index.ndjson"] = (
        json.dumps(
            {
                "assetId": "a",
                "path": "media/img/a.png",
                "mimeType": "image/png",
                "bytes": len(jpg),
                "sha256": sha256_bytes(jpg),
            }
        )
        + "\n"
    ).encode()
    return build_ok(path, e)


@case("媒体文件未被索引引用", "MEDIA_UNREFERENCED")
def _(path):
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 8
    e = base_files()
    e["media/img/orphan.png"] = png
    e["media/index.ndjson"] = b""
    return build_ok(path, e)


@case("引用了不存在的 assetId", "MEDIA_UNKNOWN_ASSET")
def _(path):
    e = base_files()
    obj = json.loads(e["locales/zh-Hans/questions.ndjson"])
    obj["assets"] = ["nope"]
    e["locales/zh-Hans/questions.ndjson"] = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("增强资料绑定了不存在的题", "ENHANCEMENT_UNKNOWN_QID")
def _(path):
    e = base_files()
    e["enhancements/index.ndjson"] = (
        json.dumps(
            {
                "questionId": "q404",
                "locale": "zh-Hans",
                "kind": "reconstruction",
                "path": "enhancements/r.ndjson",
                "reviewStatus": "unreviewed",
            }
        )
        + "\n"
    ).encode()
    e["enhancements/r.ndjson"] = b"{}\n"
    return build_ok(path, e)


# ---- 需要手工写 ZIP 的安全类用例（不走 build_ok）----

@case("ZIP 路径穿越 ../evil", "ZIP_PATH_TRAVERSAL")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        zf.writestr("../../evil.txt", b"pwned")
    return path


@case("ZIP 绝对路径 /etc/passwd", "ZIP_PATH_TRAVERSAL")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        zf.writestr("/etc/passwd", b"root")
    return path


@case("ZIP 条目带盘符 C:/x", "ZIP_PATH_TRAVERSAL")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        zf.writestr("C:/x.txt", b"x")
    return path


@case("ZIP 条目在允许的顶层目录之外", "ZIP_ENTRY_UNEXPECTED")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        zf.writestr("run.bat", b"del /f /q c:\\*")
    return path


@case("ZIP 炸弹（高压缩比）", "ZIP_BOMB")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        zf.writestr("media/img/bomb.bin", b"\x00" * (5 * 1024 * 1024))
    return path


@case("ZIP 内重复条目", "ZIP_DUPLICATE_ENTRY")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # zipfile 的 writestr 会去重同名，这里手工拼两份中央目录
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
    raw = buf.getvalue()
    # 直接追加一个重复的本地条目 + 中央目录项过于复杂；改用 allowed 的另一个名字冒充：
    # 这里用"questions/part-0001.ndjson" 的等价体（大小写不同不算重复），
    # 因此改用真正的重复测试：复制整个 zip 的条目名两次（zipfile 允许追加同名）
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        # ZipFile.writestr 在同一实例内同名会被追加为第二个同名条目（ZIP 规范允许）
        zf.writestr("questions/part-0001.ndjson", e["questions/part-0001.ndjson"])
    del raw
    return path


@case("符号链接条目", "ZIP_ENTRY_LINK")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
        info = zipfile.ZipInfo("media/link")
        info.external_attr = (0o120777 << 16)  # S_IFLNK
        zf.writestr(info, "/etc/passwd")
    return path


@case("未加密条目被标记为加密", "ZIP_ENCRYPTED")
def _(path):
    e = base_files()
    m = base_manifest(e)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("manifest.json", json.dumps(m, ensure_ascii=False).encode())
        for p, d in e.items():
            zf.writestr(p, d)
    raw = bytearray(buf.getvalue())
    # 翻转本地头与中央目录的加密标志位（general purpose bit 0）
    idx = 0
    while True:
        idx = raw.find(b"PK\x03\x04", idx)
        if idx < 0:
            break
        raw[idx + 6] |= 0x01
        idx += 4
    idx = 0
    while True:
        idx = raw.find(b"PK\x01\x02", idx)
        if idx < 0:
            break
        raw[idx + 8] |= 0x01
        idx += 4
    with open(path, "wb") as fh:
        fh.write(bytes(raw))
    return path


@case("Schema 不允许的额外字段", "SCHEMA_INVALID")
def _(path):
    e = base_files()
    obj = json.loads(e["questions/part-0001.ndjson"])
    obj["difficulty"] = "hard"
    e["questions/part-0001.ndjson"] = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
    return build_ok(path, e)


@case("只改中央目录的解压后大小（本地头与中央目录矛盾）", "ZIP_HEADER_MISMATCH")
def _(path):
    """这是真实绕过路径：zipfile.read 只信中央目录的 file_size，
    改它就能让解出的字节与清单哈希不符却不报错。必须由本地头比对拦住。"""
    build_ok(path)
    raw = bytearray(open(path, "rb").read())
    cur = raw.find(b"PK\x01\x02")
    target = None
    while cur >= 0:
        name_len = int.from_bytes(raw[cur + 28 : cur + 30], "little")
        name = bytes(raw[cur + 46 : cur + 46 + name_len]).decode("utf-8", "replace")
        if name == "questions/part-0001.ndjson":
            target = cur
            break
        cur = raw.find(b"PK\x01\x02", cur + 4)
    if target is None:
        raise RuntimeError("找不到 questions/part-0001.ndjson 的中央目录项")
    old = int.from_bytes(raw[target + 24 : target + 28], "little")
    raw[target + 24 : target + 28] = (old + 7).to_bytes(4, "little")
    with open(path, "wb") as fh:
        fh.write(bytes(raw))
    return path


@case("增强资料的 answerHash 与当前答案不符（改了题没更新增强）", "ENHANCEMENT_STALE")
def _(path):
    e = base_files()
    e["enhancements/index.ndjson"] = (
        json.dumps(
            {
                "questionId": "q001",
                "locale": "zh-Hans",
                "kind": "distractor",
                "path": "enhancements/distractors.ndjson",
                "reviewStatus": "unreviewed",
                "answerHash": "0" * 16,  # 格式合法但与当前题干/答案不符
            },
            ensure_ascii=False,
        )
        + "\n"
    ).encode()
    e["enhancements/distractors.ndjson"] = b'{"segments":[]}\n'
    return build_ok(path, e)


# ---------------------------------------------------------------- 出处与摘要

def repack(path: str, mutate_q=None, **manifest_over):
    """在最小包基础上只改一处，files 与 contentDigest 都重算 —— 保证"只坏在你想测的那一点"。"""
    e = base_files()
    if mutate_q is not None:
        e["questions/part-0001.ndjson"] = mutate_q(e["questions/part-0001.ndjson"])
    write_zip(path, e, base_manifest(e, **manifest_over))
    return path


SOURCE = [{"sourceId": "s1", "title": "甲教材", "kind": "book"}]


@case("sources 里 sourceId 重复", "SOURCE_DUPLICATE")
def _(path):
    return repack(path, sources=[{"sourceId": "s1", "title": "甲"}, {"sourceId": "s1", "title": "乙"}])


@case("题目引用了不存在的 sourceId", "SOURCE_UNKNOWN")
def _(path):
    # 有 sources，但题里引的 s9 不在其中 —— 拼错 id 是最常见的作者失误
    return repack(
        path,
        mutate_q=lambda raw: raw.replace(b'"grading"', b'"sourceId":"s9","grading"'),
        sources=SOURCE,
    )


@case("题目引了来源但整份 manifest 没有 sources", "SOURCE_UNKNOWN")
def _(path):
    return repack(path, mutate_q=lambda raw: raw.replace(b'"grading"', b'"sourceId":"s1","grading"'))


@case("contentDigest 与实际内容不符（改了题没重新打包）", "CONTENT_DIGEST_MISMATCH")
def _(path):
    e = base_files()
    m = base_manifest(e)
    e["questions/part-0001.ndjson"] = e["questions/part-0001.ndjson"].replace(b'"o2"]', b'"o1"]')
    m["files"] = [{"path": p, "sha256": sha256_bytes(b)} for p, b in sorted(e.items())]
    # files 跟着改了，digest 没改 —— 正是"手工编辑后忘了重新打包"的样子
    write_zip(path, e, m)
    return path


@case("origin 用了约定外的值", "SCHEMA_INVALID")
def _(path):
    return repack(path, mutate_q=lambda raw: raw.replace(b'"grading"', b'"origin":"copied","grading"'))


@case("sources 条目的 sourceId 含大写", "MANIFEST_INVALID")
def _(path):
    return repack(path, sources=[{"sourceId": "Marx-1", "title": "甲"}])


# 正面用例：这些包**必须**被接受，只是带警告。前缀 `warn:` 表示"应通过且含此警告"。


@case("标了来源却没写 sourceLocator", "warn:SOURCE_LOCATOR_MISSING")
def _(path):
    return repack(path, mutate_q=lambda raw: raw.replace(b'"grading"', b'"sourceId":"s1","grading"'), sources=SOURCE)


@case("声称 imported 却没写 sourceId", "warn:ORIGIN_WITHOUT_SOURCE")
def _(path):
    return repack(path, mutate_q=lambda raw: raw.replace(b'"grading"', b'"origin":"imported","grading"'))


@case("老包没有 contentDigest", "warn:CONTENT_DIGEST_MISSING")
def _(path):
    e = base_files()
    write_zip(path, e, base_manifest(e, _no_digest=True))
    return path


# ---------------------------------------------------------------- 运行

def main() -> int:
    os.makedirs(TMP, exist_ok=True)
    passed = failed = 0
    rows = []
    for i, (name, expect, fn) in enumerate(CASES, start=1):
        path = os.path.join(TMP, f"bad-{i:02d}.qbank")
        if os.path.exists(path):
            os.remove(path)
        try:
            fn(path)
            report = validate_package(path, SPEC)
            codes = {e.code for e in report.errors}
            if expect.startswith("warn:"):
                want = expect[len("warn:"):]
                warned = {w.code for w in report.warnings}
                ok = report.ok and want in warned
                detail = "" if ok else f"期望通过且带警告 {want}，实际 ok={report.ok} codes={sorted(codes)} warns={sorted(warned)}"
            else:
                ok = (not report.ok) and expect in codes
                detail = "" if ok else f"期望 {expect}，实际 codes={sorted(codes) or '无（被判为合法！）'}"
        except Exception as exc:  # noqa: BLE001
            ok = False
            detail = f"用例本身抛异常：{type(exc).__name__}: {exc}"
        if ok:
            passed += 1
        else:
            failed += 1
        rows.append((ok, name, expect, detail))

    width = max(len(n) for _, n, _, _ in rows)
    for ok, name, expect, detail in rows:
        mark = "PASS" if ok else "FAIL"
        print(f"{mark}  {name.ljust(width)}  [{expect}]  {detail}")
    print(f"\n{passed} passed, {failed} failed, {len(rows)} total")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
