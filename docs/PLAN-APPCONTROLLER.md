# AppController 拆分计划

> 目标：把 `presentation/AppController.kt` 从"什么都装"的组合根，拆成
> **一个组合根 + 若干关注点控制器**，每一刀都独立可验证。

## 现状

| 项 | 数值 |
|---|---|
| `AppController.kt` | 1691 行（3371 → 3219 → 3160 → 2972 → 2829 → 2786 → 2716 → 2530 → 2476 → 2247 → 1949 → 1691） |
| 公开成员 | 约 190 个（属性 + 方法） |
| 接触 infrastructure 的界面文件 | 只有它（架构测试白名单硬约束） |
| 覆盖它的测试 | `AppControllerTest`、`PracticeLoopTest`、`ScreenshotTest` 等，623 个 JVM 用例 |

它同时承担四件事，混在一起才是问题：

1. **组合根**：挑选具体存储 / AI / 媒体 / 文件选择器实现（`AppDependencies`）；
2. **读模型**：`banks`、`history`、`studyStats`、`notes`、`favorites` —— 几乎每个界面都要读；
3. **导航**：`screen`、`banner`、`busy`、各页的返回目标；
4. **各功能的状态与行为**：导入、练习、备份、图谱、AI、批量、笔记……

第 4 类内部彼此几乎不引用，是天然的切割线；第 1–3 类是真正的共享内核，必须留下。

## 目标形态

```
presentation/
  AppController.kt        组合根 + 共享内核（读模型 / 导航 / 刷新编排）
  toolbox/ToolboxController.kt      ✅ 已拆
  history/HistoryController.kt      ✅ 已拆
  setup/PracticeSetupController.kt ✅ 已拆
  ai/AiController.kt                ✅ 已拆
  editor/QuestionEditorController.kt ✅ 已拆
  review/ReviewController.kt        ✅ 已拆
  batch/BatchController.kt          ✅ 已拆
  importing/ImportController.kt    ✅ 已拆
  practice/PracticeController.kt  ✅ 已拆
  graph/GraphController.kt      ✅ 已拆
  backup/BackupController.kt    ✅ 已拆
```

## 三条硬规则

1. **子控制器之间不互相引用。** 需要协作时由 `AppController` 协调（例如导入完成后
   调 `refresh()`）。否则拆完会得到十个互相引用的类，比一个大类更难改。
2. **子控制器只认 `domain` 与 `application`。** 不 import `infrastructure.*`；
   具体实现由组合根注入。拆分完成后，架构测试白名单里"允许接触 infrastructure 的
   presentation 文件"应当只剩 `AppController.kt` 一条。
3. **每一刀都要让 `:shared:jvmTest` 全绿后再落下一刀。** 不攒批；一旦出现
   "先拆完再一起修"，回归就没法定位到具体某刀。

## 留下的那一半

拆完后 `AppController` 仍会有约 1000 行，这是对的 —— 它承担的是：

- `AppDependencies` 的装配与默认值（组合根）；
- 读模型的加载与刷新（`loadAll` / `refresh` / `refreshHistoryOnly`）；
- 导航与全局提示（`screen`、`banner`、`busy`、各页返回目标）；
- 跨功能的编排（导入完成 → 重载题库 → 重算统计）；
- 题目查找（`questionOf` / `questionFor` / `bankTitle`）与媒体读取。

## 切割顺序（按耦合从低到高）

| # | 关注点 | 现有行数 | 主要调用方 | 状态 |
|---|---|---:|---|---|
| 1 | 工具箱（计算器 + 单位换算） | ~150 | `ToolboxScreen` | ✅ 已完成 |
| 2 | 历史与日历 | ~82 | `HistoryScreen` | ✅ 已完成 |
| 3 | 练习设置 | ~86 | `PracticeSetupScreen` | ✅ 已完成 |
| 4 | AI 讲解 | ~141 | `AiScreen`、`PracticeScreen` | ✅ 已完成 |
| 5 | 离线手工编辑 | ~147 | `QuestionEditorScreen` | ✅ 已完成 |
| 6 | 历史复核 | ~199 | `ReviewScreens` | ✅ 已完成 |
| 7 | 批量队列 | ~211 | `AiScreen` | ✅ 已完成 |
| 8 | 导入（JSON / .qbank / .qbext / .qbpatch） | ~235 | `ImportScreen` | ✅ 已完成 |
| 9 | 练习（作答 / 提交 / 下一题 / 会话） | ~196 | `PracticeScreen` | ✅ 已完成 |
| 10 | 个人知识图谱 | ~429 | `GraphScreen` | ✅ 已完成 |
| 11 | 备份与迁移（.qbk） | ~437 | `BackupScreen` | ✅ 已完成 |

