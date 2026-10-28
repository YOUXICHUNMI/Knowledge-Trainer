# AppController 拆分计划

> 目标：把 `presentation/AppController.kt` 从"什么都装"的组合根，拆成
> **一个组合根 + 若干关注点控制器**，每一刀都独立可验证。

## 现状

| 项 | 数值 |
|---|---|
| `AppController.kt` | 2829 行（3371 → 3219 → 3160 → 2972 → 2829） |
| 公开成员 | 约 190 个（属性 + 方法） |
| 接触 infrastructure 的界面文件 | 只有它（架构测试白名单硬约束） |
| 覆盖它的测试 | `AppControllerTest`、`PracticeLoopTest`、`ScreenshotTest` 等，616 个 JVM 用例 |

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
  editor/QuestionEditorController.kt
  review/ReviewController.kt
  batch/BatchController.kt
  importing/ImportController.kt
  practice/PracticeController.kt
  graph/GraphController.kt
  backup/BackupController.kt
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
| 5 | 离线手工编辑 | ~147 | `QuestionEditorScreen` | 待做 |
| 6 | 历史复核 | ~199 | `ReviewScreens` | 待做 |
| 7 | 批量队列 | ~211 | `AiScreen` | 待做 |
| 8 | 导入（JSON / .qbank / .qbext / .qbpatch） | ~235 | `ImportScreen` | 待做 |
| 9 | 练习（作答 / 提交 / 下一题 / 会话） | ~196 | `PracticeScreen` | 待做 |
| 10 | 个人知识图谱 | ~429 | `GraphScreen` | 待做 |
| 11 | 备份与迁移（.qbk） | ~437 | `BackupScreen` | 待做 |

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
