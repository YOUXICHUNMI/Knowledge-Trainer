# banks/ — 真实题库包

## ham-b-2025（业余无线电 B 类操作证书考试题库，2025 版）

| 项 | 值 |
|---|---|
| 包文件 | `ham-b-2025.qbank`（KT-QBF 1.0，可直接「导入题库 → 选择 .qbank 文件」） |
| 题量 | 1143 题（单选 947 / 多选 196，官方编号 MCn 前缀即正确选项数） |
| 附图 | 24 张官方题图（`总题库附图标记.pdf` 按标签-图位置配对抽取，存于 authoring/media/） |
| 解释 | 每题附 AI 生成的应试解释（`explanationMarkdown`）；`MC1-1007` 等个别题目解释中标注「官方答案可能有误」 |
| 来源 | CRAC《业余无线电操作证书考试》B 类题库 2025 年版 PDF（整理者 BA1GG） |
| 校验 | 打包器 + 独立校验器双通过，0 错误 0 警告 |
| 导入测试 | `shared/src/jvmTest/.../HamBank2025ImportTest.kt`（解析 / 题型分布 / 入库 + 重复导入识别） |

`ham-b-2025-authoring/` 是打包源（questions 结构层 + locales/zh-Hans 文案层 + media），改动后用
`python tools/qbank/build_qbank.py banks/ham-b-2025-authoring -o banks/ham-b-2025.qbank` 重新打包。

已知取舍（如实记录）：

- `MC1-0014`、`MC1-0016` 源 PDF 标注笔误（MC1 却有两个正确选项），按实际答案数归入多选，题号保留原样。
- 题库为官方公开题库的转录，题干与选项文字未改动；解释为 AI 生成，判分以官方标准答案为准。
