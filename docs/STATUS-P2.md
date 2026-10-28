# Knowledge Trainer — 开发状态（P0 ~ P2）

> 更新时间：2026-10-19
> 本文件只记录**真实完成**的范围与可复现证据，未实现的能力一律标注为未实现。

## 一句话结论

P2 完成：在 P1 做题闭环之上，新增**练习范围与混合抽题、会话暂停/恢复（含草稿）、收藏、学习日历与历史筛选**。
桌面端已用种子数据实测：首页恢复卡片 → 点「继续练习」→ 正确停在断点题目（截图验证）。
66 个自动化测试全部通过；主代码约 5065 行，测试约 1554 行。

## P2 新增能力

| 能力 | 状态 | 说明 / 证据 |
|---|---|---|
| 练习范围（全部 / 未练 / 错题 / 收藏） | ✅ | `PracticeScope` + `PlanPracticeUseCase`，范围计数在设置页实时显示 |
| 按题型 / 标签筛选 | ✅ | `PracticePlan.types / tags`；题型可用数显示在设置页 |
| 混合抽题（按题型配比分层抽样） | ✅ | `QuestionSelector.mixed()`，`QuestionSelectorTest` 断言各题型配比 |
| 题目数量限制 + 选项固定顺序开关 | ✅ | `PracticePlan.questionCount / shuffleOptions` |
| 会话暂停 / 恢复 | ✅ | `SessionLifecycle`；首页「继续上次练习」卡片（已答 n/N · 停在第 k 题 · 相对时间） |
| 恢复时对齐游标 | ✅ | `resumeNormalized()`：游标对齐到第一个未答题，已答不重复、不跳题 |
| 草稿（未提交的选择随会话保存） | ✅ | `PracticeDraft`，只随会话文件存，不写历史；恢复后原样回填（`AppControllerTest` 断言） |
| 收藏 | ✅ | 练习页与错题本可收藏；「收藏」范围可直接抽题；题库卡片显示收藏数 |
| 学习日历（8 周热力图） | ✅ | `StudyCalendar`：活跃天数、连续天数、按提交次数分 4 档 |
| 历史筛选（时间 / 题库 / 判定） | ✅ | `HistoryFilter`：今天 / 7 天 / 30 天 / 全部 × 题库 × 正确/错误/待确认 |
| 完成会话自动清场 | ✅ | 总结页返回首页即清会话文件；最后一题已提交但没看总结的会话在启动时清掉 |

## P0 + P1 结论（维持有效）

做题闭环跑通：**导入 JSON 题库 → 练习设置 → 抽题作答 → 离线判题 → 追加历史 → 错题本 → 总结**。
关键不变量：`optionId` 是身份、A/B/C/D 是显示序号；提交不移动游标；待确认不进分母；
历史只追加不覆盖；导入题型含糊必须由用户选择。

## 验证证据（2026-10-19 真实执行）

```
:shared:jvmTest            → 66 用例 0 失败（含范围筛选、混合抽题配比、
                             会话暂停恢复闭环、日历分桶、草稿恢复、会话持久化）
:desktopApp:compileKotlin  → BUILD SUCCESSFUL
:androidApp:assembleDebug  → BUILD SUCCESSFUL（APK 已产出）
:webApp:compileKotlinJs    → BUILD SUCCESSFUL
:desktopApp:run            → 种子数据实测：
                             首页显示「继续上次练习 · 已答 2/6 · 停在第 3 题」、
                             学习日历（8/25—10/19 · 活跃 9/56 天 · 连续 2 天）、
                             点「继续练习」后正确停在断点题目（截图验证后关闭）
```

桌面数据目录：`~/.knowledge-trainer/`
- `banks-v1.json` 题库 · `attempts-v1.ndjson` 历史（只追加）
- `session-v1.json` 进行中会话（含草稿） · `favorites-v1.json` 收藏

## 架构增量（P2）

- `domain/practice/`：`PracticePlan` / `PracticeScope` / `QuestionSelector` / `StudyStats` / `StudyCalendar`（纯 Kotlin，可测）
- `application/usecase/`：`PlanPracticeUseCase`（按范围+统计+收藏过滤）、`SessionLifecycle`（保存/暂停/恢复/清场）
- 端口新增：`SessionRepository`（单活跃会话）、`FavoriteRepository`（键值集合）
- `Session` 增加 `paused()/resumeNormalized()/draft/updatedAtMillis`；游标对齐逻辑有测试
- 会话文件只存题目清单+游标+草稿，**已答记录仍只存历史**，恢复时按 sessionId 合并 —— 同一份作答只存一份

## 仍未实现（按规划阶段）

| 能力 | 阶段 | 备注 |
|---|---|---|
| `.qbank` / KT-QBF 1.0 规范与制包工具 | P3 | 下一步 |
| 部分得分策略（作者声明） | P4 | 目前只有 exact-set |
| 默知识点 / 语义重组模式 | P4 | |
| Markdown / LaTeX 渲染 | P5 | 题干按纯文本显示 |
| SQLite（Room / SQLDelight） | P2 后优先 | 见技术债 |
| AI（错因诊断 / 出题） | 后续 | |

## 技术债（P3 前建议先处理）

1. **存储层 JSON 全量重写**：历史是 NDJSON 追加没问题，但题库与读路径都是全量读入。十万题规模需要 SQLite。
2. **导入无大小限制**：超大文件一次性读入内存会 OOM，建议加上限 + 流式解析。
3. **Web/iOS 内存存储**：重启即丢；会话/收藏在 Web 端刷新即失效，界面已如实提示。
