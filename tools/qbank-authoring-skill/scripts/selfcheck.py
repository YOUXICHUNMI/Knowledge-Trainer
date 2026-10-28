#!/usr/bin/env python3
"""Skill 自检：确认这份 qbank-authoring Skill 单独拿出去也能用。

检查项：
  1. SKILL.md 存在，YAML frontmatter 可解析，name / description 合规
  2. 十条强制规则都在正文里（不是只写在工程总纲）
  3. 正文里出现的所有相对链接（文件 / 目录）都真实存在
  4. 正文里提到的脚本命令都能跑（--help 返回 0）
  5. examples/ 下每个 authoring 示例都能真的打包 + 校验通过
  6. scripts/tests/run_bad_packages.py 全部 PASS
  7. 校验器能找到 schema（references/ 布局），且故意破坏时确实会拒绝

运行：python scripts/selfcheck.py
退出码 0 = 全部通过。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # qbank-authoring-skill/
SKILL_MD = os.path.join(ROOT, "SKILL.md")

REQUIRED_RULES = [
    ("原始知识", "不得无来源编造"),
    ("题型不武断", "不能"),
    ("ID 稳定", "复用原 ID"),
    ("多语言不冒充审核", "机器翻译"),
    ("增强资料不覆盖标准答案", "reviewStatus: unreviewed"),
    ("必须真实打包", "build_qbank.py"),
    ("导入前摘要", "语言覆盖率"),
    ("安全检查", "<script>"),
    ("无法执行如实报告", "未生成真实包"),
    ("不依赖增强也能交付", "可练习题库"),
]

results: list[tuple[bool, str, str]] = []


def check(ok: bool, name: str, detail: str = "") -> bool:
    results.append((ok, name, detail))
    return ok


def run(args: list[str], cwd: str | None = None) -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable] + args,
        cwd=cwd or ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return proc.returncode, proc.stdout.decode("utf-8", "replace")


# ---------------------------------------------------------------- 1. frontmatter

def parse_frontmatter(text: str) -> dict[str, str] | None:
    if not text.startswith("---\n"):
        return None
    end = text.find("\n---\n", 4)
    if end < 0:
        return None
    block = text[4:end]
    data: dict[str, str] = {}
    for line in block.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)$", line)
        if not m:
            return None
        key, val = m.group(1), m.group(2).strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
            val = val[1:-1]
        data[key] = val
    return data


def check_frontmatter(text: str) -> None:
    fm = parse_frontmatter(text)
    if not check(fm is not None, "SKILL.md frontmatter 可解析"):
        return
    assert fm is not None
    check(fm.get("name") == "qbank-authoring", "frontmatter.name = qbank-authoring", repr(fm.get("name")))
    desc = fm.get("description", "")
    check(len(desc) >= 40, "frontmatter.description 足够具体", f"{len(desc)} 字符")
    for kw in ("KT-QBF", ".qbank", "localization"):
        if not check(kw in desc, f"description 含关键字 {kw}"):
            pass
    rest = text.split("\n---\n", 1)[1]
    body = rest.lstrip("\n")
    check(bool(body) and rest.startswith("\n"), "frontmatter 后接正文", f"正文 {len(body)} 字符")


# ---------------------------------------------------------------- 2. 规则

def check_rules(text: str) -> None:
    body = text.split("\n---\n", 1)[-1]
    for name, needle in REQUIRED_RULES:
        check(needle in body, f"规则存在：{name}")


# ---------------------------------------------------------------- 3. 相对链接

LINK_RE = re.compile(r"`((?:references|scripts|examples|docs)/[A-Za-z0-9_./\-\u4e00-\u9fff*]+)`")
CMD_RE = re.compile(r"python\s+(scripts/[A-Za-z0-9_./-]+\.py)")


def check_links(text: str) -> None:
    targets = sorted(set(LINK_RE.findall(text)))
    missing = []
    for t in targets:
        if "*" in t:
            continue
        if not os.path.exists(os.path.join(ROOT, *t.split("/"))):
            missing.append(t)
    check(not missing, f"正文引用的 {len(targets)} 个相对路径都存在", "缺失: " + ", ".join(missing))


def check_commands(text: str) -> None:
    cmds = sorted(set(CMD_RE.findall(text)))
    for c in cmds:
        path = os.path.join(ROOT, *c.split("/"))
        if not check(os.path.isfile(path), f"命令引用的脚本存在：{c}"):
            continue
        code, out = run([path, "--help"])
        check(code == 0, f"{c} --help 可运行", out.strip().splitlines()[0] if out.strip() else "")


# ---------------------------------------------------------------- 5. 示例可打包

def check_examples() -> None:
    ex_root = os.path.join(ROOT, "examples")
    if not check(os.path.isdir(ex_root), "examples/ 存在"):
        return
    dirs = sorted(
        d for d in os.listdir(ex_root) if os.path.isfile(os.path.join(ex_root, d, "manifest.json"))
    )
    check(bool(dirs), "examples/ 至少有一个 authoring 示例", f"{len(dirs)} 个")
    tmp = tempfile.mkdtemp(prefix="ktqbf-selfcheck-")
    try:
        for d in dirs:
            src = os.path.join(ex_root, d)
            out = os.path.join(tmp, f"{d}.qbank")
            build = os.path.join(ROOT, "scripts", "build_qbank.py")
            code, log = run([build, src, "-o", out, "--json"])
            payload = {}
            try:
                payload = json.loads(log)
            except json.JSONDecodeError:
                pass
            if not check(code == 0 and payload.get("ok"), f"示例可打包：{d}", log.strip()[-400:]):
                continue
            vcode, vlog = run([os.path.join(ROOT, "scripts", "validate_qbank.py"), "validate", out, "--json"])
            vpayload = {}
            try:
                vpayload = json.loads(vlog)
            except json.JSONDecodeError:
                pass
            check(vcode == 0 and vpayload.get("ok"), f"示例包可通过校验：{d}", vlog.strip()[-400:])
            sha = payload.get("packageSha256", "")
            check(len(sha) == 64, f"示例产出 sha256：{d}", sha)

            # 可复现：再打一次，字节必须一致
            out2 = out + ".2"
            run([build, src, "-o", out2, "--json"])
            same = os.path.isfile(out2) and open(out, "rb").read() == open(out2, "rb").read()
            check(same, f"打包可复现（两次字节相同）：{d}")

            # 仓库里随附的真包必须与现场打包结果一致（防陈旧产物）
            shipped = os.path.join(ex_root, "_packages", f"{d.replace('-authoring', '')}.qbank")
            if os.path.isfile(shipped):
                identical = open(shipped, "rb").read() == open(out, "rb").read()
                check(identical, f"随附包与现场打包一致：{os.path.basename(shipped)}")
                rep = shipped.replace(".qbank", ".report.txt")
                check(os.path.isfile(rep), f"随附校验报告存在：{os.path.basename(rep)}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 6. 坏包测试

def check_bad_packages() -> None:
    script = os.path.join(ROOT, "scripts", "tests", "run_bad_packages.py")
    if not check(os.path.isfile(script), "坏包测试脚本存在"):
        return
    code, log = run([script])
    last = [l for l in log.strip().splitlines() if "passed" in l]
    check(code == 0, "坏包测试全部 PASS", last[-1] if last else log.strip()[-300:])
    m = re.search(r"(\d+) passed, (\d+) failed, (\d+) total", log)
    if m and int(m.group(3)) < 30:
        check(False, "坏包用例数量不少于 30", f"实际 {m.group(3)}")


# ---------------------------------------------------------------- 7. 校验器确实会拒绝

def check_fail_closed() -> None:
    sys.path.insert(0, os.path.join(ROOT, "scripts"))
    try:
        from ktqbf.validator import resolve_spec_dir  # noqa: PLC0415

        spec = resolve_spec_dir()
        check(os.path.isdir(spec), "校验器能定位 Schema 目录", spec)
    except Exception as exc:  # noqa: BLE001
        check(False, "校验器能定位 Schema 目录", f"{type(exc).__name__}: {exc}")
        return

    # 关键：改动一个字节的包必须被拒
    src = os.path.join(ROOT, "examples", "minimal-authoring")
    if not os.path.isdir(src):
        return
    tmp = tempfile.mkdtemp(prefix="ktqbf-failclosed-")
    try:
        out = os.path.join(tmp, "a.qbank")
        run([os.path.join(ROOT, "scripts", "build_qbank.py"), src, "-o", out, "--json"])
        if not os.path.isfile(out):
            check(False, "fail-closed 前置：能产出包")
            return
        raw = bytearray(open(out, "rb").read())
        # 条目是 deflate 压缩的，包里搜不到明文；改为篡改中央目录里的解压后大小字段，
        # 让"清单哈希"与"实际解出字节"对不上（这正是最常见的坏包形态）。
        cen = raw.find(b"PK\x01\x02")
        if cen < 0:
            check(False, "fail-closed 前置：能找到中央目录")
            return
        target = None
        cursor = cen
        while cursor >= 0:
            name_len = int.from_bytes(raw[cursor + 28 : cursor + 30], "little")
            name = bytes(raw[cursor + 46 : cursor + 46 + name_len]).decode("utf-8", "replace")
            if name.startswith("questions/"):
                target = cursor
                break
            cursor = raw.find(b"PK\x01\x02", cursor + 4)
        if target is None:
            check(False, "fail-closed 前置：能在中央目录里找到 questions 条目")
            return
        # uncompressed size 位于中央目录 +24..28
        old = raw[target + 24 : target + 28]
        raw[target + 24 : target + 28] = (int.from_bytes(old, "little") + 7).to_bytes(4, "little")
        broken = os.path.join(tmp, "b.qbank")
        open(broken, "wb").write(bytes(raw))
        code, log = run([os.path.join(ROOT, "scripts", "validate_qbank.py"), "validate", broken, "--json"])
        data = {}
        try:
            data = json.loads(log)
        except json.JSONDecodeError:
            pass
        codes = {e.get("code") for e in data.get("errors", [])}
        check(code != 0 and not data.get("ok"), "被篡改的包被拒绝", ",".join(sorted(c for c in codes if c)))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 8. 非 ASCII 条目名

def check_unicode_paths() -> None:
    """回归：包里有中文文件名的媒体资源时，打包与校验都必须正常出报告。

    这里守的是一个真实崩过的点 —— 本地文件头比对曾对 UTF-8 标志位的条目名
    `encode("cp437")`，ASCII 名字恰好不报错，中文名字直接抛 UnicodeEncodeError。
    所以这条用例不能省：它才是当初让 39 个坏包用例全绿却依然会崩的那个盲区。
    """
    src = os.path.join(ROOT, "examples", "minimal-authoring")
    png = os.path.join(ROOT, "examples", "advanced-authoring", "media", "img", "tcp-handshake.png")
    if not (os.path.isdir(src) and os.path.isfile(png)):
        return
    tmp = tempfile.mkdtemp(prefix="ktqbf-unicode-")
    try:
        work = os.path.join(tmp, "bank")
        shutil.copytree(src, work)
        media_dir = os.path.join(work, "media", "img")
        os.makedirs(media_dir, exist_ok=True)
        cn_png = os.path.join(media_dir, "中文截图.png")
        shutil.copyfile(png, cn_png)
        data = open(cn_png, "rb").read()
        rec = {
            "assetId": "zh-pic",
            "path": "media/img/中文截图.png",
            "mimeType": "image/png",
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "alt": "中文文件名的图片",
        }
        with open(os.path.join(work, "media", "index.ndjson"), "w", encoding="utf-8") as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")

        loc = os.path.join(work, "locales", "zh-Hans", "questions.ndjson")
        patched = []
        for line in open(loc, encoding="utf-8").read().splitlines():
            if not line.strip():
                continue
            obj = json.loads(line)
            if obj.get("questionId") == "q001":
                obj["assets"] = ["zh-pic"]
            patched.append(json.dumps(obj, ensure_ascii=False))
        with open(loc, "w", encoding="utf-8") as fh:
            fh.write("\n".join(patched) + "\n")

        pkg = os.path.join(tmp, "unicode.qbank")
        bcode, blog = run([os.path.join(ROOT, "scripts", "build_qbank.py"), work, "-o", pkg, "--json"])
        vcode, vlog = run([os.path.join(ROOT, "scripts", "validate_qbank.py"), "validate", pkg, "--json"])
        payload = {}
        try:
            payload = json.loads(vlog)
        except json.JSONDecodeError:
            pass
        detail = vlog.strip()[-300:] if (bcode or vcode) else "中文图片名 → 打包 + 校验均 PASS"
        check(
            bcode == 0 and vcode == 0 and payload.get("ok"),
            "非 ASCII 条目名（中文图片名）可正常打包与校验",
            detail.replace("Traceback", "TRACEBACK!") if detail else "",
        )
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 9. answerHash

def check_answer_hash() -> None:
    """示例里的增强资料必须带着**算得出来且不过期**的 answerHash。"""
    src = os.path.join(ROOT, "examples", "advanced-authoring")
    if not os.path.isdir(src):
        return
    code, log = run([os.path.join(ROOT, "scripts", "validate_qbank.py"), "answer-hash", src, "--json"])
    payload = {}
    try:
        payload = json.loads(log)
    except json.JSONDecodeError:
        pass
    records = payload.get("records") or []
    bad = [r for r in records if r.get("status") != "OK"]
    if bad:
        detail = f"{len(records)} 条，异常 {len(bad)} 条：" + ", ".join(
            f"{r.get('questionId')}={r.get('status')}" for r in bad
        )
    else:
        detail = f"{len(records)} 条全部 OK"
    check(code == 0 and bool(records) and not bad, "示例的增强资料 answerHash 可算出且未过期", detail)


# ---------------------------------------------------------------- main

def main() -> int:
    if not os.path.isfile(SKILL_MD):
        print(f"FAILED  找不到 {SKILL_MD}")
        return 1
    text = open(SKILL_MD, encoding="utf-8").read()

    check_frontmatter(text)
    check_rules(text)
    check_links(text)
    check_commands(text)
    check_examples()
    check_bad_packages()
    check_fail_closed()
    check_unicode_paths()
    check_answer_hash()

    width = max(len(n) for _, n, _ in results)
    for ok, name, detail in results:
        mark = "PASS" if ok else "FAIL"
        one_line = " ".join(detail.split())
        print(f"{mark}  {name.ljust(width)}  {one_line}")
    passed = sum(1 for ok, _, _ in results if ok)
    failed = len(results) - passed
    print(f"\n{passed} passed, {failed} failed, {len(results)} total")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
