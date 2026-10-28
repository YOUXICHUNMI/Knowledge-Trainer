# AppController 拆分计划

> 目标：把 `presentation/AppController.kt` 从"什么都装"的组合根，拆成
> **一个组合根 + 若干关注点控制器**，每一刀都独立可验证。

## 现状

| 项 | 数值 |
|---|---|
| `AppController.kt` | 3160 行（拆工具箱前 3371，拆历史前 3219） |
| 公开成员 | 约 190 个（属性 + 方法） |
| 接触 infrastructure 的界面文件 | 只有它（架构测试白名单硬约束） |
| 覆盖它的测试 | `AppControllerTest`、`PracticeLoopTest`、`ScreenshotTest` 等，608 个 JVM 用例 |

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
  setup/PracticeSetupController.kt
  ai/AiController.kt
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
| 3 | 练习设置 | ~86 | `PracticeSetupScreen` | 待做 |
| 4 | AI 讲解 | ~141 | `AiScreen`、`PracticeScreen` | 待做 |
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
- 文件从 3219 行降到 3160 行；608 → 613 用例全绿，行为不变。

顺带删掉了一处遗留：`/** 工具箱的两个面板。 */` 这行 KDoc 在第一刀之后失去了宿主，
挂在了 `GraphPickSide` 上面，读起来像"图谱编辑器有两个面板"。
