#!/usr/bin/env python3
"""一条命令跑完 Python 工具链的全部回归，本地与 CI 共用同一份清单。

为什么要有这个脚本：坏包回归、扩展包回归、补丁回归、制包 Skill 自检
分散在四个目录里，各自单独跑很容易漏掉一个 —— 而"漏跑"和"通过"在
人眼里长得一模一样。清单只写一次，本地和 CI 都调它，就不会出现
"CI 绿但本地少跑了一套"的情况。

Kotlin 侧（./gradlew :shared:jvmTest）不在本脚本里：它需要 JDK 与
Android SDK，由 CI 的 Gradle 步骤负责。

用法：
    python tools/verify/run_all.py            # 全跑，失败即非零退出
    python tools/verify/run_all.py --quiet    # 只显示每条结论与失败详情
    python tools/verify/run_all.py --list     # 只列清单，不执行

零第三方依赖，Python 3.9+。
"""

from __future__ import annotations

import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# (名称, 相对仓库根的命令参数)。顺序固定：先快后慢。
SUITES: list[tuple[str, list[str]]] = [
    ("工程内 · 坏包回归", ["tools/qbank/tests/run_bad_packages.py"]),
    ("工程内 · .qbext 回归", ["tools/qbank/tests/run_bad_qbext.py"]),
    ("工程内 · .qbpatch 回归", ["tools/qbank/tests/run_bad_qbpatch.py"]),
    ("制包 Skill · 坏包回归", ["tools/qbank-authoring-skill/scripts/tests/run_bad_packages.py"]),
    ("制包 Skill · 自检", ["tools/qbank-authoring-skill/scripts/selfcheck.py"]),
    ("可复现构建 · 同一源两次打包字节一致", ["tools/verify/check_reproducible.py"]),
]

# 需要"真实包能被校验器接受"的样本：仓库自带的成品包，含一份 1143 题的真实题库。
VALIDATE_GLOBS: list[str] = [
    "banks",
    "tools/qbank/examples",
    "tools/qbank-authoring-skill/examples/_packages",
]

VALIDATOR = "tools/qbank/validate_qbank.py"


def _packages() -> list[str]:
    found: list[str] = []
    for rel in VALIDATE_GLOBS:
        base = os.path.join(ROOT, rel)
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in sorted(filenames):
                if name.endswith(".qbank"):
                    found.append(os.path.relpath(os.path.join(dirpath, name), ROOT).replace(os.sep, "/"))
    return sorted(found)


def _run(cmd: list[str], quiet: bool) -> tuple[int, float, str]:
    started = time.time()
    proc = subprocess.run(
        cmd,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    elapsed = time.time() - started
    output = proc.stdout or ""
    if not quiet:
        sys.stdout.write(output)
        sys.stdout.flush()
    return proc.returncode, elapsed, output


def _tail(output: str, lines: int = 12) -> str:
    rows = [r for r in output.strip().splitlines() if r.strip()]
    return "\n".join(rows[-lines:] if len(rows) > lines else rows)


def main(argv: list[str]) -> int:
    quiet = "--quiet" in argv
    packages = _packages()

    if "--list" in argv:
        for name, cmd in SUITES:
            print(f"[套件] {name}: {' '.join(cmd)}")
        for path in packages:
            print(f"[校验] {path}")
        return 0

    failed: list[str] = []
    total_started = time.time()

    print(f"仓库根：{ROOT}")
    print(f"Python：{sys.version.split()[0]}  ({sys.executable})")
    print()

    for name, cmd in SUITES:
        full = [sys.executable, os.path.join(ROOT, cmd[0]).replace(os.sep, "/"), *cmd[1:]]
        code, elapsed, output = _run(full, quiet)
        mark = "PASS" if code == 0 else "FAIL"
        print(f"[{mark}] {name}  ({elapsed:.1f}s)")
        if code != 0:
            failed.append(name)
            print(_tail(output, 20))
            print()

    for path in packages:
        target = os.path.join(ROOT, path)
        code, elapsed, output = _run(
            [sys.executable, os.path.join(ROOT, VALIDATOR), "validate", target], quiet
        )
        mark = "PASS" if code == 0 else "FAIL"
        print(f"[{mark}] 校验 {path}  ({elapsed:.1f}s)")
        if code != 0:
            failed.append(f"校验 {path}")
            print(_tail(output, 20))
            print()

    elapsed_all = time.time() - total_started
    print()
    if failed:
        print(f"合计 {len(SUITES) + len(packages)} 项，失败 {len(failed)} 项（{elapsed_all:.1f}s）：")
        for name in failed:
            print(f"  • {name}")
        return 1

    print(f"合计 {len(SUITES) + len(packages)} 项全部通过（{elapsed_all:.1f}s）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
