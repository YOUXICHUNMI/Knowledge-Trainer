"""KT-QBF 1.0 语义校验器。

分层：结构安全（package.read_package）→ Schema（schema_lite）→ 语义不变量（本文件）。
每一层失败都不放行；错误带稳定 code，便于坏包测试逐条断言。
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from typing import Any

from .answer_hash import compute_answer_hash
from . import provenance
from .package import (
    MAX_MEDIA_BYTES,
    MAX_TOTAL_MEDIA_BYTES,
    FORMAT_ID,
    SUPPORTED_MAJOR,
    PackageError,
    ZipContents,
    normalize_package_digest,
    read_package,
    safe_relpath,
    sha256_bytes,
)
from .schema_lite import load_schema, validate as schema_validate

CHOICE_TYPES = {"single_choice", "multiple_choice", "true_false"}
FRAGMENT_TYPES = CHOICE_TYPES | {"ordering"}
TYPE_LABEL = {
    "single_choice": "singleChoice",
    "multiple_choice": "multipleChoice",
    "true_false": "trueFalse",
    "short_answer": "shortAnswer",
    "ordering": "ordering",
}

# 内容体检：明确拒绝可执行/外链内容，保证离线可渲染、无注入面
_FORBIDDEN_PATTERNS = [
    (re.compile(r"<\s*script", re.I), "<script> 标签"),
    (re.compile(r"<\s*iframe", re.I), "<iframe> 标签"),
    (re.compile(r"javascript\s*:", re.I), "javascript: 链接"),
    (re.compile(r"<img[^>]+src\s*=\s*[\"']?\s*(?:https?:)?//", re.I), "外链图片"),
    (re.compile(r"!\[[^\]]*\]\(\s*(?:https?:)?//", re.I), "外链图片"),
    (re.compile(r"[A-Za-z]:\\\\|[A-Za-z]:\\"), "本地绝对路径"),
    (re.compile(r"\bdata:text/html", re.I), "data:text/html 内联页面"),
]


@dataclass
class Issue:
    code: str
    message: str
    path: str = ""
    line: int | None = None

    def as_dict(self) -> dict:
        out: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.path:
            out["path"] = self.path
        if self.line is not None:
            out["line"] = self.line
        return out


@dataclass
class Report:
    ok: bool = True
    package: str = ""
    schema_version: str = ""
    bank_id: str = ""
    title: str = ""
    digest: str = ""
    content_digest: str = ""
    counts: dict[str, int] = field(default_factory=dict)
    locales: dict[str, dict[str, int]] = field(default_factory=dict)
    provenance: dict[str, Any] = field(default_factory=dict)
    errors: list[Issue] = field(default_factory=list)
    warnings: list[Issue] = field(default_factory=list)
    enhancements: dict[str, int] = field(default_factory=dict)

    def error(self, code: str, message: str, path: str = "", line: int | None = None) -> None:
        self.errors.append(Issue(code, message, path, line))
        self.ok = False

    def warn(self, code: str, message: str, path: str = "", line: int | None = None) -> None:
        self.warnings.append(Issue(code, message, path, line))

    def as_dict(self) -> dict:
        out: dict[str, Any] = {
            "ok": self.ok,
            "package": self.package,
            "schemaVersion": self.schema_version,
            "bankId": self.bank_id,
            "title": self.title,
            "digest": self.digest,
            "contentDigest": self.content_digest,
            "counts": self.counts,
            "locales": self.locales,
            "enhancements": self.enhancements,
            "errors": [e.as_dict() for e in self.errors],
            "warnings": [w.as_dict() for w in self.warnings],
        }
        # 老包没有出处声明时整段不出现 —— 输出里多一个全零的 provenance 块，
        # 容易被读成"作者标了 0 处出处"，而事实是"作者没写这个字段"。
        if self.provenance:
            out["provenance"] = self.provenance
        return out


def _read_ndjson(text: str, path: str, report: Report) -> list[tuple[int, Any]]:
    """逐行解析 NDJSON。空行与纯注释行（# 开头）跳过。"""
    out: list[tuple[int, Any]] = []
    for lineno, raw in enumerate(text.splitlines(), start=1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        try:
            obj = json.loads(stripped)
        except json.JSONDecodeError as exc:
            report.error("NDJSON_INVALID", f"第 {lineno} 行不是合法 JSON: {exc.msg}", path, lineno)
            continue
        out.append((lineno, obj))
    return out


def _check_content(value: str, path: str, lineno: int | None, report: Report, where: str) -> None:
    for pattern, label in _FORBIDDEN_PATTERNS:
        if pattern.search(value):
            report.error("CONTENT_FORBIDDEN", f"{where} 含不允许的内容（{label}）", path, lineno)
            return


def _magic_matches(data: bytes, mime: str) -> bool:
    if mime == "image/png":
        return data.startswith(b"\x89PNG\r\n\x1a\n")
    if mime == "image/jpeg":
        return data.startswith(b"\xff\xd8\xff")
    if mime == "image/gif":
        return data.startswith(b"GIF87a") or data.startswith(b"GIF89a")
    if mime == "image/webp":
        return len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP"
    if mime == "image/svg+xml":
        head = data[:512].lstrip().lower()
        if not (head.startswith(b"<?xml") or head.startswith(b"<svg")):
            return False
        # SVG 能内嵌脚本，必须显式拒绝
        lowered = data.lower()
        return b"<script" not in lowered and b"javascript:" not in lowered
    return False


SCHEMA_NAMES = ("manifest", "question", "localization", "media", "enhancement")

# 打包器所在包目录（.../ktqbf），以及它的上级（.../qbank 或 .../scripts）
_PKG_DIR = os.path.dirname(os.path.abspath(__file__))
_PARENT_DIR = os.path.dirname(_PKG_DIR)
_SKILL_ROOT = os.path.dirname(_PARENT_DIR)


def resolve_spec_dir(spec_dir: str | None = None) -> str:
    """找出 schema-*.json 所在目录。

    同一份校验代码会被放在两种布局里：
      tools/qbank/spec/                        ← 工程内规范
      tools/qbank-authoring-skill/references/  ← 可发布的 Skill 副本
    因此先认显式传入，再按"包目录的上级"和"Skill 根目录"依次探测，
    全部落空时报错，而不是静默认为无 Schema（那样等于关掉校验）。
    """
    candidates: list[str] = []
    if spec_dir:
        candidates.append(spec_dir)
    for base in (_PARENT_DIR, _PKG_DIR):
        candidates.append(os.path.join(base, "spec"))
        candidates.append(os.path.join(base, "references"))
    candidates.append(os.path.join(_SKILL_ROOT, "references"))
    candidates.append(os.path.join(_SKILL_ROOT, "spec"))
    for cand in candidates:
        if all(os.path.isfile(os.path.join(cand, f"schema-{n}.json")) for n in SCHEMA_NAMES):
            return os.path.abspath(cand)
    raise PackageError(
        "SCHEMA_NOT_FOUND",
        "找不到 schema-*.json（尝试过：" + ", ".join(dict.fromkeys(candidates)) + "）；"
        "请把 references/ 或 spec/ 与 ktqbf/ 一起复制，或显式传入 spec_dir",
    )


def _load_schemas(spec_dir: str | None) -> dict:
    resolved = resolve_spec_dir(spec_dir)
    return {name: load_schema(os.path.join(resolved, f"schema-{name}.json")) for name in SCHEMA_NAMES}


def validate_package(path: str, spec_dir: str | None = None) -> Report:
    report = Report(package=path)
    try:
        contents = read_package(path)
    except PackageError as exc:
        report.error(exc.code, exc.message, exc.path, exc.line)
        return report
    except Exception as exc:  # noqa: BLE001
        # 兜底：任何未预期异常都必须变成一条错误报告，而不是 traceback。
        # 校验器的契约是"永远返回报告"；崩栈会让调用方连错误码都拿不到。
        report.error("INTERNAL_ERROR", f"读取包时发生未预期错误：{type(exc).__name__}: {exc}")
        return report

    try:
        schemas = _load_schemas(spec_dir)
    except PackageError as exc:
        report.error(exc.code, exc.message)
        return report
    try:
        _validate_semantics(contents, schemas, report)
        report.digest = normalize_package_digest(contents.entries)
    except Exception as exc:  # noqa: BLE001
        report.error(
            "INTERNAL_ERROR",
            f"校验过程中发生未预期错误（校验器缺陷，请上报）：{type(exc).__name__}: {exc}",
        )
    report.ok = not report.errors
    return report


def validate_entries(
    entries: dict[str, bytes], manifest: dict, spec_dir: str | None = None, package: str = "<memory>"
) -> Report:
    """给打包器用的前置校验：还没写 ZIP 就能发现语义问题。"""
    report = Report(package=package)
    try:
        schemas = _load_schemas(spec_dir)
    except PackageError as exc:
        report.error(exc.code, exc.message)
        return report
    contents = ZipContents(
        manifest=manifest,
        manifest_raw=json.dumps(manifest, ensure_ascii=False).encode("utf-8"),
        entries=dict(entries),
    )
    try:
        _validate_semantics(contents, schemas, report, check_file_list=False)
        report.digest = normalize_package_digest(contents.entries)
    except Exception as exc:  # noqa: BLE001
        report.error(
            "INTERNAL_ERROR",
            f"校验过程中发生未预期错误（校验器缺陷，请上报）：{type(exc).__name__}: {exc}",
        )
    report.ok = not report.errors
    return report


def _validate_semantics(
    contents: ZipContents,
    schemas: dict[str, dict],
    report: Report,
    *,
    check_file_list: bool = True,
) -> None:
    manifest = contents.manifest
    M = "manifest.json"

    # ---------- 1. manifest ----------
    # 先判版本：新主版本的包结构本就可能与 1.0 不同，报"字段不合法"会误导作者。
    raw_version = manifest.get("schemaVersion")
    if isinstance(raw_version, str) and re.match(r"^\d+\.\d+\.\d+$", raw_version):
        if int(raw_version.split(".")[0]) != SUPPORTED_MAJOR:
            report.schema_version = raw_version
            report.bank_id = manifest.get("bankId", "") if isinstance(manifest.get("bankId"), str) else ""
            report.error(
                "SCHEMA_VERSION_UNSUPPORTED",
                f"schemaVersion {raw_version} 不受支持（本工具只支持 1.0.x），请升级工具或换用 1.0 规范的包",
                M,
            )
            return

    for err in schema_validate(manifest, schemas["manifest"]):
        report.error("MANIFEST_INVALID", err, M)
    if not report.ok:
        # manifest 结构不对，后续依赖它的检查没有意义
        return

    version = manifest["schemaVersion"]
    report.schema_version = version
    report.bank_id = manifest.get("bankId", "")
    report.title = manifest.get("title", "")
    if manifest.get("format") != FORMAT_ID:
        report.error("MANIFEST_INVALID", f"format 必须是 {FORMAT_ID}", M)

    locales = manifest["supportedLocales"]
    source_locale = manifest["sourceLocale"]
    if source_locale not in locales:
        report.error("MANIFEST_INVALID", f"sourceLocale {source_locale} 不在 supportedLocales 中", M)

    # ---------- 2. files 清单 ↔ ZIP 条目 ----------
    listed: dict[str, str] = {}
    for i, item in enumerate(manifest["files"]):
        p = item["path"]
        if p in listed:
            report.error("MANIFEST_INVALID", f"files 中重复列出 {p}", M)
        listed[p] = item["sha256"]
    if "manifest.json" in listed:
        report.error("MANIFEST_INVALID", "manifest.json 不得列入 files（自引用）", M)

    actual = contents.entries
    for p, expected in listed.items():
        if p not in actual:
            report.error("FILE_MISSING", f"清单声明了 {p}，但 ZIP 中没有该条目", M)
            continue
        got = sha256_bytes(actual[p])
        if got != expected:
            report.error("HASH_MISMATCH", f"{p} 的 sha256 不符：清单 {expected[:12]}…，实际 {got[:12]}…", p)
    for p in actual:
        if p not in listed:
            report.error("FILE_NOT_LISTED", f"ZIP 中有未列入清单的条目 {p}", p)

    # ---------- 3. 结构层 ----------
    parts = sorted(p for p in actual if re.match(r"^questions/part-[0-9]{4}\.ndjson$", p))
    if not parts:
        report.error("SCHEMA_INVALID", "缺少 questions/part-0001.ndjson", "questions/")
    all_questions = sorted(p for p in actual if p.startswith("questions/"))
    for p in all_questions:
        if p not in parts:
            report.error("ZIP_ENTRY_UNEXPECTED", f"questions/ 下出现未约定的文件名 {p}（应为 part-NNNN.ndjson）", p)

    questions: dict[str, dict] = {}
    q_line: dict[str, tuple[str, int]] = {}
    order: list[str] = []
    for part in parts:
        records = _read_ndjson(contents.text(part), part, report)
        if not records:
            report.error("SCHEMA_INVALID", f"{part} 没有任何题目记录", part)
        for lineno, obj in records:
            if not isinstance(obj, dict):
                report.error("SCHEMA_INVALID", "结构层每行必须是对象", part, lineno)
                continue
            errs = schema_validate(obj, schemas["question"])
            if errs:
                for e in errs:
                    report.error("SCHEMA_INVALID", e, part, lineno)
                continue
            qid = obj["questionId"]
            if qid in questions:
                prev_path, prev_line = q_line[qid]
                report.error(
                    "QID_DUPLICATE",
                    f"questionId {qid} 重复（首次出现在 {prev_path}:{prev_line}）",
                    part,
                    lineno,
                )
                continue
            questions[qid] = obj
            q_line[qid] = (part, lineno)
            order.append(qid)

    # 题型 / 选项不变量
    counts = {"questions": len(order), "singleChoice": 0, "multipleChoice": 0, "trueFalse": 0, "shortAnswer": 0, "assets": 0}
    for qid in order:
        q = questions[qid]
        qtype = q["type"]
        counts[TYPE_LABEL[qtype]] = counts.get(TYPE_LABEL[qtype], 0) + 1
        part, lineno = q_line[qid]
        grading = (q.get("grading") or {}).get("strategy")
        if grading is not None:
            if qtype in CHOICE_TYPES:
                want = "exact-set"
            elif qtype == "ordering":
                want = "ordering"
            else:
                want = "strict-text"
            if grading != want and not (qtype == "short_answer" and grading == "keywords"):
                report.error(
                    "GRADING_MISMATCH",
                    f"{qid} 题型 {qtype} 应使用 {want}，实际声明 {grading}",
                    part,
                    lineno,
                )
        if qtype in FRAGMENT_TYPES:
            opt_ids = q.get("optionIds") or []
            correct = q.get("correctOptionIds") or []
            if len(opt_ids) != len(set(opt_ids)):
                report.error("OID_DUPLICATE", f"{qid} 的 optionIds 有重复", part, lineno)
            if len(correct) != len(set(correct)):
                report.error("OID_DUPLICATE", f"{qid} 的 correctOptionIds 有重复", part, lineno)
            if not set(correct) <= set(opt_ids):
                report.error(
                    "OPTION_SUBSET",
                    f"{qid} 的正确答案 {sorted(set(correct) - set(opt_ids))} 不在 optionIds 中",
                    part,
                    lineno,
                )
            if qtype in ("single_choice", "true_false") and len(correct) != 1:
                report.error("ANSWER_COUNT", f"{qid} 是 {qtype}，正确答案必须恰好 1 个，实际 {len(correct)}", part, lineno)
            if qtype == "multiple_choice" and len(correct) < 2:
                report.error("ANSWER_COUNT", f"{qid} 是多选，正确答案至少 2 个，实际 {len(correct)}", part, lineno)
            if qtype == "true_false" and len(opt_ids) != 2:
                report.error("OPTION_COUNT", f"{qid} 是判断题，选项必须恰好 2 个，实际 {len(opt_ids)}", part, lineno)
            if qtype == "ordering":
                if len(correct) < 2:
                    report.error("ANSWER_COUNT", f"{qid} 是重组题，正确片段至少 2 个，实际 {len(correct)}", part, lineno)
                alts = ((q.get("grading") or {}).get("alternatives")) or []
                for i, alt in enumerate(alts, 1):
                    if set(alt) != set(correct):
                        report.error(
                            "ANSWER_COUNT",
                            f"{qid} 的等价次序 {i} 与正确片段集合不一致",
                            part,
                            lineno,
                        )
    counts["questions"] = len(order)
    report.counts = counts

    # ---------- 3b. 出处声明 + 整包摘要 ----------
    # 放在题目语义校验之后：出处要引用 sourceId，得先确定题是真的。
    provenance.check(manifest, questions, q_line, actual, report)
    if manifest.get("sources") or any(q.get("origin") or q.get("sourceId") for q in questions.values()):
        report.provenance = provenance.summarize(questions, manifest.get("sources") or [])
    report.content_digest = str(manifest.get("contentDigest") or "")

    # ---------- 4. 媒体 ----------
    assets: dict[str, dict] = {}
    media_paths: set[str] = set()
    total_media = 0
    index_path = "media/index.ndjson"
    media_files = [p for p in actual if p.startswith("media/") and p != index_path]
    if index_path in actual:
        for lineno, obj in _read_ndjson(contents.text(index_path), index_path, report):
            if not isinstance(obj, dict):
                report.error("SCHEMA_INVALID", "媒体索引每行必须是对象", index_path, lineno)
                continue
            errs = schema_validate(obj, schemas["media"])
            if errs:
                for e in errs:
                    report.error("MEDIA_SCHEMA_INVALID", e, index_path, lineno)
                continue
            aid = obj["assetId"]
            if aid in assets:
                report.error("ASSET_DUPLICATE", f"assetId {aid} 重复", index_path, lineno)
                continue
            p = obj["path"]
            try:
                safe_relpath(p)
            except PackageError as exc:
                report.error(exc.code, exc.message, index_path, lineno)
                continue
            if not p.startswith("media/"):
                report.error("MEDIA_PATH", f"{aid} 的 path 必须以 media/ 开头", index_path, lineno)
                continue
            if obj["bytes"] > MAX_MEDIA_BYTES:
                report.error("MEDIA_TOO_LARGE", f"{p} 超过单文件上限 20 MiB", index_path, lineno)
            data = actual.get(p)
            if data is None:
                report.error("MEDIA_MISSING", f"{aid} 声明的文件 {p} 不在包内", index_path, lineno)
                continue
            if len(data) != obj["bytes"]:
                report.error("MEDIA_SIZE_MISMATCH", f"{p} 实际 {len(data)} 字节，索引写 {obj['bytes']}", index_path, lineno)
            if sha256_bytes(data) != obj["sha256"]:
                report.error("MEDIA_HASH_MISMATCH", f"{p} 的 sha256 不符", index_path, lineno)
            if not _magic_matches(data, obj["mimeType"]):
                report.error("MEDIA_TYPE_MISMATCH", f"{p} 的实际类型与声明的 {obj['mimeType']} 不符", index_path, lineno)
            total_media += len(data)
            assets[aid] = obj
            media_paths.add(p)
        if total_media > MAX_TOTAL_MEDIA_BYTES:
            report.error("MEDIA_TOO_LARGE", "媒体总量超过 200 MiB", index_path)
        for p in media_files:
            if p not in media_paths:
                report.error("MEDIA_UNREFERENCED", f"{p} 没有被 media/index.ndjson 引用", p)
        report.counts["assets"] = len(assets)
    elif media_files:
        report.error("MEDIA_INDEX_MISSING", "包内有 media/ 文件但缺少 media/index.ndjson", "media/")

    # ---------- 5. 文案层 ----------
    locale_dirs = sorted(
        p.split("/")[1] for p in actual if re.match(r"^locales/[^/]+/questions\.ndjson$", p)
    )
    for loc in locales:
        if loc not in locale_dirs:
            report.error("LOCALE_MISSING", f"manifest 声明了 {loc}，但缺少 locales/{loc}/questions.ndjson")
    for loc in locale_dirs:
        if loc not in locales:
            report.error("LOCALE_UNDECLARED", f"包内有 locales/{loc}/，但 manifest 未声明该语言")
    for p in actual:
        m = re.match(r"^locales/([^/]+)/(.+)$", p)
        if m and m.group(2) != "questions.ndjson":
            report.error("ZIP_ENTRY_UNEXPECTED", f"locales/{m.group(1)}/ 下出现未约定的文件 {m.group(2)}", p)

    localized: dict[str, dict[str, dict]] = {}
    for loc in locale_dirs:
        lpath = f"locales/{loc}/questions.ndjson"
        records = _read_ndjson(contents.text(lpath), lpath, report)
        seen: dict[str, dict] = {}
        for lineno, obj in records:
            if not isinstance(obj, dict):
                report.error("SCHEMA_INVALID", "文案层每行必须是对象", lpath, lineno)
                continue
            errs = schema_validate(obj, schemas["localization"])
            if errs:
                for e in errs:
                    report.error("SCHEMA_INVALID", e, lpath, lineno)
                # 选项数量不合法时 minItems 会先报错；这里仍补上更准确的 OPTION_MISMATCH，
                # 否则作者只能看到"元素太少"，看不出真正原因是"和结构层对不上"。
                if "options" in obj and isinstance(obj["options"], list) and obj.get("questionId") in questions:
                    q0 = questions[obj["questionId"]]
                    got0 = {o.get("optionId") for o in obj["options"] if isinstance(o, dict)}
                    want0 = set(q0.get("optionIds") or [])
                    if q0["type"] in FRAGMENT_TYPES and got0 != want0:
                        report.error(
                            "OPTION_MISMATCH",
                            f"{obj['questionId']} 文案层选项与结构层不一致"
                            f"（缺 {sorted(want0 - got0) or '无'}，多 {sorted(got0 - want0) or '无'}）",
                            lpath,
                            lineno,
                        )
                continue
            qid = obj["questionId"]
            if obj["locale"] != loc:
                report.error("LOCALE_MISMATCH", f"{qid} 行内 locale={obj['locale']} 与目录 {loc} 不一致", lpath, lineno)
            if qid not in questions:
                report.error("LOCALE_UNKNOWN_QID", f"{qid} 在结构层不存在", lpath, lineno)
                continue
            if qid in seen:
                report.error("LOCALE_DUPLICATE", f"{loc} 中 {qid} 重复出现", lpath, lineno)
                continue
            q = questions[qid]
            if q["type"] in FRAGMENT_TYPES:
                got = [o["optionId"] for o in obj.get("options") or []]
                if len(got) != len(set(got)):
                    report.error("OID_DUPLICATE", f"{qid} 的文案层选项 id 重复", lpath, lineno)
                if set(got) != set(q.get("optionIds") or []):
                    missing = sorted(set(q.get("optionIds") or []) - set(got))
                    extra = sorted(set(got) - set(q.get("optionIds") or []))
                    report.error(
                        "OPTION_MISMATCH",
                        f"{qid} 文案层选项与结构层不一致（缺 {missing or '无'}，多 {extra or '无'}）",
                        lpath,
                        lineno,
                    )
            elif q["type"] == "short_answer":
                if not obj.get("answerText"):
                    report.error("ANSWER_MISSING", f"{qid} 是简答题，缺少 answerText", lpath, lineno)
                if obj.get("options"):
                    report.error("MC_OPTIONS_ON_OPEN_QUESTION", f"{qid} 是简答题，不应带 options", lpath, lineno)
            for field_name in ("promptMarkdown", "explanationMarkdown", "answerText"):
                val = obj.get(field_name)
                if isinstance(val, str):
                    _check_content(val, lpath, lineno, report, f"{qid}.{field_name}")
            for o in obj.get("options") or []:
                _check_content(o["contentMarkdown"], lpath, lineno, report, f"{qid}.{o['optionId']}")
            if obj.get("assets"):
                for aid in obj["assets"]:
                    if aid not in assets:
                        report.error("MEDIA_UNKNOWN_ASSET", f"{qid} 引用了不存在的 assetId {aid}", lpath, lineno)
            seen[qid] = obj
        localized[loc] = seen
        report.locales[loc] = {"covered": len(seen), "total": len(questions)}

    src_covered = report.locales.get(source_locale, {}).get("covered", 0)
    if src_covered != len(questions):
        missing = sorted(set(questions) - set(localized.get(source_locale, {})))
        report.error(
            "LOCALE_INCOMPLETE",
            f"sourceLocale {source_locale} 未覆盖全部题目，缺 {len(missing)} 题：{', '.join(missing[:10])}",
        )
    for loc, seen in localized.items():
        if loc == source_locale:
            continue
        missing = len(questions) - len(seen)
        if missing:
            report.warn("LOCALE_PARTIAL", f"{loc} 只覆盖 {len(seen)}/{len(questions)} 题，缺 {missing} 题")

    # ---------- 6. 增强资料 ----------
    enh_index = "enhancements/index.ndjson"
    enh_kinds: dict[str, int] = {}
    enh_paths: set[str] = set()
    enh_payloads = [p for p in actual if p.startswith("enhancements/") and p != enh_index]
    if enh_index in actual:
        for lineno, obj in _read_ndjson(contents.text(enh_index), enh_index, report):
            if not isinstance(obj, dict):
                report.error("SCHEMA_INVALID", "增强索引每行必须是对象", enh_index, lineno)
                continue
            errs = schema_validate(obj, schemas["enhancement"])
            if errs:
                for e in errs:
                    report.error("ENHANCEMENT_SCHEMA_INVALID", e, enh_index, lineno)
                continue
            qid = obj["questionId"]
            if qid not in questions:
                report.error("ENHANCEMENT_UNKNOWN_QID", f"{qid} 在结构层不存在", enh_index, lineno)
            if obj["locale"] not in locales:
                report.error("ENHANCEMENT_UNKNOWN_LOCALE", f"{obj['locale']} 未在 manifest 声明", enh_index, lineno)
            elif qid in questions and qid not in localized.get(obj["locale"], {}):
                report.error(
                    "ENHANCEMENT_MISSING_BASE",
                    f"{qid} 在 {obj['locale']} 下没有文案，增强资料无法绑定",
                    enh_index,
                    lineno,
                )
            if obj["path"] not in actual:
                report.error("ENHANCEMENT_PATH_MISSING", f"引用的文件 {obj['path']} 不在包内", enh_index, lineno)
            else:
                enh_paths.add(obj["path"])
            # 答案指纹：声明了就要求与当前题干/答案一致，否则这条增强是"对着旧答案写的"。
            # 未声明则不报错（字段可选），但也就没有失效检测 —— 规范 §8 已注明。
            declared_hash = obj.get("answerHash")
            if declared_hash:
                base = localized.get(obj["locale"], {}).get(qid)
                if qid in questions and base is not None:
                    expected_hash = compute_answer_hash(questions[qid], base)
                    if declared_hash != expected_hash:
                        report.error(
                            "ENHANCEMENT_STALE",
                            f"{qid} 的 answerHash 与当前题干/答案不符"
                            f"（记录 {declared_hash}，实际应为 {expected_hash}）；"
                            f"说明题干或答案改过而这条增强没跟着更新。"
                            f"确认增强内容仍然成立后，用 validate_qbank.py answer-hash 重新取指纹",
                            enh_index,
                            lineno,
                        )
            enh_kinds[obj["kind"]] = enh_kinds.get(obj["kind"], 0) + 1
        for p in enh_payloads:
            if p not in enh_paths:
                report.warn("ENHANCEMENT_UNREFERENCED", f"{p} 没有被 enhancements/index.ndjson 引用", p)
        report.enhancements = enh_kinds
    elif enh_payloads:
        report.error("ENHANCEMENT_INDEX_MISSING", "包内有 enhancements/ 文件但缺少 index.ndjson", "enhancements/")

    # ---------- 7. 清单交叉检查 ----------
    if check_file_list:
        for p in listed:
            if p not in actual:
                continue  # 已在上面报 FILE_MISSING
    del check_file_list
