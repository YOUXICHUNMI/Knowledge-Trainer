# 示例题库

每个 `*-authoring/` 是源目录，同名 `.qbank` 是它打出来的真包（`python tools/qbank-authoring-skill/scripts/build_qbank.py <目录> -o <包>`）。
两者都进仓库：源目录给人改，包用来对照"改完应该长什么样"。

| 样例 | 内容 | 用来演示什么 |
|---|---|---|
| `minimal-authoring/` · `starter.qbank` | 3 题 | 最小可用：单选 / 多选 / 简答 |
| `advanced-authoring/` · `advanced.qbank` | 6 题 | 多分片、zh-Hans + en 部分翻译、LaTeX、真实 PNG、增强资料 |
| `p4-authoring/` · `p4-extensions.qbank` | — | 1.1 草案扩展：语义重组（ordering）与关键词判分的默写题 |
| `zhuanshengben-authoring/` · `zhuanshengben.qbank` | 43 题 | 多科目实战：12 个知识点、4 种题型、解析写成知识点卡片（见 `zhuanshengben-outline.md`） |
| `qbext-authoring/` · `relations.qbext` | — | 扩展包（`.qbext`）：跨库关系与讲解 |
| `patch-authoring/` · `starter-v2.qbpatch` | — | 版本补丁（`.qbpatch`）：基于第几版、锚点逐条核对 |

`tools/verify/run_all.py` 会遍历本目录下所有 `.qbank` 做一次校验 —— 新增包自动纳入，
不需要改脚本。改完源目录记得重新打包，并跑一次 `python tools/verify/run_all.py`。
