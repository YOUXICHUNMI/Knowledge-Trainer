"""KT-QBF 1.0 增强资料的答案指纹（`answerHash`）。

为什么需要它：增强资料（语义重组、干扰项）是从题目**当前的题干与答案**派生出来的。
题干或答案改过之后，旧增强就和标准答案对不上了 —— 用户会看到"解析说选 B、标准答案却是 C"。
`answerHash` 就是这条绑定关系的载体：答案一变，指纹就对不上，旧增强自动失效。

为什么由工具算而不是手写：手抄哈希几乎必错（和 `manifest.files` 同理）。
唯一的权威算法在这里，作者用 `validate_qbank.py answer-hash <authoring_dir>` 取值，
校验器用同一个函数复核，两边不可能漂移。

指纹定义（写进规范 §8，改动即等于改格式）：

    payload = {
      "questionId":       <结构层 questionId>,
      "locale":           <文案层 locale>,
      "type":             <题型>,
      "promptMarkdown":   <该 locale 的题干原文，不做归一化>,
      "options":          [[optionId, contentMarkdown], ...] 按结构层 optionIds 顺序；简答为 null,
      "correctOptionIds": 升序数组；简答为 null,
      "answerText":       简答题的 answerText；选择题为 null
    }
    answerHash = sha256(UTF-8(json.dumps(payload, ensure_ascii=False,
                                        sort_keys=True, separators=(",", ":"))))[:16]

要点：按 `optionIds` 顺序取选项（文案层顺序不影响指纹）；正确项先排序
（`["o2","o1"]` 与 `["o1","o2"]` 是同一个答案）；解析（explanation）**不参与**
指纹 —— 改解析不该让增强失效。
"""

from __future__ import annotations

import hashlib
import json

CHOICE_TYPES = ("single_choice", "multiple_choice", "true_false")
HASH_LENGTH = 16


def answer_payload(question: dict, localization: dict) -> dict:
    """把"这道题在这个语言下的答案"压成可哈希的规范结构。"""
    qtype = question.get("type")
    payload: dict = {
        "questionId": question.get("questionId", ""),
        "locale": localization.get("locale", ""),
        "type": qtype,
        "promptMarkdown": localization.get("promptMarkdown", ""),
        "options": None,
        "correctOptionIds": None,
        "answerText": None,
    }
    if qtype in CHOICE_TYPES:
        texts = {
            o.get("optionId"): o.get("contentMarkdown", "")
            for o in (localization.get("options") or [])
            if isinstance(o, dict)
        }
        payload["options"] = [
            [oid, texts.get(oid, "")] for oid in (question.get("optionIds") or [])
        ]
        payload["correctOptionIds"] = sorted(question.get("correctOptionIds") or [])
    else:
        payload["answerText"] = localization.get("answerText", "")
    return payload


def canonical_bytes(question: dict, localization: dict) -> bytes:
    """指纹的规范字节串。要改格式就改这里，并把答案同步进规范 §8。"""
    return json.dumps(
        answer_payload(question, localization),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def compute_answer_hash(question: dict, localization: dict) -> str:
    """返回 16 位小写十六进制指纹（sha256 前 16 位）。"""
    return hashlib.sha256(canonical_bytes(question, localization)).hexdigest()[:HASH_LENGTH]
