"""KT-QBX 1.0：`.qbext` 扩展包的构建与校验。

扩展包不装题库，只往用户本机添加两样东西：跨库关系与讲解注释。
它绑定**一个** bankId（UUID），并且只能给这个题库加"出口"关系 ——
目标端可以指向任何库，那正是跨库的意义。

与 KT-QBF 一样是零第三方依赖、可复现打包；与 KT-QBF 的校验器分开写，
因为两种包的必填字段与失败语义不同，混在一个函数里会写出"到底在校验谁"都说不清的代码。
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass, field

from .package import PackageError, read_package, safe_relpath, sha256_bytes, write_package

FORMAT_ID = "knowledge-trainer-bank-extension"
SUPPORTED_MAJOR = "1.0"
MANIFEST = "manifest.json"
RELATIONS_FILE = "relations/edges.ndjson"
ANNOTATIONS_FILE = "annotations/annotations.ndjson"
ALLOWED_TOP_LEVEL = ("manifest.json", "relations/", "annotations/")

PACKAGE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,63}$")
QID_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,63}$")
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")

RELATIONS = {"related", "prerequisite", "explains", "contrasts"}
DIRECTIONAL = {"prerequisite", "explains"}  # 只有它们能承载判题硬依赖
DEPENDENCIES = {"navigation", "hard"}

MAX_RELATIONS = 5000
MAX_ANNOTATIONS = 2000
MAX_ANNOTATION_CHARS = 20000
MAX_NOTE_CHARS = 2000


@dataclass
class Report:
    ok: bool = True
    errors: list[dict] = field(default_factory=list)
    warnings: list[dict] = field(default_factory=list)
    counts: dict = field(default_factory=dict)
    package_id: str = ""
    version: str = ""
    bank_id: str = ""

    def error(self, code: str, message: str, path: str = "", line: int | None = None) -> None:
        self.ok = False
        item = {"code": code, "message": message}
        if path:
            item["path"] = path
        if line is not None:
            item["line"] = line
        self.errors.append(item)

    def warn(self, code: str, message: str, path: str = "", line: int | None = None) -> None:
        item = {"code": code, "message": message}
        if path:
            item["path"] = path
        if line is not None:
            item["line"] = line
        self.warnings.append(item)

    def as_dict(self) -> dict:
        return {
            "ok": self.ok,
            "packageId": self.package_id,
            "version": self.version,
            "bankId": self.bank_id,
            "counts": self.counts,
            "errors": self.errors,
            "warnings": self.warnings,
        }


def _json_lines(text: str):
    """逐行给出 (行号, 对象或 None)。空行与 # 注释跳过，行号仍是文件里的真实行号。"""
    for idx, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            yield idx, None
            continue
        yield idx, obj


