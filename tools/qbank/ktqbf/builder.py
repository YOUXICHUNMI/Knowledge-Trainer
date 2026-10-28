"""KT-QBF 打包器：authoring 源目录 → 真 .qbank。

authoring 目录结构与包内结构一致（manifest.json + questions/ + locales/ + ...），
唯一区别是 manifest.json 里不写 files —— files 由打包器按实际字节计算后填入，
避免作者手抄哈希写错。
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field

from . import provenance
from .package import FORMAT_ID, PackageError, safe_relpath, sha256_bytes, write_package
from .validator import Report, validate_entries, validate_package


@dataclass
class BuildResult:
    ok: bool
    output: str = ""
    package_sha256: str = ""
    report: Report | None = None
    errors: list[dict] = field(default_factory=list)


def _collect(root: str) -> dict[str, bytes]:
    entries: dict[str, bytes] = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        # 跳过点目录与 __pycache__，避免把编辑器/构建残留打进包
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d != "__pycache__"]
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if rel == "manifest.json":
                continue
            if name.startswith("."):
                continue
            safe_relpath(rel)
            with open(full, "rb") as fh:
                entries[rel] = fh.read()
    return entries


def build(authoring_dir: str, out_path: str | None, spec_dir: str | None = None, *, allow_invalid: bool = False) -> BuildResult:
    manifest_path = os.path.join(authoring_dir, "manifest.json")
    if not os.path.isfile(manifest_path):
        return BuildResult(ok=False, errors=[{"code": "MANIFEST_MISSING", "message": f"缺少 {manifest_path}"}])

    with open(manifest_path, "r", encoding="utf-8-sig") as fh:
        try:
            manifest = json.load(fh)
        except json.JSONDecodeError as exc:
            return BuildResult(
                ok=False,
                errors=[{"code": "MANIFEST_INVALID", "message": f"manifest.json 不是合法 JSON: {exc}"}],
            )
    if not isinstance(manifest, dict):
        return BuildResult(ok=False, errors=[{"code": "MANIFEST_INVALID", "message": "manifest 顶层必须是对象"}])

    entries = _collect(authoring_dir)

    # 作者手写的 files 一律丢弃重算：占位符/过期哈希是最常见的错误来源
    manifest["files"] = [
        {"path": p, "sha256": sha256_bytes(entries[p])} for p in sorted(entries)
    ]
    # contentDigest 同理：由打包器算，不让作者手抄（抄错了只会得到"打包成功但导入被拒"）
    manifest["contentDigest"] = provenance.content_digest(manifest, entries)
    manifest.pop("_comment", None)

    # 先做语义校验，问题太多就不浪费时间打包
    report = validate_entries(entries, manifest, spec_dir, package=authoring_dir)
    if not report.ok and not allow_invalid:
        return BuildResult(ok=False, report=report, errors=[e.as_dict() for e in report.errors])

    if out_path is None:
        title = str(manifest.get("title", "bank")).strip() or "bank"
        safe_title = "".join(c if c.isalnum() or c in "-_" else "-" for c in title)[:60]
        out_path = os.path.join(authoring_dir, f"{safe_title}.qbank")

    try:
        write_package(entries, manifest, out_path)
    except PackageError as exc:
        return BuildResult(ok=False, report=report, errors=[exc.as_dict()])

    # 报告给用户的 sha256 也取自磁盘，而不是取自"我们打算写什么"。
    with open(out_path, "rb") as fh:
        package_sha256 = sha256_bytes(fh.read())

    # 打包后**回读落盘的字节**再校验一遍：确保"写出去的"和"校验过的"是同一份。
    # 这里必须走 validate_package(out_path)，不能复用上面的 entries ——
    # 校验内存对象证明不了磁盘上到底是什么（写坏、写不全、被同名旧文件覆盖都测不出来）。
    post = validate_package(out_path, spec_dir)
    return BuildResult(
        ok=post.ok,
        output=out_path,
        package_sha256=package_sha256,
        report=post,
        errors=[e.as_dict() for e in post.errors],
    )


def main(argv: list[str] | None = None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    import argparse

    parser = argparse.ArgumentParser(prog="build_qbank", description="把 authoring 目录打包成 KT-QBF 1.0 .qbank")
    parser.add_argument("authoring_dir", help="包含 manifest.json 的源目录")
    parser.add_argument("-o", "--output", help="输出 .qbank 路径（默认写进源目录）")
    parser.add_argument("--json", action="store_true", help="输出机器可读报告")
    parser.add_argument("--allow-invalid", action="store_true", help="仅用于坏包测试：允许产出无效包")
    args = parser.parse_args(argv)

    # spec_dir 交给 validator 自动探测（工程内 spec/ 或 Skill 的 references/）
    result = build(args.authoring_dir, args.output, None, allow_invalid=args.allow_invalid)

    payload = {
        "ok": result.ok,
        "output": result.output,
        "packageSha256": result.package_sha256,
        "report": result.report.as_dict() if result.report else None,
        "errors": result.errors,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        if result.ok:
            r = result.report
            print(f"OK  {result.output}")
            print(f"    sha256={result.package_sha256}")
            print(f"    digest={r.digest if r else ''}")
            if r:
                print(f"    题目 {r.counts.get('questions', 0)} 题，语言 {len(r.locales)} 个")
        else:
            print("FAILED")
            for e in result.errors[:20]:
                print(f"  [{e['code']}] {e['message']}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
