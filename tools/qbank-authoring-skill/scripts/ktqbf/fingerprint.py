"""题目内容指纹：与 Kotlin 端 `Fingerprint` / `Question.contentFingerprint()` 逐字节一致。

为什么要在 Python 里复刻一遍：

`.qbpatch` 的 `baseFingerprint` 是"这份补丁基于哪一版题库"的锚点。应用端会拿本地题库
重新算一遍再比对 —— 两边算法只要差一个字节，所有补丁在用户那儿都会变成"版本不符"，
而作者这边完全看不出问题。

所以这个文件只放算法，别的都不放，并配一条 golden 测试（`tests/test_fingerprint.py`）：
golden 值由 Kotlin 端对同一个示例包算出，任何一侧改动都会立刻被它抓住。
"""

from __future__ import annotations

import hashlib

_MASK = 0xFFFFFFFFFFFFFFFF
_FNV_OFFSET = 0xCBF29CE484222325
_FNV_PRIME = 0x100000001B3


def fnv1a64(text: str) -> str:
    """Kotlin `Fingerprint.of`：FNV-1a 64 位，输入按 UTF-8 编码。"""
    h = _FNV_OFFSET
    for byte in text.encode("utf-8"):
        h ^= byte
        h = (h * _FNV_PRIME) & _MASK
    return format(h, "016x")


def of_questions(parts) -> str:
    """Kotlin `Fingerprint.ofQuestions`：题目指纹集合的指纹，与语言和顺序无关。

    做法是**逐项相加**（模 2^64），不是"排序后拼接再哈希"。两者都是多重集指纹，
    区别在代价：相加可以增量更新（加一道题 = 旧值 + 新题指纹，与库多大无关），
    排序拼接必须把整库重算一遍 —— 十万题的库加一道题要 200ms，而新增内容只有 512 字节。

    它只是查重与补丁定位的锚点，不是安全哈希；抗构造碰撞因此比整串哈希弱一些。
    """
    total = 0
    for part in parts:
        total = (total + int(fnv1a64(part), 16)) & _MASK
    return format(total, "016x")


def question_fingerprint(q: dict) -> str:
    """Kotlin `Question.contentFingerprint()`。

    顺序是有意义的，改动顺序等于让所有历史补丁失效：

        type \\u0001 promptMarkdown \\u0001 correct(逗号排序) \\u0001 answerText ?: ""
             \\u0001 optionId=contentMarkdown   （按 optionId 排序，每个选项一段）

    注意它**不含** explanationMarkdown、tags、grading —— 讲解写错不算"题目变了"，
    但答案、选项、题干变了就算。这个取舍与 Kotlin 端一致，不能单方面改。
    """
    parts = [
        str(q.get("type") or ""),
        str(q.get("promptMarkdown") or ""),
        ",".join(sorted(q.get("correctOptionIds") or [])),
        str(q.get("answerText") or ""),
    ]
    for option in sorted(q.get("options") or [], key=lambda o: o.get("optionId") or ""):
        parts.append("%s=%s" % (option.get("optionId") or "", option.get("contentMarkdown") or ""))
    return fnv1a64("\u0001".join(parts))


def bank_fingerprint(questions) -> str:
    return of_questions([question_fingerprint(q) for q in questions])


def content_sha(text: str) -> str:
    """正文摘要：只用于补丁里的 `expect`（可选的人工可读锚点），不参与判题。"""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