排序理由：先做"读写独立性高、只被一个界面使用"的部分，积累套路；
练习与备份涉及会话恢复、媒体重建、题库整体替换，放最后。

## 每一刀的验收清单

1. `./gradlew :shared:jvmTest` 全绿（数量不减）；
2. 被移动的成员在 `AppController` 上不再留同名转发属性 —— 宁可在调用方改一次，
   也不要在组合根里堆 190 行 `get() = toolbox.xxx` 之类的胶水；
3. 新类的 KDoc 写清"它为什么值得单独存在"，而不是"它装了哪些字段"；
4. 若牵涉架构测试白名单，同步更新并把删掉的条目计数写进提交说明。

## 已完成的第一刀（工具箱）

- 新增 `presentation/toolbox/ToolboxController.kt`（186 行），把计算器与单位换算的
  状态、实时求值、留档上限一起搬走，顺带把 `ToolboxTab` / `CalcRecord` / `ConvPicker`
  三个只服务于工具箱的类型也搬进同一个包 —— 类型和使用者放在一起，包边界才对得上。
- `AppController` 只留导航：`openToolbox` / `closeToolbox` 与返回目标。
- 调用方改动：`ToolboxScreen`（40 处）、`AppControllerTest`（45 处）、
  `ScreenshotTest`（16 处），全部是 `controller.x` → `controller.toolbox.x`。
- 文件从 3371 行降到 3219 行；行为不变，测试数量不变。

为什么先拿它开刀：它与题目、题库、练习会话**零耦合**，不写持久化，也不联网，
是唯一能证明"拆分不需要改动行为"的样板。

## 已完成的第二刀（历史与日历）

- 新增 `presentation/history/HistoryController.kt`（131 行）：四个筛选条件
  （日期范围 / 题库 / 判定 / 语言）与两块派生视图（学习日历、错题本），
  连同只服务于历史页的 `HistoryRange` 一起搬进新包。
- 与第一刀不同，它有一个外部依赖：**历史列表本身**。这里没有把列表复制一份，
  而是从构造参数接收 `() -> List<AttemptRecord>` 读取器 —— 复制一份快照，
  就会出现"组合根换了历史、历史页还是旧的"两份真相。
- 两份派生视图反过来**必须显式刷新**：`update(history)` 由组合根在
  `loadAll` / `reloadHistory` 之后调用。它们要遍历全部记录，不能做成 getter
  每帧重算；而"什么时候该重算"只有组合根知道，所以用参数把这件事摆在调用点上。
- 命名上刻意叫 `historyView` 而不是 `history`：后者是共享内核里的**数据**
  （统计、会话恢复、复核都要用），前者是**看它的方式**。同名会逼着把原始列表
  改个更含糊的名字。
- 新增 `HistoryControllerTest`（5 例），钉住两条容易改坏的规则：
  筛选是**叠加**的；日历与错题本**不跟着筛选走**（切到"只看错题"不该让那天
  的学习记录从日历上消失）。
- 调用方改动：`HistoryScreen` 11 处、`HomeScreen` 4 处、`MultiLocalePracticeTest` 10 处、
  `ReviewFlowTest` 2 处、`ScreenshotTest` 1 处。
- 文件从 3219 行降到 3160 行；608 → 614 用例全绿（新增 7 例，替换 1 例），行为不变。

拆完这一刀才发现的日期范围问题（"今天"其实是最近 24 小时）单独一次提交修掉，
没有混进这一刀的 diff：拆分提交里出现行为变化，回归就定位不到是哪一刀引起的。

顺带删掉了一处遗留：`/** 工具箱的两个面板。 */` 这行 KDoc 在第一刀之后失去了宿主，
挂在了 `GraphPickSide` 上面，读起来像"图谱编辑器有两个面板"。

## 已完成的第三刀（练习设置）

- 新增 `presentation/setup/PracticeSetupController.kt`（301 行）：抽题范围、题型、
  标签、题量、语言、缺译文策略，连同**按语言算出来的那份历史统计**
  （一道题在英文里答对过，不代表中文里也会）与两类计数缓存（题型分布、各范围候选数）。
