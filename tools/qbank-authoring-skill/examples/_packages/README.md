# 示例包与验证报告

这里的 `.qbank` 都是**真包**（ZIP + manifest + 哈希），由 `scripts/build_qbank.py` 从相邻的
authoring 源目录打包出来，再用 `scripts/validate_qbank.py` 复验。可以直接拖进 Knowledge Trainer 导入。

| 包 | 源目录 | 题数 | 语言 | 媒体 | 增强 | sha256 | 规范化 digest |
|---|---|---|---|---|---|---|---|
| `minimal.qbank` | `../minimal-authoring/` | 3（单选 1 / 多选 1 / 简答 1） | zh-Hans 3/3 | 0 | 无 | `47fd7bbc…0cbab842` | `7c865487…b8e64290a` |
| `advanced.qbank` | `../advanced-authoring/` | 6（单选 2 / 多选 2 / 判断 1 / 简答 1） | zh-Hans 6/6、en 2/6 | 1 PNG | reconstruction 1 + distractor 1 | `ab8d0225…561bb380` | `34b6708b…44ac6424` |

`minimal.report.txt` / `advanced.report.txt` 是校验器的原始输出（人读格式）。
`advanced.qbank` 的 `LOCALE_PARTIAL` 是**预期警告**（en 只翻了一部分），不是错误。

## 复现

```bash
python scripts/build_qbank.py examples/minimal-authoring  -o /tmp/minimal.qbank
python scripts/validate_qbank.py validate /tmp/minimal.qbank
```

同一份源目录两次打包，字节必须完全一致（固定条目顺序、固定 1980-01-01 时间戳、权限 0o644、
`compresslevel=9`）。上面的 sha256 就是这么得来的，可用于核对你的环境是否一致。

## 两个示例分别覆盖什么

**minimal-authoring** —— 最小可用闭环：三种原生题型、单个分片、单语言、无媒体、无增强。
用来验证"作者什么都不配也能得到可练习题"。

**advanced-authoring** —— 综合压力：两个分片（`part-0001` / `part-0002` 按文件名排序拼接）、
zh-Hans 全量 6 题 + en 部分 2 题（验证部分翻译的覆盖率报告）、行内 `$...$` 与块级 `$$...$$`
公式、真实 PNG（`media/img/tcp-handshake.png`，325 字节，手工编码）、
一条 `reviewed` 的语义重组 + 一条 `unreviewed` 的干扰项（验证"AI 生成必须标未审核"），
两条都带**算得出来且不过期**的 `answerHash`（用 `validate_qbank.py answer-hash` 取，别手抄）。