def validate_entries(entries: dict[str, bytes], manifest: dict) -> Report:
    report = Report()

    schema_version = str(manifest.get("schemaVersion", ""))
    if not re.match(r"^1\.0\.\d+$", schema_version):
        report.error("SCHEMA_VERSION_UNSUPPORTED", f"schemaVersion 只支持 1.0.x，实际是 {schema_version!r}", MANIFEST)
    if manifest.get("format") != FORMAT_ID:
        report.error("MANIFEST_INVALID", f"format 必须是 {FORMAT_ID}", MANIFEST)

    package_id = str(manifest.get("packageId", ""))
    if not PACKAGE_ID_RE.match(package_id):
        report.error(
            "MANIFEST_INVALID",
            f"packageId 非法：{package_id!r}（小写字母数字开头，可用 . _ -，最长 64）",
            MANIFEST,
        )
    report.package_id = package_id

    version = str(manifest.get("version", ""))
    if not VERSION_RE.match(version):
        report.error("MANIFEST_INVALID", f"version 必须是 主.次.修 形式，实际是 {version!r}", MANIFEST)
    report.version = version

    if not str(manifest.get("name", "")).strip():
        report.error("MANIFEST_INVALID", "缺少 name", MANIFEST)

    binds = manifest.get("binds")
    bank_id = ""
    if not isinstance(binds, dict):
        report.error("BINDING_INVALID", "缺少 binds 对象；扩展包必须声明绑定哪个题库", MANIFEST)
    else:
        bank_id = str(binds.get("bankId", ""))
        if not UUID_RE.match(bank_id):
            report.error("BINDING_INVALID", f"binds.bankId 必须是题库 UUID，实际是 {bank_id!r}", MANIFEST)
    report.bank_id = bank_id

    # ---- 清单 ↔ 条目 ----
    listed: dict[str, str] = {}
    files = manifest.get("files")
    if not isinstance(files, list):
        report.error("MANIFEST_INVALID", "files 必须是数组（由打包器计算）", MANIFEST)
        files = []
    elif not files:
        # 空清单不是"格式错"，而是"装了个什么都没有的包"：错误码要说清这一点
        report.error("EXT_EMPTY", "files 为空：扩展包至少要带 relations/ 或 annotations/ 下的一个文件", MANIFEST)
    for item in files:
        if not isinstance(item, dict):
            report.error("MANIFEST_INVALID", "files 的每一项都必须是对象", MANIFEST)
            continue
        path = str(item.get("path", ""))
        digest = str(item.get("sha256", ""))
        if not path or not digest:
            report.error("MANIFEST_INVALID", "files 项缺少 path / sha256", MANIFEST)
            continue
        if path in listed:
            report.error("MANIFEST_INVALID", f"files 重复列出 {path}", MANIFEST)
            continue
        listed[path] = digest
        if not any(path.startswith(top) for top in ALLOWED_TOP_LEVEL):
            report.error(
                "ZIP_ENTRY_UNEXPECTED",
                f"不允许顶层路径 {path!r}（扩展包只允许 relations/ 与 annotations/）",
                path,
            )
    for path, digest in listed.items():
        if path not in entries:
            report.error("FILE_MISSING", f"清单声明了 {path}，但包里没有", path)
            continue
        if sha256_bytes(entries[path]) != digest:
            report.error("HASH_MISMATCH", f"{path} 的摘要与清单不符", path)
    for path in entries:
        if path not in listed:
            report.error("FILE_NOT_LISTED", f"包内有未列入清单的文件：{path}", path)

    if report.errors:
        return report

    # ---- 关系 ----
    seen_relation_ids: set[str] = set()
    relation_count = 0
    if RELATIONS_FILE in entries:
        text = entries[RELATIONS_FILE].decode("utf-8-sig", errors="replace")
        for lineno, obj in _json_lines(text):
            if obj is None or not isinstance(obj, dict):
                report.error("NDJSON_INVALID", f"第 {lineno} 行不是 JSON 对象", RELATIONS_FILE, lineno)
                continue
            relation_count += 1
            if relation_count > MAX_RELATIONS:
                report.error("EXT_TOO_MANY_RELATIONS", f"关系超过 {MAX_RELATIONS} 条", RELATIONS_FILE)
                break

            rid = str(obj.get("relationId", ""))
            if not QID_RE.match(rid):
                report.error("SCHEMA_INVALID", f"relationId 非法：{rid!r}", RELATIONS_FILE, lineno)
            elif rid in seen_relation_ids:
                report.error("EXT_RELATION_DUPLICATE", f"relationId {rid} 重复", RELATIONS_FILE, lineno)
            else:
                seen_relation_ids.add(rid)

            relation = str(obj.get("relation", ""))
            if relation not in RELATIONS:
                report.error("SCHEMA_INVALID", f"relation 无法识别：{relation!r}", RELATIONS_FILE, lineno)

            dependency = str(obj.get("dependency", "navigation"))
            if dependency not in DEPENDENCIES:
                report.error("SCHEMA_INVALID", f"dependency 无法识别：{dependency!r}", RELATIONS_FILE, lineno)
            if dependency == "hard" and relation not in DIRECTIONAL:
                report.error(
                    "EXT_HARD_NEEDS_DIRECTION",
                    f"「{relation}」是对称关系，说不清谁依赖谁，不能作为判题硬依赖",
                    RELATIONS_FILE,
                    lineno,
                )

            source = obj.get("source")
            target = obj.get("target")
            if not isinstance(source, dict) or not isinstance(target, dict):
                report.error("SCHEMA_INVALID", "缺少 source / target 对象", RELATIONS_FILE, lineno)
                continue
            source_bank = str(source.get("bankId", bank_id))
            if source_bank != bank_id:
                report.error(
                    "EXT_FOREIGN_SOURCE",
                    f"起点属于 {source_bank}，但本包绑定 {bank_id}（扩展包无权给别的题库加关系）",
                    RELATIONS_FILE,
                    lineno,
                )
            source_qid = str(source.get("questionId", ""))
            target_bank = str(target.get("bankId", ""))
            target_qid = str(target.get("questionId", ""))
            if not QID_RE.match(source_qid):
                report.error("SCHEMA_INVALID", f"source.questionId 非法：{source_qid!r}", RELATIONS_FILE, lineno)
            if not QID_RE.match(target_qid):
                report.error("SCHEMA_INVALID", f"target.questionId 非法：{target_qid!r}", RELATIONS_FILE, lineno)
            if not UUID_RE.match(target_bank):
                report.error("SCHEMA_INVALID", f"target.bankId 必须是 UUID，实际是 {target_bank!r}", RELATIONS_FILE, lineno)
            if source_bank == target_bank and source_qid == target_qid:
                report.error("EXT_RELATION_SELF", "一道题不能和自己建立关系", RELATIONS_FILE, lineno)
            note = str(obj.get("noteMarkdown", ""))
            if len(note) > MAX_NOTE_CHARS:
                report.warn("EXT_RELATION_NOTE_LONG", f"备注超过 {MAX_NOTE_CHARS} 字", RELATIONS_FILE, lineno)

    # ---- 注释 ----
    seen_annotation_ids: set[str] = set()
    annotation_count = 0
    if ANNOTATIONS_FILE in entries:
        text = entries[ANNOTATIONS_FILE].decode("utf-8-sig", errors="replace")
        for lineno, obj in _json_lines(text):
            if obj is None or not isinstance(obj, dict):
                report.error("NDJSON_INVALID", f"第 {lineno} 行不是 JSON 对象", ANNOTATIONS_FILE, lineno)
                continue
            annotation_count += 1
            if annotation_count > MAX_ANNOTATIONS:
                report.error("EXT_TOO_MANY_ANNOTATIONS", f"注释超过 {MAX_ANNOTATIONS} 条", ANNOTATIONS_FILE)
                break
            aid = str(obj.get("annotationId", ""))
            if not QID_RE.match(aid):
                report.error("SCHEMA_INVALID", f"annotationId 非法：{aid!r}", ANNOTATIONS_FILE, lineno)
            elif aid in seen_annotation_ids:
                report.error("EXT_ANNOTATION_DUPLICATE", f"annotationId {aid} 重复", ANNOTATIONS_FILE, lineno)
            else:
                seen_annotation_ids.add(aid)
            qid = obj.get("questionId")
            if qid is not None and not QID_RE.match(str(qid)):
                report.error("SCHEMA_INVALID", f"questionId 非法：{qid!r}", ANNOTATIONS_FILE, lineno)
            body = str(obj.get("bodyMarkdown", ""))
            if not body.strip():
                report.error("SCHEMA_INVALID", "bodyMarkdown 为空", ANNOTATIONS_FILE, lineno)
            elif len(body) > MAX_ANNOTATION_CHARS:
                report.error("SCHEMA_INVALID", f"bodyMarkdown 超过 {MAX_ANNOTATION_CHARS} 字", ANNOTATIONS_FILE, lineno)
            if not str(obj.get("title", "")).strip():
                report.warn("EXT_ANNOTATION_TITLE", "注释没有标题，列表里只会显示正文开头", ANNOTATIONS_FILE, lineno)

    if relation_count == 0 and annotation_count == 0:
        report.error("EXT_EMPTY", "既没有关系也没有注释，装上不会有任何变化")

    report.counts = {"relations": relation_count, "annotations": annotation_count}
    return report