- 依附关系比前两刀更像"一个功能"：它依赖题库（取语言与标签）、历史（算统计）、
  收藏（候选数算法要用），三个都通过读取器传入，与历史页同一套做法。
- 与前两刀最大的不同：**它要造东西**。`start()` 抽题成功后不自己往下走，
  而是返回 `SetupStart.Ready(session)` / `SetupStart.Empty(reason)` ——
  组合根接过来决定弹哪条横幅、跳哪个页面、什么时候存盘。子控制器不导航、
  不写盘、不弹提示，这条边界在这刀第一次真正被用到。
- 计数的失效时机被收进类里：历史一变，组合根只调 `invalidateStats()`，
  不必再知道"哪个 private 字段该清空"。此前这一步是两处零散的
  `setupStatsLocale = ""`，散落在 `loadAll` 与 `reloadHistory` 中间。
- `startPractice`（题库卡片上的快捷开始）也搬了抽题部分：它有自己的默认语言，
  不能借用设置页当前的选择 —— 用户可能刚在另一个题库里选了英文。
- 调用方 44 行：`PracticeSetupScreen` 31、`MultiLocalePracticeTest` 22（另加 1 个新用例）、
  `AppControllerTest` 7、`ScreenshotTest` 3 —— 全部是 `controller.setupXxx` → `controller.setup.xxx`。
- 新增用例 `saysTranslationIsMissingRatherThanPretendingThereIsNoQuestion`：
  整库没有某语言译文时，横幅要说"这门语言没译文"，而不是含糊的"没有可练习的题目"。
- 文件从 3160 行降到 2972 行；614 → 615 用例全绿，行为不变。

## 已完成的第四刀（AI 讲解）

- 新增 `presentation/ai/AiController.kt`（244 行）：接入配置、密钥有无、今日用量、
  讲解记录、生成中标记、面板展开，连同"哪一道题该用哪种语言"的回退规则一起搬走，
  审核（采纳 / 弃用 / 打回）也归它。
- 这是第一刀**必须自己写盘**的拆分：配置、密钥、讲解记录、用量账本都是本功能自己的
  数据，换个地方存就是两份真相。前几刀"子控制器不写盘"针对的是**别人的**数据
  （会话、题库、历史），那些仍归组合根。同一条规则在不同功能上边界不同，写下来
  比"统一照着前一次抄"更靠谱。
- 与前几刀相同的是**不导航、不弹提示**：`openAi` / `closeAi` 留在组合根；要说的话经
  `onNotice(text, isError)` 交回去，`text = null` 表示"没话说，顺手把上一条收起来"
  （命中缓存就是这种情形：既不是新生成也不是失败，让"已生成"继续挂着像是又花了钱）。
  提示不做成状态而做成回调，是因为这里不该攒"待显示的横幅"，否则会出现
  "已经换页了、横幅才冒出来"。
- 当前题目通过 `() -> CurrentQuestion?` 读取器传入，与历史页同一套做法，但**代价更高**：
  缓存键按"题库 + 题目 + 语言 + 修订 + 内容指纹 + 模型"算，快照错位不只是显示错，
  而是让用户为上一道题再付一次钱。会话结构不进 AI 包 —— 组合根只把
  `bankId / question / sessionLocale / bank / attempt` 五样递过去。
- 组合根留下的只有三件：`openAi` / `closeAi` 两条导航、`aiTargetOf()` 这段搬运
  （其中按 `questionId` 再核一次作答记录，因为提交反馈与当前题目之间隔着一次"下一题"）、
  以及 `refreshAi()` 这个"一次刷新喂两处"的入口（讲解 + 批量队列）。等批量队列也搬出去，
  这个入口改成调它的 `refresh()` 即可，调用点不用再动。
- 调用方 57 行：`PracticeScreen` 17、`AiScreen` 16、`AiBatchFlowTest` 11、
  `ScreenshotTest` 4、`BatchScreen` 4、`HomeScreen` 1，全部是
  `controller.aiXxx` → `controller.ai.xxx`。
- 新增用例 `explanationDoesNotFollowYouToTheNextQuestion`：答完第一题生成讲解，
  切到第二题后缓存键必须变、`currentExplanation` 必须为空、已有记录不能跟着冒充；
  第二题没作答也不许发请求。这条钉的正是"读取器而不是快照"这个决定。
