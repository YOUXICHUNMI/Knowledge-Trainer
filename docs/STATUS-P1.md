# Knowledge Trainer — 开发状态（P0 + P1）

> 更新时间：2026-10-20（本表是 P0/P1 时点的快照；后续阶段补上的能力已就地改为 ✅ 并标注所属阶段，
> 详细证据与已知限制见 [STATUS-P3](./STATUS-P3.md)）
> 本文件只记录**真实完成**的范围与可复现证据，未实现的能力一律标注为未实现。

## 一句话结论

做题闭环已经跑通：**导入 JSON 题库 → 选择题库开练 → 单选/多选/判断/简答作答 → 离线判题（含漏选误选、逐字 diff）→ 追加历史 → 错题本 → 练习总结**。
Windows 桌面端可运行（窗口验证），Android 可打出 APK，Web(JS) 可编译；46 个自动化测试全部通过
（P1 时点的数字；当前 601 个，见 [STATUS-P3](./STATUS-P3.md)）。

## 完成度对照

| 能力 | 状态 | 说明 / 证据 |
|---|---|---|
| 分层架构（Domain / Application / Infrastructure / Presentation） | ✅ | `shared/src/commonMain/kotlin/.../domain｜application｜infrastructure｜presentation`，依赖单向内指 |
| 组合根、无全局可变单例 | ✅ | 各平台入口各自构造 `AppDependencies`（Desktop/Android 用文件，Web/iOS 用内存） |
| 原生题型：单选 / 多选 / 判断 / 简答 | ✅ | `QuestionType`；判断题为 2 选项特例，可自动补「正确/错误」 |
| 题型 / 交互 / 模式 / 判题策略四维分离 | ✅ | `QuestionType` · `AnswerInput` · `PracticeMode` · `GradingSpec` + `GradingRegistry` |
| 多选按集合判定（顺序无关） | ✅ | `GradingTest`：ABC / BAC 均判对 |
| 漏选、误选分别标注 | ✅ | `EvaluationDetail.Choice.missing / .extra`，界面显示「漏选 C」「误选 D」 |
| 选项乱序不改变答案身份 | ✅ | 会话创建时冻结 `displayOptionIds`；A/B/C/D 只是显示序号 |
| 严格默写（逐字符） | ✅ | `StrictTextEvaluator` 不做任何归一化 |
| 文本差异标注 | ✅ | 共同前后缀 + 中间差异块；提示「仅空白字符不同 / 全半角 / 换行」 |
| 无可靠依据 → 待确认 | ✅ | 未知策略、缺答案均返回 `PENDING`，绝不默认判错 |
| JSON 导入（数组 / `{questions:[]}` / NDJSON） | ✅ | `JsonQuestionImporterTest` 14 例 |
| 兼容 A/B/C/D 扁平字段、数字索引答案、选项对象/字符串 | ✅ | 同上 |
| 题型含糊必须由用户选择 | ✅ | `needsMapping` + 界面「按单选 / 按多选导入」，不猜 |
| 重复导入检测 | ✅ | 内容指纹（FNV-1a 64）命中即拒绝创建副本 |
| 历史追加写、不覆盖 | ✅ | `attempts-v1.ndjson` 逐行追加；仓储接口无 update/delete |
| 错题按题聚合（不是错一次复制一份） | ✅ | `WrongQuestions.derive`，以最近一次判定为准 |
| 总结分母显式 | ✅ | 正确率 = 正确 ÷ 已判定；待确认、放弃不计入分母 |
| 桌面端持久化 | ✅ | `~/.knowledge-trainer/banks-v1.json` + `attempts-v1.ndjson`，先写 `.tmp` 再重命名 |
| Android 持久化 | ✅ | 应用私有目录 `files/knowledge-trainer/`，不申请存储权限 |
| Web / iOS | ⚠️ 可运行但内存存储 | 刷新/重启即丢失，界面顶部明确提示，不假装保存 |
| Markdown / LaTeX 渲染 | ✅ P5 | 自写 Markdown 子集 + LaTeX 排版，离线、无 CDN、无 WebView；见 STATUS-P3「P5 第一切片」 |
| `.qbank` / KT-QBF 1.0 规范与制包工具 | ✅ P3 | 规范 + Schema + 打包/校验工具 + 坏包回归 49 条 + Kotlin 严格导入；见 STATUS-P3「P3 新增能力」 |
| SQLite（Room / SQLDelight） | ❌ 未接入 | 当前为 JSON 文件，见技术债 |
| 会话暂停 / 恢复 / 异常重启续做 | ✅ P2 | 会话与草稿落盘，首页出现「未完成」卡片可继续 |
| 部分得分策略（作者声明） | ✅ P4 | `keywords` 关键词判题（全中 / 部分中 / 命中禁用词 / 无规则 PENDING）与 `ordering` 等价次序 |
| 死记硬背以外的增强训练（默知识点 / 语义重组） | ✅ P4 | 关键词判题 + 片段排序重组题；见 STATUS-P3「P4 新增能力」 |
| 题库更新补丁（`.qbpatch`） | ✅ P8 第一块 | 作者声明基于第几版改了什么，应用端逐条核对锚点后严格应用；预览前一次写都没有，历史一条不动 |
| 历史复核（用户控制） | ✅ P8 第二块 | 答案变了之后逐条列出"当时判的 / 按现在重算"，由用户选接受还是维持；决定单独存 `attempt-reviews-v1.ndjson`，历史文件不改写 |
| 来源真实性（出处声明 + 整包摘要） | ✅ P8 第三块 | 题库级 `sources[]` + 题目级 `origin / sourceId / sourceLocator`，界面四态分开（含「来源未标注」）；打包时自动算 `contentDigest`，导入端重算比对。**是声明不是证明**：只核对包内引用自洽，不核对内容真伪 |
| AI（本地 / 云端）、任务队列、预算 | ⚠️ 部分实现（P7 第一块 + 第二块） | 单题按需生成、预算与审核、持久化批量队列（暂停/继续/取消）已可用，见 [STATUS-P3](./STATUS-P3.md)。本地推理引擎、流式输出、移动端接入仍未做；其余功能不含任何网络调用 |
| ktlint / detekt / CI | ❌ 未接入 | 目前只有单元测试 |

