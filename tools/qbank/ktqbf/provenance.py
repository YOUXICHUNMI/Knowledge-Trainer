"""出处声明（§4.1 / §5.1）与整包内容摘要（§4.2）。

两件事放在一个文件里，因为它们是同一个问题的两面：

- `sources` / `origin` 回答「这道题哪来的」。**这是声明，不是证明** ——
  软件无法核对"某书第 42 页真有这道题"，只能核对"包内引用自洽"。
- `contentDigest` 回答「这个包还是作者发出来的那一份吗」。这个是能算的：
  它只依赖逻辑内容（路径 + 内容哈希），与 ZIP 时间戳、压缩级别无关，
  所以"同一个人用不同工具重打的同一个包"摘要相同，"内容被改过一个字节"摘要就不同。

摘要**不覆盖** `sources` / `tags` / `description`：那些是可修改的说明性字段，
把它们算进去会让"改一句说明就换一个摘要"，而摘要要回答的是"这是不是同一份题"。
它**覆盖** author 与 license，因为那两个是作者对读者的承诺，不该悄悄改。
"""

from __future__ import annotations

from .package import sha256_bytes

DIGEST_VERSION = "kt-qbf-digest-1"

ORIGINS = ("authored", "adapted", "imported", "ai-assisted")
KINDS = ("book", "paper", "web", "course", "original", "other")


def _esc(value: object) -> str:
    """把值压成单行。反斜杠先转义，否则后续替换会把它自己产出的 `\\n` 再转一遍。"""
    text = "" if value is None else str(value)
    return (
        text.replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )


def digest_input(manifest: dict, entries: dict[str, bytes]) -> bytes:
    """摘要的规范化输入。**这个格式一旦发布就不能改** —— 改了等于让所有旧包的摘要失效。"""
    author = manifest.get("author")
    author_name = author.get("displayName") if isinstance(author, dict) else author
    locales = manifest.get("supportedLocales") or []
    lines = [
        DIGEST_VERSION,
        f"format={_esc(manifest.get('format'))}",
        f"schemaVersion={_esc(manifest.get('schemaVersion'))}",
        f"bankId={_esc(manifest.get('bankId'))}",
        f"contentRevision={_esc(manifest.get('contentRevision'))}",
        f"sourceLocale={_esc(manifest.get('sourceLocale'))}",
        f"supportedLocales={','.join(_esc(loc) for loc in locales)}",
        f"title={_esc(manifest.get('title'))}",
        f"author={_esc(author_name)}",
        f"license={_esc(manifest.get('license'))}",
    ]
    body = "\n".join(lines) + "\n"
    for path in sorted(entries):
        body += f"{path} {sha256_bytes(entries[path])}\n"
    return body.encode("utf-8")


def content_digest(manifest: dict, entries: dict[str, bytes]) -> str:
    return sha256_bytes(digest_input(manifest, entries))


def check(manifest: dict, questions: dict[str, dict], q_line: dict[str, tuple[str, int]], entries: dict[str, bytes], report) -> None:
    """出处声明的一致性 + 摘要核对。只做"包内自洽"，不做"内容属实"。"""
    M = "manifest.json"

    sources = manifest.get("sources") or []
    by_id: dict[str, dict] = {}
    for i, src in enumerate(sources):
        sid = src.get("sourceId")
        if sid in by_id:
            report.error("SOURCE_DUPLICATE", f"sources 中 sourceId {sid} 重复（第 {i + 1} 条）", M)
            continue
        by_id[sid] = src

    for qid, q in questions.items():
        part, lineno = q_line.get(qid, ("questions/", 0))
        sid = q.get("sourceId")
        origin = q.get("origin")
        if sid and sid not in by_id:
            report.error(
                "SOURCE_UNKNOWN",
                f"{qid} 引用了 sourceId {sid}，但 manifest.sources 里没有这一条"
                + ("" if sources else "（整份 manifest 都没有 sources）"),
                part,
                lineno,
            )
        if sid and not q.get("sourceLocator"):
            report.warn(
                "SOURCE_LOCATOR_MISSING",
                f"{qid} 标了来源 {sid} 但没写 sourceLocator（章节 / 页码 / 题号），读者没法核对",
                part,
                lineno,
            )
        if origin in ("adapted", "imported") and not sid:
            report.warn(
                "ORIGIN_WITHOUT_SOURCE",
                f"{qid} 声称 origin={origin}，但没写 sourceId —— 「抄来的」却不说是从哪抄的",
                part,
                lineno,
            )

    declared = manifest.get("contentDigest")
    if not declared:
        report.warn(
            "CONTENT_DIGEST_MISSING",
            "manifest 没有 contentDigest。老包仍能导入，但应用端无法判断"
            "「两份包是不是同一份内容」；用 build_qbank.py 重新打包会自动补上",
            M,
        )
        return
    actual = content_digest(manifest, entries)
    if declared != actual:
        report.error(
            "CONTENT_DIGEST_MISMATCH",
            f"contentDigest 与实际内容不符：清单 {declared[:12]}…，重算 {actual[:12]}…。"
            "手工改过 questions/ 或 locales/ 后需要用 build_qbank.py 重新打包",
            M,
        )


def summarize(questions: dict[str, dict], sources: list[dict]) -> dict:
    """给报告与界面用的统计。刻意把"未声明"单独计数 —— 它不等于"原创"。"""
    total = len(questions)
    by_origin = {o: 0 for o in ORIGINS}
    undeclared = 0
    with_source = 0
    for q in questions.values():
        o = q.get("origin")
        if o in by_origin:
            by_origin[o] += 1
        else:
            undeclared += 1
        if q.get("sourceId"):
            with_source += 1
    return {
        "total": total,
        "sources": len(sources),
        "withSource": with_source,
        "originUndeclared": undeclared,
        "byOrigin": by_origin,
    }