- 文件从 2972 行降到 2829 行；615 → 616 用例全绿，行为不变。

## 已完成的第五刀（离线手工编辑）

- 新增 `presentation/editor/QuestionEditorController.kt`（108 行）：当前题库、校验结果、
  保存成功标记、忙碌标记，以及"校验 → 落盘 → 版本号加一"这整条链路。
- 值得单独存在的理由是**校验与写入必须写在一起**：规矩是 fail-closed（有任何 ERROR
  就不落盘），且落盘要连带把 `contentRevision` 加一。这两句分开写在两个文件里，
  就没人能一眼确认"校验过了就真会写、写了就真校验过"。
- **表单字段没有搬进来。** 题干、选项、答案仍是 `QuestionEditorScreen` 的本地状态，
  只在点保存那一刻组装成 `Question` 递过来。每敲一个字就往控制器里推一个不可变题目，
  既慢，也会让"正在输入、还没校验"的中间态变成全局状态 —— 别处读到时无法判断它算不算数。
- 它写的是**题库**（别人的数据），所以写完之后不自己重读，只通过 `onBankChanged`
  说一句"题库变了"。重读要连带历史、统计、图谱一起换，那是组合根的事。
- 组合根留下 `openQuestionEditor` / `closeQuestionEditor` 两条导航 —— 编辑器也一样
  "不导航"，这刀没有例外。
- 调用方 15 行：`QuestionEditorScreen` 9 处、`AppControllerTest` 6 处（另加 1 个新用例）。
- 用例 616 → 617：新增 `manualQuestionWithAnExistingIdIsRejected`。顺手在原有用例里
  补了一条断言（`contentRevision` 必须 +1）—— 加题就是新一版内容，这条以前没人看着。
  重复 ID 之所以必须拒收而不是覆盖：ID 是进度、复核、错题本匹配题目的唯一依据，
  撞了 ID 会让两套题面共享同一批作答记录，事后分不清哪条历史属于谁。
- 文件从 2829 行降到 2786 行。

## 已完成的第六刀（历史复核）

- 新增 `presentation/review/ReviewController.kt`（163 行）：待复核清单、逐条表态、批量表态、
  首页徽章数字，以及**原始历史与复核决定两份数据本身**。
- 这一刀把"两份历史"的区别收了回来。`attempts` 文件存的是**当时按那时的标准**判成了什么，
  复核决定另存一处，读取时才合并。此前那行 `AttemptReviews.apply(...)` 在 `loadAll` 与
  `reloadHistory` 里**各写了一次** —— 两处漏一处，重读（恢复备份、装扩展包、手动刷新）
  之后统计与错题本就会退回旧判定，而界面上完全看不出区别。
  现在只有 `readHistory()` 一处合并，组合根一律 `history = review.readHistory()`。
- 与前几刀不同，它保管的是**共享内核的数据**（原始历史还被引用自检与备份概况读）。
  所以两个只读出口单独给了名字：`rawAttempts` 与 `decisionCount` —— 让"备份报的是
  **历史文件**里有几条"这件事在调用点上就能读出来，而不是靠记得"这里的 history 是合并后的"。
- 徽章数字仍走便宜的那条路（只看修订日志里的 `answerChangedIds`，不解析题库内容），
  所以它可能比进页面后的清单条数多：答案变了但重算结果相同，不算候选。
  这条差异写在 [pending] 的 KDoc 里，因为"徽章显示 3、点进去只有 1"很容易被当成 bug。
- 导航仍留在组合根：`openReview`（置页 + `review.scan()`）、`closeReview`（`goHome()`）。
  清单在返回时**不清**：用户可能去题库里看一眼再回来，重扫一遍等于让他重新等一次。
- 调用方 33 行：`ReviewScreen` 11、`ReviewFlowTest` 16（另加 1 个新用例）、
  `ScreenshotTest` 4、`HomeScreen` 2。
- 新增用例 `aFullReloadKeepsTheDecisionAndStillReportsTheRawHistory`：表态后整库重读，
  生效判定不能丢、徽章不重新冒出来，而备份概况仍报历史文件的条数。
  这条钉的正是"合并只有一处"——它是这次拆分唯一可能悄悄引入的回归。
- 文件从 2786 行降到 2716 行；617 → 618 用例全绿，行为不变。

## 已完成的第七刀（批量队列）