## 可复现的验证

```bash
# 1) 自动化测试（46 个用例，含真实文件持久化与闭环回归）
./gradlew :shared:jvmTest
# 结果：BUILD SUCCESSFUL；GradingTest 13、SessionTest 7、JsonQuestionImporterTest 14、
#       PersistenceTest 4、PracticeLoopTest 4、FileStorageTest 2（均为 0 failures）

# 2) 桌面端编译与启动
./gradlew :desktopApp:run
# 结果：窗口 "Knowledge Trainer" 正常创建（1100x820）

# 3) Android 构建
./gradlew :androidApp:assembleDebug -Porg.gradle.java.installations.paths=D:/Path/Java/JDK/jdk-25.0.1
# 结果：BUILD SUCCESSFUL；产出 androidApp-debug.apk（约 12.8 MB）

# 4) Web 编译
./gradlew :webApp:compileKotlinJs
# 结果：BUILD SUCCESSFUL

# 5) 全平台共享层编译
./gradlew :shared:jvmTest :desktopApp:compileKotlin
```

## 关键设计决策

1. **判题器按策略注册，不写巨大 when**：`GradingRegistry` 按 `strategyId + supports(question)` 匹配；匹配不到返回 `PENDING`（是执行点，不是异常分支）。
2. **`PENDING` 是一等公民**：待确认不进正确率分母、不进错题本，避免「默认判错」污染历史。
3. **显示顺序与身份分离**：`optionId` 稳定，A/B/C/D 由 `SessionItem.labelOf()` 现算；会话创建时冻结顺序，题库更新不影响进行中的会话。
4. **历史只追加**：仓储端口只有 `append / all`，没有 `update`；「重新判定」实现为独立事件而非覆盖 —— P8 第二块的复核决定就是这样做的：`attempt-reviews-v1.ndjson` 追加一条，读取时合并，`attempts` 文件不动。
5. **暂缓 SQLite**：Room KMP 需要 Kotlin 2.4.20 + KSP + AGP 9 的兼容性验证，未验证前不引入。仓储接口（`BankRepository` / `AttemptRepository`）已把存储隔离，替换实现不影响领域与应用层。

## 技术债（按优先级）

1. **存储层**：JSON 全量重写、无暂存区与原子提交、无索引；十万题规模会失效 → 切 SQLite（含 `staging → validate → commit`）。
2. **导入未做资源限制**：没有文件大小上限与流式解析，超大 JSON 会 OOM（P5 必修）。
3. **Web/iOS 无持久化**：需要 IndexedDB / localStorage 与 iOS 文件目录实现。
4. **UI 未做 Markdown/LaTeX 渲染**：数学题目前只能看到源码；离线渲染资源需打包（不可用 CDN）。
5. **会话不可恢复**：`PracticeSession` 未持久化，进程退出丢当前进度（已提交记录保留）。
6. **列表性能**：总结/历史页用 `Column + verticalScroll`，长列表未验证。
7. **无 CI / 静态检查**：无 ktlint、detekt、架构依赖检查。
8. **无日期格式化**：只显示相对时间（"3 分钟前"），缺日历视图（P2）。

## 下一步（建议顺序）

- **P2**：会话持久化与暂停恢复、混合抽题、范围筛选（未练习 / 错题 / 收藏）、学习日历。
- **P3**：KT-QBF 1.0 + JSON Schema + 制包/校验工具 + 真实 `starter.qbank` + `tools/qbank-authoring-skill/`（含 SKILL.md 与正反例）。
- **P1.5（穿插）**：SQLite 接入 + 导入流式化 + 大小/路径安全校验。

## 运行环境

- Gradle 9.5.1 / Kotlin 2.4.20 / Compose Multiplatform 1.12.1 / AGP 9.1.1；Kotlin Serialization 1.11.0。
- 桌面数据目录：`~/.knowledge-trainer/`（不可写时自动退化为内存并在界面提示）。