def validate_package(path: str) -> Report:
    try:
        contents = read_package(path, ALLOWED_TOP_LEVEL)
    except PackageError as exc:
        report = Report()
        report.error(exc.code, exc.message, exc.path)
        return report
    entries = {k: v for k, v in contents.entries.items() if k != MANIFEST}
    return validate_entries(entries, contents.manifest)


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


def build(authoring_dir: str, out_path: str | None = None, *, allow_invalid: bool = False) -> tuple[bool, str, Report]:
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

    entries = _collect(authoring_dir)
    # 作者手写的 files 一律丢弃重算：手抄哈希是最常见的错误来源
    manifest["files"] = [{"path": path, "sha256": sha256_bytes(entries[path])} for path in sorted(entries)]

    report = validate_entries(entries, manifest)
    if not report.ok and not allow_invalid:
        return False, "", report

    if out_path is None:
        stem = str(manifest.get("packageId") or "extension").strip() or "extension"
        out_path = os.path.join(os.path.dirname(os.path.abspath(authoring_dir)), f"{stem}.qbext")

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
    parser = argparse.ArgumentParser(prog="qbext", description="KT-QBX 1.0 .qbext 扩展包的构建与校验")
    sub = parser.add_subparsers(dest="command", required=True)

    p_build = sub.add_parser("build", help="把 authoring 目录打包成 .qbext")
    p_build.add_argument("authoring_dir")
    p_build.add_argument("-o", "--output")
    p_build.add_argument("--json", action="store_true")

    p_validate = sub.add_parser("validate", help="校验一个 .qbext")
    p_validate.add_argument("package")
    p_validate.add_argument("--json", action="store_true")

    args = parser.parse_args(argv)

    if args.command == "build":
        ok, out, report = build(args.authoring_dir, args.output)
        if args.json:
            print(json.dumps({"ok": ok, "output": out, **report.as_dict()}, ensure_ascii=False, indent=2))
        elif ok:
            print(f"OK  {out}")
            print(f"    packageId={report.package_id} v{report.version}")
            print(f"    绑定 {report.bank_id}")
            print(f"    关系 {report.counts.get('relations', 0)} 条，注释 {report.counts.get('annotations', 0)} 条")
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
        print(f"    packageId={report.package_id} v{report.version} → 绑定 {report.bank_id}")
        print(f"    关系 {report.counts.get('relations', 0)} 条，注释 {report.counts.get('annotations', 0)} 条")
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