- 新增 `presentation/batch/BatchController.kt`（258 行）：建队列、跑、暂停、取消、删除，
  看板上"现在显示哪个任务"，以及把队列项还原成请求的那段解析。
- 它是全应用唯一**长时间在后台推进**的东西，别的功能都是一次动作一个结果。
  所以值得单独存在的不是"字段多"，而是状态机（排队 / 运行 / 暂停 / 取消）
  与那个"叫停意图"之间的配合：用户点暂停只是写下 `stopIntent`，
  真正的状态由执行器在**请求边界**上收尾 —— 打断正在飞的请求同样会扣费，
  还会留下一条不知道算成功还是失败的记录。这段配合埋在三千行中间没人能一眼确认它没跑飞。
- 这一刀有两处"题库"，刻意没有合并：
  - [create] 用 `banks()` 读取器，也就是用户此刻在界面上看到的那个 ——
    报的"共 N 道"必须和他看到的数字对得上；
  - [resolve] 用 `bankRepository.list()` 重读磁盘，因为暂停期间题库可能被改过，
    按新内容生成、缓存键才会跟着变。
  两者同名会让人以为可以互换，所以 KDoc 里直接写清"不是同一个东西"。
- `refreshAi()` 现在是真正的"一次刷新喂两处"：`ai.refresh()` + `batch.refresh()`。
  上一刀留下的那句注释（"等批量队列也搬出去，这里改成调它的 refresh()"）在这里兑现，
  调用点没有改动 —— 跑完任务后讲解记录、今日用量、任务列表一起换，才不会出现
  "用量已经涨了、任务还显示在跑"。
- 配置从读取器 `config()` 进来，而不是直接拿 `ai` 控制器：子控制器之间不互相引用，
  由组合根把 `AiConfig` 递过来。模型名要写进任务里，缓存键按它算。
- 调用方 48 行：`BatchScreen` 11、`AiBatchFlowTest` 31（另加 1 个新用例）、
  `ScreenshotTest` 3、`AiScreen` 3，全部是 `controller.createBatch` → `controller.batch.create` 这类改名。
- 新增用例 `questionsGoneFromTheBankAreSkippedInsteadOfBeingRequested`：排队之后题库被换成
  另一批题，跑起来必须**从磁盘重读**（找不到就跳过并说明"题库里已经没有这道题"），
  0 次请求、0 条完成。这条钉住 [resolve] 的存在理由——用内存里那份旧题库发请求，
  就是拿已经不存在的内容在花钱。
- 文件从 2716 行降到 2530 行；618 → 619 用例全绿，行为不变。

## 已完成的第八刀（导入）

- 新增 `presentation/importing/ImportController.kt`（246 行）：粘贴文本、`.qbank` 包、
  "选文件"入口、解析预览、行内错误、最近处理的文件名。
- 值得单独存在的理由不是"字段多"，而是**两条输入路径共用一套结论**：文本走宽松导入
  （允许部分题目有问题、其余照常导入、要题型映射），包走严格校验（错一条整包拒绝、
  不做映射）。过程完全不同，但"导入之后"必须一致 —— 清不清空输入框、预览留不留、
  重复导入怎么说。这套一致性以前只存在于一处 `when` 里，改一条分支没人知道另一条还在不在。
- **认包没有搬。** `.qbext` / `.qbpatch` / `.qbk` 分别落到扩展、补丁、备份三个功能上，
  而认包类型要用 `infrastructure` 的探针 —— 子控制器不许碰，所以分发留在组合根。
  这也正是"跨功能编排留在组合根"那条规则第一次真正被用到。
- **结论做成返回值**（`ImportVerdict`）：题库落库可能让某个"等待题库"的扩展包当场生效，
  而"哪一个生效了"要比对落库前后的扩展清单 —— 两份清单都只在组合根手里。
  让导入控制器自己拼这句话，就得把扩展包状态也搬进来，两个功能从此互相引用。
  `Rejected` 刻意不带信息：行内已经写了"没有可导入的题目"，横幅再说一遍只是重复。
- **成功之后清不清空输入框，由来源决定。** 文本导入清空（防止用户再点一次，点了也只会
  撞重复），包导入不清（输入框里那段 JSON 与这次导的包无关，替用户删掉等于替他丢东西）。
  这条差异写在 `accept(outcome, fromText)` 的参数名上，不靠读调用点猜。
