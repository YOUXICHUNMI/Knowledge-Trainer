"""KT-QBP 1.0：`.qbpatch` 题库更新补丁的构建与校验。

补丁不装题库，只描述"从哪一版改到哪一版"。它回答的是另一类问题：

    作者改了三道题的答案、加了两道题，已经练了四十题的用户怎么跟上？

三条不可让步的规则决定了这个格式长什么样：

1. **必须声明它基于哪一版**（`baseRevision` + `baseFingerprint`）。
   没有这个锚点，补丁就变成"盲改"—— 用户手上的题库如果已经被别的补丁改过，
   盲目覆盖会把内容改乱且无法察觉。应用端拿本地题库重算指纹比对，对不上就整包拒绝。

2. **只描述改动，不携带整库**。补丁里没有的题一律不动，所以用户本地那份题库
   不会因为"作者某次导出漏了两道题"而丢题。

3. **历史不是补丁的事**。补丁只改题库当前内容；已经发生的作答记录一条都不碰。
   哪几道题的答案变了，要单独列出来交给用户判断。

与 `.qbank` / `.qbext` 一样是 ZIP + manifest、零第三方依赖、可复现打包；
但与它们**不共用校验器**：三种包的必填字段、失败语义、锚点都不同，混在一起写
最终会得到一段"到底在校验谁"都说不清的代码。
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass, field

from .fingerprint import bank_fingerprint, question_fingerprint
from .package import PackageError, read_package, safe_relpath, sha256_bytes, write_package

FORMAT_ID = "knowledge-trainer-bank-patch"
SUPPORTED_MAJOR = "1.0"
MANIFEST = "manifest.json"
MEDIA_PREFIX = "media/"
ALLOWED_TOP_LEVEL = ("manifest.json", "media/")

PATCH_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,63}$")
QID_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,63}$")
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
FINGERPRINT_RE = re.compile(r"^[0-9a-f]{16}$")   # FNV-1a 64 位，与 Kotlin Fingerprint 一致

# 没有 retitle 操作：改题库标题用 manifest.title 表达。
# 标题不是"一道题的改动"，混进操作列表会破坏"每条操作针对一道题"这个模型。
OPS = {"add", "update", "remove"}
# 题型与判题策略的对应关系：与 Kotlin `QuestionType` / `Question.defaultGradingFor` 一致
DEFAULT_GRADING = {
    "single_choice": "exact-set",
    "multiple_choice": "exact-set",
    "true_false": "exact-set",
    "ordering": "ordering",
    "short_answer": "strict-text",
}
ANSWER_TYPES = {"single_choice", "multiple_choice", "true_false"}
CHOICE_TYPES = ANSWER_TYPES | {"ordering"}

# update 能改哪些字段。**type 不在其中**：改题型会让判题策略、历史口径、
# 甚至"什么算答对"同时变化，那是"换了一道题"而不是"改了这道题"。
# 真要换题型就 remove + add，让人一眼看见。
# grading 同样不在其中：题型决定判题策略，题型不能改，策略自然也不能改。
# 要换判题规则（比如重写关键词表）也是 remove + add。
UPDATABLE = (
    "promptMarkdown",
    "options",
    "correctOptionIds",
    "answerText",
    "explanationMarkdown",
    "assetIds",
    "shuffleAllowed",
    "tags",
    # 出处三件套可以改：作者补上一个漏写的出处是最常见的合法修复。
    # 它们不参与内容指纹，所以改出处不会让逐题 expect 失效。
    "sourceId",
    "sourceLocator",
    "origin",
)

ORIGINS = ("authored", "adapted", "imported", "ai-assisted")

MAX_OPERATIONS = 2000
MAX_TEXT_CHARS = 20000
MAX_TITLE_CHARS = 120
MAX_ASSET_BYTES = 20 * 1024 * 1024
MAX_TOTAL_ASSET_BYTES = 200 * 1024 * 1024


@dataclass
class Report:
    ok: bool = True
    errors: list[dict] = field(default_factory=list)
    warnings: list[dict] = field(default_factory=list)
    counts: dict = field(default_factory=dict)
    patch_id: str = ""
    version: str = ""
    bank_id: str = ""
    base_revision: int = 0
    target_revision: int = 0
    base_fingerprint: str = ""

    def error(self, code: str, message: str, path: str = "", line: int | None = None) -> None:
        self.ok = False
        item = {"code": code, "message": message}
        if path:
            item["path"] = path
        if line is not None:
            item["line"] = line
        self.errors.append(item)

    def warn(self, code: str, message: str, path: str = "") -> None:
        item = {"code": code, "message": message}
        if path:
            item["path"] = path
        self.warnings.append(item)

    def as_dict(self) -> dict:
        return {
            "ok": self.ok,
            "patchId": self.patch_id,
            "version": self.version,
            "bankId": self.bank_id,
            "baseRevision": self.base_revision,
            "targetRevision": self.target_revision,
            "baseFingerprint": self.base_fingerprint,
            "counts": self.counts,
            "errors": self.errors,
            "warnings": self.warnings,
        }


def _text(value, limit: int = MAX_TEXT_CHARS) -> str | None:
    if not isinstance(value, str):
        return None
    if not value.strip():
        return None
    if len(value) > limit:
        return None
    return value


def _options(raw) -> list[dict] | None:
    """选项数组 → 规范化列表；结构不对返回 None（不逐项修，修一半的选项比没有更糟）。"""
    if not isinstance(raw, list) or len(raw) < 2:
        return None
    out = []
    seen = set()
    for item in raw:
        if not isinstance(item, dict):
            return None
        oid = item.get("optionId")
        text = item.get("contentMarkdown")
        if not isinstance(oid, str) or not QID_RE.match(oid):
            return None
        if oid in seen:
            return None
        if not isinstance(text, str) or not text.strip():
            return None
        seen.add(oid)
        out.append({"optionId": oid, "contentMarkdown": text})
    return out


def question_problems(q) -> list[str]:
    """一道**完整**题目的自洽性检查。

    与 Kotlin `QuestionRules` 一一对应：同一个题目在两个工具里必须得出同一个结论，
    否则作者这边通过、用户那边被拒，是最难查的一类问题。
    """
    if not isinstance(q, dict):
        return ["题目必须是 JSON 对象"]
    problems: list[str] = []

    qid = q.get("questionId")
    if not isinstance(qid, str) or not QID_RE.match(qid):
        problems.append("questionId 非法（小写字母数字开头，可用 . _ -）")
    qtype = q.get("type")
    if qtype not in DEFAULT_GRADING:
        problems.append(f"题型 {qtype!r} 无法识别")
        return problems  # 题型不认识，后面的规则都无从谈起

    if _text(q.get("promptMarkdown")) is None:
        problems.append("promptMarkdown 缺失、为空或超过 2 万字")

    explained = q.get("explanationMarkdown")
    if explained is not None and _text(explained) is None:
        problems.append("explanationMarkdown 不是合法文本")

    grading = q.get("grading")
    if grading is not None:
        if not isinstance(grading, dict) or grading.get("strategy") != DEFAULT_GRADING[qtype]:
            problems.append(f"{qtype} 只能使用判题策略 {DEFAULT_GRADING[qtype]}")

    tags = q.get("tags")
    if tags is not None and not (isinstance(tags, list) and all(isinstance(t, str) and t.strip() for t in tags)):
        problems.append("tags 必须是字符串数组")

    origin = q.get("origin")
    if origin is not None and origin not in ORIGINS:
        # 拼错的 origin 一律拒绝，不猜"最接近的那个"——猜错等于替作者声明了他没说过的事
        problems.append(f"origin={origin!r} 不在约定取值内（{'/'.join(ORIGINS)}）")
    source_id = q.get("sourceId")
    if source_id is not None and not (isinstance(source_id, str) and QID_RE.match(source_id)):
        problems.append("sourceId 非法（小写字母数字开头，可用 . _ -）")
    locator = q.get("sourceLocator")
    if locator is not None and not (isinstance(locator, str) and 0 < len(locator) <= 200):
        problems.append("sourceLocator 必须是 1~200 字的字符串")

    if qtype == "short_answer":
        if _text(q.get("answerText")) is None:
            problems.append("简答题缺少 answerText")
        if q.get("options"):
            problems.append("简答题不应该有选项")
        if q.get("correctOptionIds"):
            problems.append("简答题不应该有 correctOptionIds")
        return problems

    options = _options(q.get("options"))
    if options is None:
        problems.append("options 必须至少两项、optionId 唯一且文案非空")
        return problems

    ids = {o["optionId"] for o in options}
    correct = q.get("correctOptionIds")
    if not isinstance(correct, list) or not correct:
        problems.append("缺少 correctOptionIds")
        return problems
    if any(not isinstance(cid, str) or cid not in ids for cid in correct):
        problems.append("correctOptionIds 里有不在 options 中的 id")
        return problems
    if len(set(correct)) != len(correct):
        problems.append("correctOptionIds 有重复项")

    if qtype == "single_choice" and len(set(correct)) != 1:
        problems.append("单选题必须恰好一个正确答案")
    if qtype == "multiple_choice" and len(set(correct)) < 2:
        problems.append("多选题至少两个正确答案")
    if qtype == "true_false":
        if len(options) != 2:
            problems.append("判断题必须恰好两个选项")
        if len(set(correct)) != 1:
            problems.append("判断题必须恰好一个正确答案")
    if qtype == "ordering":
        # 重组题的 correctOptionIds 既是答案也是次序，必须是一个完整排列
        if len(correct) != len(options) or set(correct) != ids:
            problems.append("重组题的 correctOptionIds 必须是 options 的完整排列")

    if qtype == "short_answer" and q.get("answerText") is not None:
        problems.append("判为简答题时不应同时提供 answerText 与选项")
    return problems


def _coerce_question(raw: dict, where: str, report: Report) -> dict | None:
    """把 add 里的题目对象规范化成应用端认识的形状。"""
    problems = question_problems(raw)
    if problems:
        for p in problems:
            report.error("QUESTION_INVALID", f"{where}：{p}", MANIFEST)
        return None
    q = {
        "questionId": raw["questionId"],
        "type": raw["type"],
        "promptMarkdown": raw["promptMarkdown"],
        "correctOptionIds": [c for c in raw.get("correctOptionIds") or []],
        "answerText": raw.get("answerText") or None,
        "explanationMarkdown": raw.get("explanationMarkdown") or None,
        "tags": list(raw.get("tags") or []),
        "shuffleAllowed": raw.get("shuffleAllowed", True),
        "assetIds": list(raw.get("assetIds") or []),
        "grading": raw.get("grading") or {"strategy": DEFAULT_GRADING[raw["type"]]},
    }
    for key in ("sourceId", "sourceLocator", "origin"):
        if raw.get(key):
            q[key] = raw[key]
    if raw["type"] in CHOICE_TYPES:
        q["options"] = _options(raw.get("options")) or []
    return q


def validate_entries(entries: dict[str, bytes], manifest: dict) -> Report:
    """校验补丁内容（不含 manifest 自身字节校验，那一步在读取时完成）。"""
    report = Report()

    schema_version = manifest.get("schemaVersion")
    if not isinstance(schema_version, str) or not re.match(r"^1\.0\.\d+$", schema_version):
        report.error("SCHEMA_VERSION_UNSUPPORTED", f"schemaVersion 只支持 1.0.x，实际是 {schema_version!r}", MANIFEST)
    if manifest.get("format") != FORMAT_ID:
        report.error("MANIFEST_INVALID", f"format 必须是 {FORMAT_ID}", MANIFEST)

    patch_id = manifest.get("patchId")
    if not isinstance(patch_id, str) or not PATCH_ID_RE.match(patch_id):
        report.error("MANIFEST_INVALID", f"patchId 非法：{patch_id!r}（小写字母数字开头，可用 . _ -）", MANIFEST)
    report.patch_id = patch_id if isinstance(patch_id, str) else ""

    version = manifest.get("version")
    if not isinstance(version, str) or not VERSION_RE.match(version):
        report.error("MANIFEST_INVALID", f"version 必须是 主.次.修，实际是 {version!r}", MANIFEST)
    report.version = version if isinstance(version, str) else ""

    if _text(manifest.get("name"), MAX_TITLE_CHARS) is None:
        report.error("MANIFEST_INVALID", "缺少 name（补丁要有个给人看的名字）", MANIFEST)

    bank_id = manifest.get("bankId")
    if not isinstance(bank_id, str) or not UUID_RE.match(bank_id):
        report.error("MANIFEST_INVALID", f"bankId 必须是 UUID，实际是 {bank_id!r}", MANIFEST)
    report.bank_id = bank_id if isinstance(bank_id, str) else ""

    base_revision = manifest.get("baseRevision")
    target_revision = manifest.get("targetRevision")
    if not isinstance(base_revision, int) or base_revision < 1:
        report.error("MANIFEST_INVALID", "baseRevision 必须是正整数（补丁基于第几版）", MANIFEST)
        base_revision = 0
    if not isinstance(target_revision, int) or target_revision <= base_revision:
        report.error(
            "MANIFEST_INVALID",
            f"targetRevision 必须大于 baseRevision（{base_revision} → {target_revision}）",
            MANIFEST,
        )
        target_revision = 0
    report.base_revision = base_revision
    report.target_revision = target_revision

    base_fingerprint = manifest.get("baseFingerprint")
    if not isinstance(base_fingerprint, str) or not FINGERPRINT_RE.match(base_fingerprint):
        report.error(
            "BASE_FINGERPRINT_MISSING",
            "缺少 baseFingerprint（16 位十六进制，题库内容指纹）。它是「这份补丁改的是哪一版」的锚点，"
            "用 `--base 旧版.qbank` 让工具自动算，不要手写",
            MANIFEST,
        )
        base_fingerprint = ""
    report.base_fingerprint = base_fingerprint

    title = manifest.get("title")
    if title is not None and _text(title, MAX_TITLE_CHARS) is None:
        report.error("MANIFEST_INVALID", "title 不合法（非空且不超过 120 字）", MANIFEST)

    operations = manifest.get("operations")
    if not isinstance(operations, list) or not operations:
        report.error("PATCH_EMPTY", "operations 为空：一个什么都没改的补丁不该发出去", MANIFEST)
        operations = []
    if len(operations) > MAX_OPERATIONS:
        report.error("MANIFEST_INVALID", f"单个补丁最多 {MAX_OPERATIONS} 条操作", MANIFEST)

    counts = {"add": 0, "update": 0, "remove": 0}
    seen: dict[str, int] = {}
    for index, op in enumerate(operations):
        where = f"operations[{index}]"
        if not isinstance(op, dict):
            report.error("OPERATION_INVALID", f"{where} 必须是对象", MANIFEST)
            continue
        kind = op.get("op")
        if kind not in OPS:
            report.error("OPERATION_INVALID", f"{where} 的 op={kind!r} 不可识别（add / update / remove）", MANIFEST)
            continue
        counts[kind] += 1

        qid = op.get("questionId")
        if not isinstance(qid, str) or not QID_RE.match(qid):
            report.error("OPERATION_INVALID", f"{where} 的 questionId 非法：{qid!r}", MANIFEST)
            continue
        if qid in seen:
            # 同一道题在同一个补丁里出现两次，最终状态取决于顺序 —— 那是作者没想到的事
            report.error(
                "OPERATION_CONFLICT",
                f"{where} 与 operations[{seen[qid]}] 改的是同一道题 {qid}；"
                "同一道题只能有一条操作，先合并好再发",
                MANIFEST,
            )
            continue
        seen[qid] = index

        expect = op.get("expect")
        if expect is not None and (not isinstance(expect, str) or not re.match(r"^[0-9a-f]{16}$", expect)):
            report.error("OPERATION_INVALID", f"{where} 的 expect 必须是 16 位十六进制的题目指纹", MANIFEST)

        if kind == "add":
            if "question" not in op:
                report.error("OPERATION_INVALID", f"{where} 是 add 但没有 question", MANIFEST)
                continue
            question = op["question"]
            if not isinstance(question, dict):
                report.error("OPERATION_INVALID", f"{where} 的 question 必须是对象", MANIFEST)
                continue
            if question.get("questionId") != qid:
                report.error(
                    "OPERATION_INVALID",
                    f"{where} 的 questionId 与 question.questionId 不一致（{qid} / {question.get('questionId')!r}）",
                    MANIFEST,
                )
                continue
            _coerce_question(question, where, report)
            if expect is not None:
                report.warn("EXPECT_IGNORED", f"{where} 是 add，expect 没有意义（新题在旧库里不存在）", MANIFEST)

        if kind == "update":
            patch_set = op.get("set")
            if not isinstance(patch_set, dict) or not patch_set:
                report.error("OPERATION_INVALID", f"{where} 是 update 但没有 set（要改什么）", MANIFEST)
                continue
            for key in patch_set:
                if key not in UPDATABLE:
                    report.error(
                        "OPERATION_INVALID",
                        f"{where} 试图修改 {key}；update 只能改 {'/'.join(UPDATABLE)}。"
                        "改题型请用 remove + add —— 题型一变，判题口径就变了",
                        MANIFEST,
                    )
            if "type" in patch_set:
                continue
            # 能就地判断的几项先判：选项与答案是否对得上
            if "options" in patch_set and _options(patch_set["options"]) is None:
                report.error("OPERATION_INVALID", f"{where} 的 set.options 不合法（至少两项、id 唯一、文案非空）", MANIFEST)
            if "correctOptionIds" in patch_set:
                correct = patch_set["correctOptionIds"]
                if not isinstance(correct, list) or not correct or not all(isinstance(c, str) for c in correct):
                    report.error("OPERATION_INVALID", f"{where} 的 set.correctOptionIds 必须是非空字符串数组", MANIFEST)

    if counts["add"] + counts["update"] + counts["remove"] == 0:
        report.error("PATCH_EMPTY", "补丁里没有任何实际操作", MANIFEST)

    # ---------- 媒体清单 ----------
    listed: dict[str, str] = {}
    files = manifest.get("files")
    if files is None:
        files = []
    if not isinstance(files, list):
        report.error("MANIFEST_INVALID", "files 必须是数组", MANIFEST)
        files = []
    for item in files:
        if not isinstance(item, dict):
            report.error("MANIFEST_INVALID", "files 里每项必须是 {path, sha256}", MANIFEST)
            continue
        path = item.get("path")
        sha = item.get("sha256")
        if not isinstance(path, str) or not isinstance(sha, str):
            report.error("MANIFEST_INVALID", "files 里每项必须是 {path, sha256}", MANIFEST)
            continue
        if path in listed:
            report.error("MANIFEST_INVALID", f"files 中重复列出 {path}", MANIFEST)
        listed[path] = sha

    for path, expected in listed.items():
        if path not in entries:
            report.error("FILE_MISSING", f"清单声明了 {path}，但包里没有该文件", path)
            continue
        got = sha256_bytes(entries[path])
        if got != expected:
            report.error("HASH_MISMATCH", f"{path} 的摘要与清单不符，文件可能被改动或损坏", path)
    for path in entries:
        if path not in listed:
            report.error("FILE_NOT_LISTED", f"包内有未列入清单的文件：{path}", path)

    total_asset = 0
    for path, data in entries.items():
        if not path.startswith(MEDIA_PREFIX):
            continue
        if len(data) > MAX_ASSET_BYTES:
            report.error("MEDIA_TOO_LARGE", f"{path} 超过单文件 {MAX_ASSET_BYTES // 1024 // 1024} MiB", path)
        total_asset += len(data)
    if total_asset > MAX_TOTAL_ASSET_BYTES:
        report.error("MEDIA_TOO_LARGE", "补丁内媒体合计超过 200 MiB", MEDIA_PREFIX)

    report.counts = counts
    return report


def validate_package(path: str) -> Report:
    report = Report()
    try:
        contents = read_package(path, ALLOWED_TOP_LEVEL)
    except PackageError as exc:
        report.error(exc.code, exc.message, exc.path)
        return report
    entries = {k: v for k, v in contents.entries.items() if k != MANIFEST}
    return validate_entries(entries, contents.manifest)


# ---------------------------------------------------------------- 读取基础题库


def read_base_bank(path: str) -> tuple[dict, list[dict]]:
    """把一个 `.qbank` 读成"应用端眼里的题目列表"。

    这里**不是**完整校验（那是 validator.py 的职责，build 时会先调用它），
    只做"读出结构层 + 源语言文案层并合并"。之所以需要它，是因为补丁的锚点
    （baseFingerprint、每题的 expect）必须由工具算 —— 让人手抄指纹是这一块
    最容易出错的地方。
    """
    contents = read_package(path)
    manifest = contents.manifest
    source = manifest.get("sourceLocale") or ""

    structure: dict[str, dict] = {}
    order: list[str] = []
    parts = sorted(p for p in contents.entries if re.match(r"^questions/part-[0-9]{4}\.ndjson$", p))
    if not parts:
        raise PackageError("SCHEMA_INVALID", "基础题库缺少 questions/part-0001.ndjson")
    for part in parts:
        for line in contents.text(part).splitlines():
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            qid = obj.get("questionId")
            structure[qid] = obj
            order.append(qid)

    texts: dict[str, dict] = {}
    locale_path = f"locales/{source}/questions.ndjson"
    if locale_path not in contents.entries:
        raise PackageError("LOCALE_MISSING", f"基础题库缺少源语言文案 {locale_path}", locale_path)
    for line in contents.text(locale_path).splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        texts[obj.get("questionId")] = obj

    questions: list[dict] = []
    for qid in order:
        s = structure[qid]
        loc = texts.get(qid)
        if loc is None:
            raise PackageError("LOCALE_INCOMPLETE", f"{qid} 在源语言文案里没有条目", locale_path)
        qtype = s.get("type")
        options = []
        if qtype in CHOICE_TYPES:
            option_ids = list(s.get("optionIds") or [])
            by_id = {o.get("optionId"): o.get("contentMarkdown") for o in (loc.get("options") or [])}
            options = [{"optionId": oid, "contentMarkdown": by_id.get(oid) or ""} for oid in option_ids]
        questions.append(
            {
                "questionId": qid,
                "type": qtype,
                "promptMarkdown": loc.get("promptMarkdown") or "",
                "options": options,
                "correctOptionIds": list(s.get("correctOptionIds") or []),
                "answerText": loc.get("answerText") or None,
            }
        )
    return manifest, questions


# ---------------------------------------------------------------- 构建


def _collect(root: str) -> dict[str, bytes]:
    entries: dict[str, bytes] = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d != "__pycache__"]
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if rel == MANIFEST or name.startswith("."):
                continue
            safe_relpath(rel)
            with open(full, "rb") as fh:
                entries[rel] = fh.read()
    return entries


def build(
    authoring_dir: str,
    out_path: str | None = None,
    base_package: str | None = None,
    *,
    allow_invalid: bool = False,
) -> tuple[bool, str, Report]:
    """把 authoring 目录 + 旧版 `.qbank` 打成 `.qbpatch`。

    `base_package` 用来算 baseFingerprint 并给每条操作补 `expect`。
    不提供时必须自己写好 baseFingerprint，否则拒绝 —— 锚点不能靠"我觉得应该是"。
    """
    manifest_path = os.path.join(authoring_dir, MANIFEST)
    report = Report()
    if not os.path.isfile(manifest_path):
        report.error("MANIFEST_MISSING", f"缺少 {manifest_path}")
        return False, "", report
    with open(manifest_path, "r", encoding="utf-8-sig") as fh:
        try:
            manifest = json.load(fh)
        except json.JSONDecodeError as exc:
            report.error("MANIFEST_INVALID", f"manifest.json 不是合法 JSON：{exc}")
            return False, "", report
    if not isinstance(manifest, dict):
        report.error("MANIFEST_INVALID", "manifest 顶层必须是对象")
        return False, "", report

    base_questions: dict[str, dict] = {}
    base_meta: dict = {}
    if base_package:
        try:
            base_meta, base_list = read_base_bank(base_package)
        except PackageError as exc:
            report.error(exc.code, f"基础题库读不出来：{exc.message}", exc.path)
            return False, "", report
        base_questions = {q["questionId"]: q for q in base_list}
        base_fp = bank_fingerprint(base_list)

        declared_bank = manifest.get("bankId")
        if declared_bank and declared_bank != base_meta.get("bankId"):
            report.error(
                "BASE_BANK_MISMATCH",
                f"manifest.bankId={declared_bank} 与基础题库的 {base_meta.get('bankId')} 不一致；"
                "补丁只能改它声明的那一个题库",
            )
        manifest["bankId"] = base_meta.get("bankId") or declared_bank
        manifest["baseFingerprint"] = base_fp
        if not isinstance(manifest.get("baseRevision"), int):
            manifest["baseRevision"] = int(base_meta.get("contentRevision") or 1)
            report.warn("BASE_REVISION_DERIVED", f"baseRevision 未写，按基础题库的第 {manifest['baseRevision']} 版")

        # 逐条补 expect：作者不用手抄指纹，且抄错会被当场发现
        for index, op in enumerate(manifest.get("operations") or []):
            if not isinstance(op, dict) or op.get("op") not in ("update", "remove"):
                continue
            qid = op.get("questionId")
            base_q = base_questions.get(qid)
            if base_q is None:
                report.error("BASE_QUESTION_MISSING", f"operations[{index}] 要改的 {qid} 在基础题库里不存在")
                continue
            actual = question_fingerprint(base_q)
            declared = op.get("expect")
            if declared is not None and declared != actual:
                report.error(
                    "EXPECT_MISMATCH",
                    f"operations[{index}] 的 expect={declared} 与基础题库里 {qid} 的实际指纹 {actual} 不符；"
                    "多半是这个补丁基于的不是这一版题库",
                )
            op["expect"] = actual
        for op in manifest.get("operations") or []:
            if isinstance(op, dict) and op.get("op") == "add":
                if op.get("questionId") in base_questions:
                    report.error(
                        "PATCH_ADD_EXISTING",
                        f"add 的 {op.get('questionId')} 在基础题库里已经存在；改已有题目请用 update",
                    )
    elif not manifest.get("baseFingerprint"):
        report.error(
            "BASE_FINGERPRINT_MISSING",
            "没有提供 --base 旧版.qbank，manifest 里也没有 baseFingerprint；"
            "补丁必须能说清它基于哪一版，否则应用端无法判断该不该改",
        )

    if not isinstance(manifest.get("targetRevision"), int):
        base_revision = manifest.get("baseRevision")
        if isinstance(base_revision, int):
            manifest["targetRevision"] = base_revision + 1
            report.warn("TARGET_REVISION_DERIVED", f"targetRevision 未写，按 baseRevision+1 = {manifest['targetRevision']}")

    entries = _collect(authoring_dir)
    # 作者手写的 files 一律丢弃重算：手抄哈希是最常见的错误来源
    manifest["files"] = [{"path": path, "sha256": sha256_bytes(entries[path])} for path in sorted(entries)]

    checked = validate_entries(entries, manifest)
    # 把前面几步（读基础包、对 expect）的错误一并带上，否则作者只看到"格式没问题"
    checked.errors = report.errors + checked.errors
    checked.warnings = report.warnings + checked.warnings
    if checked.errors:
        checked.ok = False
    report = checked
    if not report.ok and not allow_invalid:
        return False, "", report

    if out_path is None:
        stem = str(manifest.get("patchId") or "patch").strip() or "patch"
        out_path = os.path.join(os.path.dirname(os.path.abspath(authoring_dir)), f"{stem}.qbpatch")

    try:
        write_package(entries, manifest, out_path)
    except PackageError as exc:
        report.error(exc.code, exc.message, exc.path)
        return False, out_path, report

    # 回读磁盘字节再校验：证明"写出去的"和"校验过的"是同一份
    post = validate_package(out_path)
    return post.ok, out_path, post


def main(argv: list[str] | None = None) -> int:
    import argparse

    argv = argv if argv is not None else sys.argv[1:]
    parser = argparse.ArgumentParser(prog="qbpatch", description="KT-QBP 1.0 .qbpatch 题库补丁的构建与校验")
    sub = parser.add_subparsers(dest="command", required=True)

    p_build = sub.add_parser("build", help="把 authoring 目录 + 旧版 .qbank 打成 .qbpatch")
    p_build.add_argument("authoring_dir")
    p_build.add_argument("-o", "--output")
    p_build.add_argument("--base", dest="base_package", help="补丁基于的旧版 .qbank（用来算指纹与 expect）")
    p_build.add_argument("--json", action="store_true")

    p_validate = sub.add_parser("validate", help="校验一个 .qbpatch")
    p_validate.add_argument("package")
    p_validate.add_argument("--json", action="store_true")

    p_fp = sub.add_parser("fingerprint", help="打印一个 .qbank 的题库指纹与逐题指纹")
    p_fp.add_argument("package")
    p_fp.add_argument("--json", action="store_true")

    args = parser.parse_args(argv)

    if args.command == "fingerprint":
        try:
            manifest, questions = read_base_bank(args.package)
        except PackageError as exc:
            print(f"FAILED [{exc.code}] {exc.message}")
            return 1
        rows = [{"questionId": q["questionId"], "fingerprint": question_fingerprint(q)} for q in questions]
        total = bank_fingerprint(questions)
        if args.json:
            print(json.dumps({"ok": True, "bankId": manifest.get("bankId"), "fingerprint": total, "questions": rows},
                             ensure_ascii=False, indent=2))
        else:
            print(f"bankId     {manifest.get('bankId')}")
            print(f"fingerprint {total}")
            for row in rows:
                print(f"  {row['fingerprint']}  {row['questionId']}")
        return 0

    if args.command == "build":
        ok, out, report = build(args.authoring_dir, args.output, args.base_package)
        if args.json:
            print(json.dumps({"ok": ok, "output": out, **report.as_dict()}, ensure_ascii=False, indent=2))
        elif ok:
            print(f"OK  {out}")
            print(f"    patchId={report.patch_id} v{report.version}")
            print(f"    题库 {report.bank_id}  第 {report.base_revision} 版 → 第 {report.target_revision} 版")
            print(f"    基础指纹 {report.base_fingerprint}")
            counts = report.counts
            print(f"    新增 {counts.get('add', 0)} / 修改 {counts.get('update', 0)} / 删除 {counts.get('remove', 0)}")
            for w in report.warnings:
                print(f"    warn [{w['code']}] {w['message']}")
        else:
            print("FAILED")
            for e in report.errors[:20]:
                print(f"  [{e['code']}] {e['message']}")
        return 0 if ok else 1

    report = validate_package(args.package)
    if args.json:
        print(json.dumps(report.as_dict(), ensure_ascii=False, indent=2))
    elif report.ok:
        print(f"OK  {args.package}")
        print(f"    patchId={report.patch_id} v{report.version}  题库 {report.bank_id}")
        print(f"    第 {report.base_revision} 版 → 第 {report.target_revision} 版")
        counts = report.counts
        print(f"    新增 {counts.get('add', 0)} / 修改 {counts.get('update', 0)} / 删除 {counts.get('remove', 0)}")
        for w in report.warnings:
            print(f"    warn [{w['code']}] {w['message']}")
    else:
        print("FAILED")
        for e in report.errors[:20]:
            location = e.get("path", "")
            if e.get("line"):
                location = f"{location}:{e['line']}"
            print(f"  [{e['code']}] {location} {e['message']}")
    return 0 if report.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
