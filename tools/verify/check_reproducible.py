#!/usr/bin/env python3
"""断言"同一份源目录打包两次，字节完全一致"。

这条性质是 KT-QBF 可复现构建的底线：ZIP 中央目录里若混入当前时间、
文件系统顺序或本机路径，同一个源目录在两台机器上就会产出不同的 sha256，
内容摘要（contentDigest）与补丁基线随之失效。以前只在文档里声称，
这里把它变成可执行的断言。

只比较"同一台机器、同一份源、连续两次"的字节 —— 跨平台（zlib 版本、
换行符）不在断言范围内，那是另一件事，不该由这条用例假装覆盖。

用法：python tools/verify/check_reproducible.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

BUILDER = os.path.join(ROOT, "tools/qbank/build_qbank.py")

# 两个示例各有侧重：minimal 只有题目与文案，advanced 带媒体与增强资料，
# 后者才会踩到"图片字节是否被重编码"这类不确定性。
SOURCES = [
    "tools/qbank/examples/minimal-authoring",
    "tools/qbank/examples/advanced-authoring",
]


def build(src: str, out: str) -> None:
    proc = subprocess.run(
        [sys.executable, BUILDER, os.path.join(ROOT, src).replace(os.sep, "/"), "-o", out],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise SystemExit(f"打包失败：{src}\n{proc.stdout}")


def digest(path: str) -> str:
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def main() -> int:
    work = tempfile.mkdtemp(prefix="kt-repro-")
    failures: list[str] = []
    try:
        for src in SOURCES:
            first = os.path.join(work, "a.qbank")
            second = os.path.join(work, "b.qbank")
            build(src, first)
            build(src, second)
            a, b = digest(first), digest(second)
            if a == b:
                print(f"[PASS] {src}  两次打包字节一致  {a[:16]}…")
            else:
                failures.append(src)
                print(f"[FAIL] {src}  两次打包字节不同：{a} vs {b}")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    if failures:
        print(f"\n可复现构建失败 {len(failures)} 项：{', '.join(failures)}")
        return 1
    print(f"\n可复现构建 {len(SOURCES)}/{len(SOURCES)} 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