- 顺手删掉一处死字段：`lastImportNotes` 只写不读，没有任何界面引用。
- 调用方 16 行：`ImportScreen` 13 处、`ExtensionsScreen` 3 处，全部是
  `controller.importXxx` → `controller.importing.xxx`。`openImport` / `importDroppedFile` /
  `pickPackageFile` / `confirmImport` 留在组合根：它们要么导航，要么分发。
- 新增用例 `importingTheSameTextTwiceCreatesNoCopyAndKeepsThePreview`：同一份材料导两次
  不许建副本，第二次仍要把预览留在页面上 —— 清空输入框是为了防止再点一次，
  但连预览一起丢掉，用户就只剩一句错误横幅，看不出这次导进去了什么。
- 文件从 2530 行降到 2476 行；619 → 620 用例全绿，行为不变。

## 已完成的第九刀（练习）

- 新增 `presentation/practice/PracticeController.kt`（415 行）：当前会话、待继续的会话、
  输入中的答案、判题反馈、总结、草稿存盘，以及"会话文件 + 历史"的合并恢复。
- 它与前八刀不同的地方是**状态会被并发改写两次**：作答在协程里跑，用户的手指同时还在改
  草稿（选项、文本、片段顺序），而草稿又要合并存盘。`draftJob` 的取消防抖、提交前先取消草稿、
  换题时清空输入，这三件事必须写在一起 —— 否则"提交后草稿又把旧答案写回去"这类问题
  会随机出现且事后无法复现。埋进三千行里，没人能一眼确认它没写反。
- **会话恢复的合并规则跟着搬了。** 会话文件只存题目与游标，已答数量从历史合并；
  最后一题交过但没点"查看总结"的会话，实际已经做完，要清掉而不是永远挂着。
  这条规则以前夹在 `loadAll` 里，和题库、扩展、图谱、历史的读取排在一起，
  看不出它其实只属于"练习"。
- 三条边界：
  - **不导航。** 作答页 / 总结页 / 首页仍由组合根决定；`next()` 与 `finish()` 返回
    "还有没有下一题 / 有没有会话"，去哪儿由调用点说。`next` / `finish` 这两个名字
    在组合根和子控制器上同时存在，是这一刀唯一容易接错的地方。
  - **不写别人的数据。** 会话文件与作答记录是它自己的；历史、统计、日历经
    `onAttemptRecorded` / `onSessionClosed` 交回组合根。
  - **不判"硬依赖是否过期"。** 那要看图谱关系与题库修订号，组合根算完通过
    `reportBlockedDependencies(count)` 递给它 —— 与历史页 `update(history)` 同一套做法：
    派生显示状态归子控制器，"什么时候该重算"归组合根。
- 组合根留下：抽题（setup）、错题聚合（historyView）、收藏、导航、`checkHardDependencies`。
- 调用方 55 行：`PracticeScreen` 25、`ScreenshotTest` 21（另加改名）、`MultiLocalePracticeTest` 17、
  `AppControllerTest` 43 处改名 + 1 个新用例、`AiFlowTest` 7、`HomeScreen`/`SummaryScreen` 各 1。
- 新增用例 `aSessionWithNothingLeftToAnswerIsClearedInsteadOfOfferedAsResumable`：
  最后一题答完但没点总结就退出，下次启动**不该**出现"继续练习"（点进去只会看到一道空题），
  而答过的记录仍在历史里。这条钉的正是搬过来的那段收尾规则。
- 文件从 2476 行降到 2247 行；620 → 621 用例全绿，行为不变。

## 已完成的第十刀（个人知识图谱）

- 新增 `presentation/graph/GraphController.kt`（439 行）：图谱本体、与生效扩展包的合并视图、
  解析帧、筛选、跨库搜题（含防抖）、编辑草稿，以及保存 / 删除 / 复核三条写路径。
- 这一刀搬的不是"字段多"，而是**唯一一处"显示的东西不等于存的东西"**：磁盘上只有用户手建的
  那一份，界面上看到的却是"手建 + 生效中扩展包"。两者在视图层完全同权，唯一区别是扩展包带来的
  关系不能在这里改。这层合并以前夹在每次 `refresh()` 里，谁也没法一眼确认"删了包关系还在不在"。
- **导航留在组合根，写盘结果做成返回值。** `GraphWrite.Saved(message)` /
  `Blocked(message)` 只描述"成了没有、该说哪句话"；要不要离开编辑器由调用点决定 ——
  删除在列表页和编辑页都能点到，两处的下一屏不一样（同一条规则顺手也适用保存）。
  `Blocked(null)` 表示"问题已经显示在表单里了"，不要再加一条横幅。
