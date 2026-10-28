#!/usr/bin/env python3
"""生成 Kotlin 侧的 inflate 测试向量。

纯 Kotlin 的 DEFLATE 解压器必须和 zlib 逐字节一致。这里用 Python 的 zlib
（就是 CPython 绑定的那套 zlib）压出一组 raw deflate 流，覆盖三类块：

  BTYPE=0 stored / BTYPE=1 fixed Huffman / BTYPE=2 dynamic Huffman

并覆盖几个容易写错的边界：空输入、不可压缩的随机字节、长距离重复、
中文 UTF-8（多字节字符不能被拆断）、多块流。

用法：
    python tools/qbank/tests/gen_inflate_vectors.py            # 打印到 stdout
    python tools/qbank/tests/gen_inflate_vectors.py -o <file>   # 写入 Kotlin 源文件
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import random
import zlib


def raw_deflate(data: bytes, level: int) -> bytes:
    c = zlib.compressobj(level, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def btype(stream: bytes) -> int:
    return (stream[0] >> 1) & 0x3 if stream else -1


def build_cases() -> list[tuple[str, bytes, int]]:
    rnd = random.Random(7)
    cases: list[tuple[str, bytes, int]] = [
        ("empty", b"", 9),
        ("stored_short", b"hello hello hello", 0),
        ("fixed_huffman", (b"abcabcabc" * 30)[:270], 1),
        ("chinese_utf8", ("题库格式规范：题目身份与显示文案分离。" * 12).encode("utf-8"), 9),
        ("random_incompressible", bytes(rnd.randrange(256) for _ in range(256)), 9),
        ("long_distance_repeat", b"Knowledge Trainer KT-QBF 1.0\n" * 160, 9),
        # 大文本 level 9 会被 zlib 切成多个块，用来验证循环读块而不是只读第一块
        ("multi_block", ("".join(f"第{i}条：题目身份 questionId 必须稳定。\n" for i in range(600))).encode("utf-8"), 9),
    ]
    return cases


def render(cases: list[tuple[str, bytes, int]]) -> str:
    lines = [
        "// 由 tools/qbank/tests/gen_inflate_vectors.py 生成，请勿手改。",
        f"// 生成时间：{datetime.date.today().isoformat()}  来源：CPython zlib（raw deflate, wbits=-15）",
        "package morefish.knowledge.trainer.infrastructure.archive",
        "",
        "/**",
        " * raw DEFLATE 测试向量：`deflate` 是 zlib 压出来的字节流，",
        " * `plainSha256` 是解压后应得的原文哈希，`plainSize` 是原文长度。",
        " *",
        " * 只存哈希不存原文，避免测试源码被大段文本淹没；原文由解压结果反推验证。",
        " */",
        "internal object InflateVectors {",
        "    data class Vector(",
        "        val name: String,",
        "        val deflateBase64: String,",
        "        val plainSize: Int,",
        "        val plainSha256: String,",
        "        val blockType: Int,",
        "    )",
        "",
        "    val all: List<Vector> = listOf(",
    ]
    for name, data, level in cases:
        stream = raw_deflate(data, level)
        b64 = base64.b64encode(stream).decode()
        digest = hashlib.sha256(data).hexdigest()
        lines.append("        Vector(")
        lines.append(f'            name = "{name}",')
        # 长 base64 折行，保持每行不超过 100 字符
        if len(b64) <= 88:
            lines.append(f'            deflateBase64 = "{b64}",')
        else:
            lines.append('            deflateBase64 =')
            for i in range(0, len(b64), 88):
                chunk = b64[i : i + 88]
                suffix = "," if i + 88 >= len(b64) else " +"
                lines.append(f'                "{chunk}"{suffix}')
        lines.append(f"            plainSize = {len(data)},")
        lines.append(f'            plainSha256 = "{digest}",')
        lines.append(f"            blockType = {btype(stream)},")
        lines.append("        ),")
    lines.append("    )")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 inflate 测试向量")
    parser.add_argument("-o", "--output", help="写入的 Kotlin 文件路径")
    args = parser.parse_args()

    cases = build_cases()
    text = render(cases)
    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        seen = sorted({btype(raw_deflate(d, l)) for _, d, l in cases})
        print(f"OK  {args.output}  ({len(cases)} 个向量，块类型 {seen})")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