- **组合根仍负责认扩展包。** "这条关系来自《某包》"要查包清单，`extensionNameOf` 是扩展包那一侧
  的知识，所以 `openGraphEditor` / `deleteGraphEdge` / `revalidateGraphEdge` 三个入口先拦一道，
  子控制器只认手建关系（`ownsEdge`）。
- **题库标题不自己拼。** 题库缺失时组合根还会去"待继续会话"里找标题，所以 `bankTitleOf`
  由外部递入，而不是在控制器里再写一份"（题库缺失）"。
- 两个视图类型跟着搬：`GraphPickSide`、`GraphCard` 从组合根挪进 `presentation/graph`，
  `AppController.kt` 从此不再定义图谱类型。
- 顺手删掉一处死代码：`graphEdgesForCurrentQuestion()` 没有任何调用点。
- 调用方 82 处：`GraphEditorScreen` 40、`GraphScreen` 11、`PracticeScreen` 1、`HomeScreen` 1、
  `AppControllerTest` 25、`ExtensionFlowTest` 4。
- 新增用例 `deletingAnEdgeFromTheListRefreshesTheListAndStaysThere`：列表页删完必须留在列表，
  而且"解析过的那一帧"要跟着变 —— 模型空了视图没空，界面上就会留着一行已经不存在的关系。
- 文件从 2247 行降到 1949 行；621 → 622 用例全绿，行为不变。

## 已完成的第十一刀（备份与迁移）

- 新增 `presentation/backup/BackupController.kt`（569 行）：导出表单与三条规则、读入 / 解锁、
  待确认状态、恢复、引用自检的结论与时间、上次恢复被打断的那条通知，以及"忙"标记。
- 三个视图类型跟着搬：`BackupSummary` / `BackupExportInfo` / `BackupIntake` 从组合根挪进
  `presentation/backup`，`AppController.kt` 从此不再定义备份类型。
- **这一刀的核心问题是分层，不是行数。** 三个用例的返回类型（`BackupBundle`、`BackupReadResult`）
  来自 `infrastructure`，而子控制器不许认识 `infrastructure` —— 于是把用例留在这里、包成
  lambda 递进去，并且**在递进去之前就把容器摘成 `PackedBackup` / `BackupRead`**。
  这一步"摘字段"本来也只有组合根能做：它是唯一允许挑具体实现的地方。
  架构测试的白名单因此仍然只有 `AppController.kt` 一条。
- **写盘结果做成返回值**：`BackupExportVerdict.Saved / Cancelled / Failed / Blocked`。
  导出成功之后要顺带重跑一次引用自检，而自检要拿"真正打包进去的那份文件清单"去核对整库 ——
  那是组合根的事，所以 `Saved` 里带着 `payloadPaths`。
- **恢复只回结论，不负责重读世界**：`RestoreVerdict.Done` 带上"写了几个文件、旧数据去了哪、
  写进去的清单"，`loadAll()` 与自检留给组合根 —— 与历史页 `update(history)`、
  练习页 `reportBlockedDependencies(n)` 同一套做法：全局的事归组合根，这一屏的事归子控制器。
- **一次性通知的"只说一次"也搬了**（`takeRecoveryNotice()`）：横幅位归谁由组合根定
  （可能被更要紧的加载警告占着），说没说过由控制器记 —— 让调用点自己管 `announced` 标记，
  一定会有人忘了置位。
- `working { }` 收掉四处手写的 `busy = true / false`：打包、解锁、恢复、重读自检都会抛异常
  （磁盘满、文件被删），漏写 `finally` 的那一处会让界面永远停在"正在恢复…"。
- 调用方：`BackupScreen` 约 30 处、`HomeScreen` 1、`BackupFlowTest` 45、`ScreenshotTest` 6。
- 新增用例 `leavingTheBackupPageForgetsTheUnlockedPassphrase`：解锁之后不恢复、直接返回，
  口令、待确认的备份、加密开关必须一起消失。这条钉的正是"两边都对才抹得掉"的分界。
- 文件从 1949 行降到 1691 行；622 → 623 用例全绿，行为不变。
- **计划完成**：11 个关注点全部拆完，组合根从 3371 行降到 1691 行（-50%），
  用例从 601 涨到 623。
