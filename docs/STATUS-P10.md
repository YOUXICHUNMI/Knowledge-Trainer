# Knowledge Trainer — 开发状态（P10）

> 更新时间：2026-10-29
> 本文件只记录**真实执行过**的检查与产物；没跑过的平台、没签名的包，一律如实标注。
>
> 后续更新：[STATUS-P11.md](./STATUS-P11.md) 把「取一道题」从 389ms 降到 1ms、
> 「加一道题」从 226ms 降到 26ms、「搜一个词」从 1.26s 降到 5ms。本文件保留当时的测量口径与结论 ——
> 下文出现的「取一道题 245ms」「加一道题 226ms」都是**当时的**数字，不再是现状；
> 「搜一个词 1.26s」同样已被 P11 的两张派生表解决；
> 「整库替换（`save`：打补丁 / 重新导入）仍整库重写」这一条至今未变 ——
> 这不是漏掉的优化，产品里没有"就地改/删单题"的入口（见第 22 节末尾与 P11 第 8 节）。
>
> 安装包验收见第 22 节（2026-10-29）：装 → 覆盖升级 → 卸载在本机真跑过，
> 界面串（导入 → 练习 → 错题）仍未在装好的那份上点过。

## 一句话结论

P10 第一切片完成：**分层架构从"文档约定"变成可执行断言**（7 条，0 越层）；
**Windows 与 Android 首次产出真实可分发产物**（MSI 63.7 MB、debug APK 13.6 MB、
release APK 9.7 MB 未签名），桌面端 exe 实测可启动；
**制包 Skill 与工程内实现重新对齐** —— Skill 副本此前落后两天，会拒收应用已经接受的
`sources` / `contentDigest` 包，现在两边规范与校验器逐字节一致，四份真实题库（A/B/C/总）
在新校验器下仍全部通过。

之后又走了三刀：CI 把回归清单收成一份（第 1 节）、组合根从 3371 行拆到 1622 行（第 2–14 节）、
深色主题从"只有浅色"变成两套成套配色 + 像素护栏（第 18 节）。最近这一刀把 74 张截图
也搬进 CI（第 19 节）：先证明了**"job 绿着但一张图没拍"有两种走法**（UP-TO-DATE 与 FROM-CACHE），再推上去真跑 ——
首轮三个 job 全红（`gradlew` 丢了可执行位、`media/index.ndjson` 在 Windows 上被检出成 CRLF
导致 Linux 重建字节不同），修完之后三个 job 全绿，截图 artifact 也下载回来逐张看过。

再往后两刀都在存储层：先把「十万题加一道题」的代价量出来（第 20 节，1.0 秒 / 读写各 49.9MB / 峰值 106MB，
顺带抓到一个真缺陷 —— 目录里的指纹会说谎），再把它拆掉（第 21 节：端口长出 `addQuestion`、
指纹换成可增量结构，同样一件事变成 **226ms / 写 658 字节 / 7MB**）。
换指纹算法是**跨语言的破坏性变更**，`FingerprintParityTest` 当场把 17 个用例点红，
Python 制包工具、示例补丁、两侧 golden 一起改才重新变绿。

## P10-1 架构验收：把"依赖只能指向内层"变成测试

新增 `shared/src/jvmTest/.../architecture/ArchitectureTest.kt`（9 个用例），
直接扫描 `shared/src/commonMain` 的**真实源码**按目录分层，而不是靠人自觉：

| 断言 | 规则 | 当前结果 |
|---|---|---|
| domain 不依赖任何外层 | `domain/**` 不 import application / infrastructure / presentation | 0 处（零容忍，无例外） |
| application 不依赖 presentation | 用例层不得认识界面 | 0 处 |
| presentation 只在组合根接触 infrastructure | 界面只认 domain / application | 仅 `AppController.kt`（组合根） |
| application → infrastructure 只能出现在登记表 | 新增必须先进端口 | 4 处已登记（见下） |
| 登记项必须仍然真实存在 | 修好了要删登记，清单不许说谎 | 5/5 仍有效 |
| commonMain 不出现 JVM/Android 专属 API | `java.*` / `javax.*` / `android.*` / `sun.*` | 0 处（122 个 Kotlin 文件） |
| 扫描确实覆盖源码 | 防止路径算错导致"全绿但没检查" | 122 个文件，断言 ≥100 |
| 功能包不互相引用、不碰界面 | `presentation/<功能>/` 只依赖 domain / application 与自己，`presentation` 顶层仅允许常量 | 0 处（12 个功能包） |
| 子控制器不导航 | 功能包里不出现 `Screen` | 0 处（12 个功能包） |

### 顺带修掉的越层

`presentation/screens/ImportScreen.kt` 原来直接 import `infrastructure.importing.ChoiceMapping`
和 `ImportPreview`。这些是**用例的输入/输出契约**，不属于任何具体导入实现，已上移到
`application/port/`：

| 类型 | 新位置 | 理由 |
|---|---|---|
| `ChoiceMapping` | `application/port/ImportOptions.kt` | 界面要传、导入器要读，两边都不该依赖对方的包 |
| `ImportPreview` / `RejectedQuestion` / `ImportParseResult` | `application/port/ImportPreview.kt` | 导入器的输出、界面的输入，是端口契约 |

结果：`presentation` 只剩 `AppController.kt` 一个文件接触 infrastructure，而它正是
`AppDependencies` 挑选具体实现的地方 —— 组合根有且只有一个。

### 没有做的事（登记而不是藏起来）

用例层仍有 4 处直接依赖基础设施，全部写在测试的登记表里，并附原因：

| 文件 | 直接依赖 | 为什么还没端口化 |
|---|---|---|
| `ImportBankUseCase.kt` | `JsonQuestionImporter` / `QbankImporter` / `PureDeflateCodec` | `.qbank` 解析结果（含媒体字节、出处、语言覆盖）还没有 application 侧的返回类型 |
| `ImportExtensionUseCase.kt` | `QbextImporter` / `PackageProbe` | 扩展包只有一种实现，先抽象容易抽错 |
| `ApplyPatchUseCase.kt` | `QbpatchImporter` / `PureDeflateCodec` | 同上 |
| `BackupUseCases.kt` | `BackupArchive` / `BackupBundle` | 备份容器与加密耦合在一起，拆端口要连加密一起改 |

这不是"以后再说"，而是**新增越层会立刻让测试失败**：可以接受旧债，不能偷偷加新债。

## P10-2 发布准备：真实产物

### 图标

`tools/release/make_icons.py` 用代码画图标（无随机、无时间戳，重跑字节一致）：

- 设计：靛蓝渐变底 + 白色答题卡（三条题目占位线）+ 右下角绿色判对徽标，
  配色取自 `presentation/theme/Palette.kt` 的 `HeroStart/HeroEnd` 与 `Correct`；
- Android：5 个密度 × 普通/圆形（10 张 PNG）+ 自适应图标前景（`drawable-xxxhdpi`，432px = 108dp）
  与纯色背景；
- 桌面：`desktopApp/icons/app.ico`（16/24/32/48/64/128/256 七个尺寸）与 512px PNG。

换掉了模板自带的 Android 机器人图标 —— 一个准备发出去的安装包不该顶着框架默认图标。

### 打包元数据

| 项 | 值 | 说明 |
|---|---|---|
| 应用名 | `KnowledgeTrainer` | MSI 安装目录与开始菜单名 |
| 版本 | `1.0.0` | 桌面 `packageVersion` 与 Android `versionName` 一致 |
| 厂商 / 版权 | `morefish` / `© 2026 morefish` | 安装程序的发布者信息 |
| Windows 安装 | 用户目录（`perUserInstall`）、可选目录、开始菜单组、桌面快捷方式 | 不需要管理员权限 |
| 升级标识 | `upgradeUuid = 5ba16a17-…` | **固定值**：MSI 靠它认"同一个产品"，改了等于换产品、无法覆盖升级 |

### 产物（本机真实构建）

| 产物 | 大小 | sha256（前 16 位） | 状态 |
|---|---:|---|---|
| `desktopApp/build/compose/binaries/main/msi/KnowledgeTrainer-1.0.0.msi` | 63.8 MB | `9ae16269b088096f…` | 可安装（描述改英文后重打；MSI 内含时间戳，sha 每次构建都不同） |
| `desktopApp/.../app/KnowledgeTrainer/`（免安装目录） | 129 MB | — | 含 `KnowledgeTrainer.exe` + 精简运行时 |
| `androidApp/build/outputs/apk/debug/androidApp-debug.apk` | 13.6 MB | `e959e5a3629b2e19…` | 可直接装 |
| `androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk` | 9.7 MB | `d657e1e362e4979e…` | **未签名**，需作者 keystore 才能装 |

构建命令（可复现）：

```bash
./gradlew :desktopApp:createDistributable        # 免安装目录
./gradlew :desktopApp:packageMsi                 # 需要 WiX（Compose 插件会自动解压）
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease \
    -Porg.gradle.java.installations.paths=D:/Path/Java/JDK/jdk-25.0.1
```

### 启动实测

`KnowledgeTrainer.exe` 启动 10 秒后进程驻留 334 MB、数据目录 `~/.knowledge-trainer` 正常命中，
随后关闭、无残留进程。Android 侧用 `aapt2 dump badging` 核对：

```
package: name='morefish.knowledge.trainer' versionCode='1' versionName='1.0.0'
application-label:'Knowledge Trainer'
minSdkVersion:'24'  targetSdkVersion:'37'
```

并解包核对 `res/drawable-xxxhdpi-v4/ic_launcher_foreground.png` 与各密度
`ic_launcher.png` 均为新图标（不是模板机器人）。

## P10-3 验收执行（2026-10-28 真实执行）

| 检查 | 命令 | 结果 |
|---|---|---|
| 共享层全量单测 | `./gradlew :shared:jvmTest` | **664 用例 0 失败**（P9 后 601 + 架构 9 + 拆分相关 16 + 移除题库 5 + 重组题 1 + 键盘 15 + 深色主题 17） |
| 十万题基准 | `KT_BENCH=1 ./gradlew :shared:jvmTest --tests "*LargeBankTest*"` | 导入 694ms / 写入 558ms / **重开 3ms** / 读题 416ms，峰值堆 113 MB；加一道题 226ms / 658 B / 7 MB（第 20、21 节，`save` 与 `addQuestion` 各一组） |
| 界面离屏截图 | `KT_SHOTS=1 … --tests "*ScreenshotTest*"` | 74 张，含真实 B 类题库附图与 20 张深色 |
| 坏包回归（工程内） | `python tools/qbank/tests/run_bad_packages.py` | 49 passed / 0 failed |
| 坏包回归（Skill 副本） | `python tools/qbank-authoring-skill/scripts/tests/run_bad_packages.py` | 49 passed / 0 failed |
| Skill 自检 | `python tools/qbank-authoring-skill/scripts/selfcheck.py` | 44 passed / 0 failed |
| 补丁回归 | `python tools/qbank/tests/run_bad_qbpatch.py` | 39 passed / 0 failed |
| 四份真实题库 | `validate_qbank.py validate 业余无线电*.qbank` | A / B / C / 总 四个包全部 OK |

## 本轮发现并修掉的不一致

**制包 Skill 落后于工程内实现。** `tools/qbank/spec` 在 10-21 加了三样东西
（`sources` 来源清单、`origin` 出处性质、`contentDigest` 整包摘要），但
`tools/qbank-authoring-skill/references` 还停在 10-19 的冻结版。后果不是"少了个功能"，
而是**两套校验器对同一个包给相反结论**：manifest schema 是 `additionalProperties: false`，
应用造出来的带出处包会被 Skill 的旧校验器判为 `MANIFEST_INVALID`；作者照着 Skill 写的包
又拿不到应用已经支持的能力。

处理方式：把 `ktqbf` 的校验/打包模块、5 个 Schema 与规范正文同步进 Skill，
坏包用例从 40 条并到 49 条，示例包按新规范重打（`contentDigest` 由打包器写入，
所以 sha256 变了、规范化 digest 没变）：

| 包 | 旧 sha256 | 新 sha256 |
|---|---|---|
| `minimal.qbank` | `0bd3e683…40aaf43a` | `47fd7bbc…0cbab842` |
| `advanced.qbank` | `0e77558f…f105a8ce` | `ab8d0225…561bb380` |

同步后立刻用**四份真实题库**做反向验证（它们由旧工具链生成、体积最大、结构最杂）：
在新校验器下仍全部 `OK`，说明这次同步没有不知不觉收紧规则。

Skill 的 `SKILL.md` 也补上了可选字段说明（`sources` / `origin` / `contentDigest` /
`ordering` 草案），以及"拼错不猜"的枚举规则。

## P10 第二切片（2026-10-28）：把回归交给 CI，开始拆组合根

### 1. CI：清单只维护一份

`.github/workflows/ci.yml` 四个触发面：

| 触发 | 任务 | 内容 |
|---|---|---|
| push / PR（必检） | `toolchain` | `python tools/verify/run_all.py` —— 12 项：坏包 49、扩展包、补丁 39、Skill 自检 44、Skill 坏包 49、可复现构建 ×2、样本包校验 ×6 |
| push / PR（必检） | `jvm-tests` | `./gradlew :shared:jvmTest`，664 用例，失败时上传测试报告 |
| push 到主分支 / 手动 | `screenshots` | `KT_SHOTS=1` 离屏渲染 74 张页面图，核对张数后作为 artifact 上传（见第 19 节） |
| 手动触发 | `android` / `desktop` | debug+release APK、MSI + 免安装目录，产物作为 artifact 上传 |

新增 `tools/verify/run_all.py`：本地与 CI 调**同一个脚本、同一份清单**。在此之前，
坏包、扩展包、补丁、Skill 自检分散在四个目录，靠人记得逐个跑 —— 而"漏跑"和"通过"
在人眼里长得一样。`--list` 可以只列清单不执行。

同时补了一条以前只在文档里声称的性质：**同一份源目录打包两次，字节完全一致**
（`tools/verify/check_reproducible.py`，minimal 与 advanced 各打两次比对 sha256）。
这条一旦破了，`contentDigest` 与补丁锚点就失去意义，因此值得变成断言而不是说明。

### 2. 组合根拆分：第一刀是工具箱

`AppController` 原来 3371 行、约 190 个公开成员，同时干四件事：依赖装配、读模型、
导航、各功能状态。第一刀切的是唯一与题目/题库**零耦合**的部分：

- 新增 `presentation/toolbox/ToolboxController.kt`（186 行）：计算器与单位换算的状态、
  实时求值、留档上限，以及只服务于工具箱的 `ToolboxTab` / `CalcRecord` / `ConvPicker`
  —— 类型跟着使用者走，包边界才对得上；
- `AppController` 只留导航（`openToolbox` / `closeToolbox` 与返回目标）；
- 调用方改动 101 处（`ToolboxScreen` 40、`AppControllerTest` 45、`ScreenshotTest` 16），
  全是 `controller.x` → `controller.toolbox.x`；
- 文件 3371 → 3219 行，608 用例仍全绿，行为未变。

**没有采用"在组合根留同名转发属性"的做法。** 那样能少改 101 处调用，但会把 190 行胶水
留在最该变薄的文件里；拆分的意义是让边界可见，不是让 diff 变小。

后续 10 个关注点的切割顺序、三条硬规则（子控制器互不引用 / 只认 domain 与 application /
每刀测试全绿）与每刀验收清单见 [PLAN-APPCONTROLLER.md](./PLAN-APPCONTROLLER.md)。

### 3. 组合根拆分：第二刀是历史与日历

按计划的顺序切到第 2 项。新增 `presentation/history/HistoryController.kt`（131 行），
搬走历史页的四个筛选条件、两块派生视图（学习日历、错题本）与 `HistoryRange`。

这一块与工具箱的区别是**有外部依赖**：历史列表本身。处理方式有讲究：

- 列表用 `() -> List<AttemptRecord>` 读取器传入，不复制快照 —— 复制一份就会有
  "组合根换了历史、历史页还是旧的"两份真相；
- 日历与错题本反过来必须**显式** `update(history)`（组合根在 `loadAll` /
  `reloadHistory` 之后调用）：它们要遍历全量记录，不能做成每帧重算的 getter。

新增 `HistoryControllerTest`（6 例）钉住两条容易改坏的规则：筛选是**叠加**的；
日历与错题本**不跟着筛选走** —— 切到"只看错题"不该让那天的学习记录从日历上消失。

### 4. 顺手修掉：日期范围其实不是"日历日"

拆的过程中写测试才发现：`HistoryRange` 一直是**滚动窗口**（现在减 N×24 小时），
所以上午 10 点打开"今天"，会把昨天 10 点以后的记录一起算进来。标签写着"今天"，
行为却是"最近 24 小时" —— 与之前修过的"文档承诺、代码没做到"是同一类问题。

修法：`days` 改为**含今天在内的日历日数**，窗口起点取本地零点
（新增 `LocalDates.startOfDayMillis`，用 `atStartOfDayIn(zone)` 而不是
`epochDay * 86_400_000`，后者是 UTC 零点，在东八区会落到当天 08:00）。
`HistoryController` 的时区做成显式构造参数，测试固定 UTC，不跟跑测试的机器走。

| 范围 | 修前 | 修后 |
|---|---|---|
| 今天 | 现在往前 24 小时 | 本地今天 00:00 起 |
| 近 7 天 | 现在往前 168 小时 | 6 天前 00:00 起（含今天共 7 天） |
| 近 30 天 | 现在往前 720 小时 | 29 天前 00:00 起（含今天共 30 天） |

### 5. 组合根拆分：第三刀是练习设置

新增 `presentation/setup/PracticeSetupController.kt`（301 行），搬走抽题范围、题型、标签、
题量、语言、缺译文策略，以及**按语言算出来的那份历史统计**与两类计数缓存。

这一刀第一次真正用上"子控制器不导航"的边界：`start()` 抽题成功后不自己往下走，
而是返回 `SetupStart.Ready(session)` / `SetupStart.Empty(reason)`，由组合根决定
弹哪条横幅、跳哪个页面、什么时候存盘。此前这类"算完了顺手 `screen = ...`"的写法
一旦分散到十个类里，路由就没有单一入口了。

顺带把两处零散的 `setupStatsLocale = ""` 收成 `invalidateStats()`：
历史的失效时机仍由组合根判断，但"哪个字段该清空"不再泄漏到组合根里。

新增用例：整库没有某语言译文时，横幅必须说"这门语言没译文"，而不是含糊的
"没有可练习的题目"——后者会让人去翻题库，实际上只是没翻译。

### 6. 组合根拆分：第四刀是 AI 讲解

新增 `presentation/ai/AiController.kt`（244 行）：接入配置、密钥有无、今日用量、
讲解记录、生成中标记、面板展开与人工审核。

这一刀第一次让子控制器**自己写盘**：配置、密钥、记录、用量是本功能自己的数据。
前几刀的"不写盘"针对的是别人的数据（会话、题库、历史），那些仍归组合根 ——
同一条规则在不同功能上边界不同，照着上一次抄反而会绕开真正的理由。

它仍然不导航、不弹提示：`openAi` / `closeAi` 留在组合根，要说的话通过
`onNotice(text, isError)` 交回去（`text = null` 表示"没话说，顺手收掉上一条"）。
提示做成回调而不是状态，是为了避免"已经换页了、横幅才冒出来"。

当前题目用读取器传入而不是快照。这里的错位代价比前几刀高：缓存键按
题库 + 题目 + 语言 + 修订 + 内容指纹 + 模型算，拿错题就是让用户为上一道题再付一次钱。
新增用例 `explanationDoesNotFollowYouToTheNextQuestion` 把这条钉住。

### 7. 组合根拆分：第五刀是离线手工编辑

新增 `presentation/editor/QuestionEditorController.kt`（108 行）：当前题库、校验结果、
保存成功标记、忙碌标记，以及"校验 → 落盘 → 版本号加一"整条链路。

这一刀的分界线是**什么不该进来**：题干、选项、答案这些表单字段仍留在界面本地状态，
只在点保存那一刻组装成 `Question` 递过来。把每个按键都推成全局状态，会让
"正在输入、还没校验"的中间态被别处读到，而那半个题目算不算数没人说得清。

它写的是题库（别人的数据），所以写完只调 `onBankChanged` 让组合根重读，
不自己 `refresh()`：重读要连带历史、统计、图谱一起换。

新增用例 `manualQuestionWithAnExistingIdIsRejected`：ID 撞车必须拒收而不是覆盖 ——
ID 是进度、复核、错题本匹配题目的唯一依据，撞了就会让两套题面共享同一批作答记录。
另在原有用例里补了 `contentRevision` 必须 +1 的断言。

### 8. 组合根拆分：第六刀是历史复核

新增 `presentation/review/ReviewController.kt`（163 行）：待复核清单、逐条表态、批量表态、
首页徽章数字，以及原始历史与复核决定两份数据本身。

这一刀和前五刀的性质不同：**它搬回了一个原本被写了两遍的规则。**
`attempts` 文件存的是"当时按那时的标准判成了什么"，复核决定另存一处，读取时才合并；
此前那行合并代码在 `loadAll` 与 `reloadHistory` 里各写一次。漏掉任何一处，
重读（恢复备份、装扩展包、手动刷新）之后统计与错题本就会退回旧判定，界面上却看不出区别。
现在只有 `readHistory()` 一处合并。

代价是它也保管了共享内核的数据（原始历史还被引用自检与备份概况读），
所以另给了 `rawAttempts` / `decisionCount` 两个只读出口，把"备份报的是历史文件里有几条"
摆在调用点上，而不是靠记得"这里的 history 是合并后的"。

新增用例 `aFullReloadKeepsTheDecisionAndStillReportsTheRawHistory`：表态后整库重读，
生效判定不能丢、徽章不重新冒出来，而备份概况仍报历史文件的条数。

### 9. 组合根拆分：第七刀是批量队列

新增 `presentation/batch/BatchController.kt`（258 行）：建队列、跑、暂停、取消、删除，
看板上"现在显示哪个任务"，以及把队列项还原成请求的解析。

它是全应用唯一长时间在后台推进的功能，所以拆它的理由是那段**状态机**：
用户点暂停只是写下意图，任务状态由执行器在请求边界上收尾——打断正在飞的请求
同样会扣费，还会留下一条不知道算成功还是失败的记录。这段配合不该埋在三千行里。

这一刀有两处"题库"刻意没有合并：建队列用内存里的（用户看到的那个，报数要对得上），
发请求时重读磁盘上的（暂停期间可能被改过，按新内容生成缓存键才跟着变）。

`refreshAi()` 现在是名副其实的"一次刷新喂两处"，上一刀留下的 TODO 在此兑现，
调用点没有改动。

新增用例 `questionsGoneFromTheBankAreSkippedInsteadOfBeingRequested`：排队之后题库被换掉，
跑起来必须从磁盘重读、找不到就跳过并说明原因，而不是拿内存里那份旧题库去花钱。

### 10. 组合根拆分：第八刀是导入

新增 `presentation/importing/ImportController.kt`（246 行）：粘贴文本与 `.qbank` 包两种材料，
连同"选文件"这个入口、解析预览、行内错误、最近处理的文件名一起搬走。

拆它的理由不是"字段多"，而是**两条输入路径共用一套结论**：文本走宽松导入（允许部分题目
有问题、其余照常导入、要题型映射），包走严格校验（错一条整包拒绝、不做映射）。过程完全不同，
但"导入之后"必须一致 —— 清不清空输入框、预览留不留、重复导入怎么说。以前这套一致性只存在于
一处 `when` 里，改一条分支没人知道另一条还在不在。

三处边界被这刀明确下来：

- **认包留在组合根。** `.qbext` / `.qbpatch` / `.qbk` 分别落到扩展、补丁、备份三个功能上，
  而认包类型要用 `infrastructure` 的探针 —— 子控制器不许碰，所以分发仍在组合根。
- **结论做成返回值 `ImportVerdict`，不是提示。** 题库落库可能让某个"等待题库"的扩展包当场生效，
  而"哪一个生效了"要比对落库前后的扩展清单，那两份清单都只在组合根手里。
- **忙碌标记不进来。** 什么时候算"忙"取决于谁在等这次导入，由调用点决定。

顺手删掉一处死字段：`lastImportNotes` 只写不读，没有任何界面引用它。

调用方 16 行：`ImportScreen` 13 处、`ExtensionsScreen` 3 处，全部是
`controller.importXxx` → `controller.importing.xxx`。`openImport` / `importDroppedFile` /
`pickPackageFile` / `confirmImport` 留在组合根 —— 它们要么导航，要么分发。

新增用例 `importingTheSameTextTwiceCreatesNoCopyAndKeepsThePreview`：同一份材料导两次
不许建副本，第二次仍要把预览留在页面上（清空输入框是为了防止再点一次，但清空预览
就只剩一句错误横幅，看不出这次导进去了什么）。

文件从 2530 行降到 2476 行；619 → 620 用例全绿，行为不变。

### 11. 组合根拆分：第九刀是练习

新增 `presentation/practice/PracticeController.kt`（415 行）：会话、草稿、判题反馈、总结、
草稿存盘，以及"会话文件 + 历史"的合并恢复。

这一刀的特殊之处是**状态会被并发改写两次**：作答在协程里跑，用户的手指同时还在改草稿，
而草稿又要合并存盘。草稿取消防抖、提交前先取消草稿、换题清空输入，这三件事必须写在一起，
否则"提交后草稿又把旧答案写回去"会随机出现且无法复现。

会话恢复的合并规则也跟着搬了：会话文件只存题目与游标，已答数量从历史合并；最后一题交过
但没点"查看总结"的会话实际已做完，要清掉而不是永远挂着。这条规则以前夹在整库重读中间。

边界：不导航（`next()` / `finish()` 只返回"还有没有"，跳哪儿由组合根说）、不写别人的数据
（历史 / 统计经回调交回）、不判硬依赖是否过期（图谱那边算完递进来）。

新增用例 `aSessionWithNothingLeftToAnswerIsClearedInsteadOfOfferedAsResumable`：
最后一题答完却没点总结就退出，下次启动不该出现"继续练习"，而答过的记录仍在历史里。

文件从 2476 行降到 2247 行；620 → 621 用例全绿，行为不变。

### 12. 组合根拆分：第十刀是知识图谱

新增 `presentation/graph/GraphController.kt`（439 行）：图谱本体、与生效扩展包的合并视图、
解析帧、筛选、跨库搜题（含防抖）、编辑草稿，以及保存 / 删除 / 复核三条写路径。

这一刀搬的是**唯一一处"显示的东西不等于存的东西"**：磁盘上只有用户手建的那一份，界面上看到的
却是"手建 + 生效中扩展包"。这层合并以前夹在每次 `refresh()` 里，谁也没法一眼确认"删了包关系
还在不在"。跟着搬的还有快照与新鲜度：关系存的是两端题目的内容版本，题库换一版就必须有人再看一眼，
软件只负责把"已看过"记下来。

边界：导航留在组合根（写盘返回 `GraphWrite`，删除在列表页与编辑页的下一屏不同）、
扩展包的归属判定留在组合根（要查包清单）、题库标题由外部递入（题库缺失时还能借待继续会话的标题）。

新增用例 `deletingAnEdgeFromTheListRefreshesTheListAndStaysThere`：列表页删完留在列表，
且解析帧必须跟着变 —— 模型空了视图没空，列表就会继续显示一行已经不存在的关系。

文件从 2247 行降到 1949 行；621 → 622 用例全绿，行为不变。

### 13. 组合根拆分：第十一刀是备份（计划完成）

新增 `presentation/backup/BackupController.kt`（569 行）：导出表单与三条规则、读入 / 解锁、
待确认状态、恢复、引用自检的结论与时间、上次恢复被打断的那条一次性通知，以及"忙"标记。

这一刀的核心问题是**分层**，不是行数：三个备份用例的返回类型（`BackupBundle`、
`BackupReadResult`）来自 `infrastructure`，而子控制器不许认识 `infrastructure`。
做法是把用例留在组合根、包成 lambda 递进去，并在递进去之前把容器摘成 `PackedBackup` /
`BackupRead` —— "摘字段"本来也只有组合根能做。架构测试的白名单因此仍然只有一条。

边界：导航留在组合根（`openBackup` / `closeBackup` / `confirmRestore` 换屏）、
`loadAll()` 与引用自检留在组合根（恢复之后要重读整个世界）、
一次性通知的"说没说过"留在控制器（横幅位归谁由组合根定）。

`working { }` 收掉四处手写的 busy 标记：打包、解锁、恢复、重读自检都会抛异常，漏写
`finally` 的那处会让界面永远停在"正在恢复…"，而用户唯一能做的就是杀掉应用。

新增用例 `leavingTheBackupPageForgetsTheUnlockedPassphrase`：解锁后不恢复直接返回，
口令、待确认的备份、加密开关必须一起消失。

文件从 1949 行降到 1691 行；622 → 623 用例全绿，行为不变。11 个关注点全部拆完。

### 14. 组合根拆分：第十二刀是个人笔记（计划外补充）

计划表上的 11 项拆完之后回头核账，发现笔记整块还在 `AppController` 里：它有自己的数据
（`NoteRepository`）、自己的两个界面（列表与编辑器）、与题库只按 id 相认 —— 完全符合同一条切割线，
留着的唯一理由是「当初列名单时漏了」。于是补做第 12 项，而不是给它编一个「就该留在组合根」的理由。

新增 `presentation/notes/NoteController.kt`（183 行）：全部笔记、编辑器种子、校验结论、
「已保存」/「保存中」标记、题库筛选，以及保存 / 删除 / 置顶三条写路径。

它值得单独存在的理由是**笔记是唯一丢了就找不回来的东西**：题库来自作者、历史来自作答，
两者都能从别处重算；笔记不行。所以两条规则必须和写盘待在一起 —— 校验 fail-closed
（有 ERROR 就不落盘）、编辑沿用原 `noteId` 与 `createdAt`（否则「编辑」会变成「又多一条」）。

边界与前几刀一致：导航留在组合根（编辑器还要记住「从练习页来的」），删除返回
`NoteDelete.DELETED / NOTHING` 只回结论，换屏与横幅由调用点决定；数据是它自己的，
所以写盘发生在类里（与 AI 讲解同一条边界）。

`noteCountFor` / `saveNote` / `toggleNotePin` / `filterNotesByBank` 四个转发属性没有保留，
调用点直接改成 `controller.notes.xxx`；顺手删掉没有任何调用点的 `notesForBank()`。

新增用例 `deletingANoteAlsoDropsTheEditorSeedSoItCannotComeBack`：删除后种子必须一起清掉。
种子留着，编辑器就还拿着一条已经不存在的笔记，用户再按一次「保存」就会用原 noteId 把它写回来 ——
他看到的是「我明明删了」，磁盘上却是「还在」。

文件从 1691 行降到 1622 行；623 → 624 用例全绿，行为不变。

### 15. 拆分之后补的护栏：把两条硬规则变成断言

十二条切完，`AppController` 的边界靠的是"每次提交都跑全量测试"和人的自觉。但拆分计划开头的
三条硬规则里，前两条（子控制器互不引用、子控制器不碰界面与导航）此前没有任何测试守着 ——
"写在文档里的规则"和"没人检查的规则"是同义词，下一个功能包只要 import 一下邻居就破了。

`ArchitectureTest` 补了两条（共 9 条）：

- **功能包只认 domain 与 application**：自动识别 `presentation` 下"装着 `*Controller.kt`"的目录
  （算出来的，不写死清单 —— 笔记那一刀漏在名单外，正说明名单会漏），要求其中每个文件只 import
  `domain` / `application` / 自己这个包，外加允许 `presentation` 顶层的常量（目前只有 `UI_LOCALE`）。
  `Screen` 也是顶层类型但它就是路由本身，单独排除，否则"引用导航"能借道顶层类型溜进来。
- **功能控制器里没有导航语句**：功能包里不出现 `Screen`（跳过注释行）。

两条都验过"能失败"：临时往 `NoteController` 里塞一个 `graph.GraphController` 的 import 与一行
`Screen.HOME`，两条断言各自失败并指出文件与行号；撤掉后 626 用例全绿。断言里另有
"至少认得出 12 个功能包"，防止路径算错时安静地全绿 —— 这与已有那条"扫描覆盖源码"同一个理由。

### 16. 移除题库：一个能删的东西才算真的装过

P10 交付后回看首页，发现一个功能缺口：题库只能导入，**没有任何入口能把一个库从书架上拿下来**。
导错一个包、或者一份材料已经过时，用户只能去数据目录里手工删文件 —— 而我们的界面从不告诉
他文件在哪。更尴尬的是笔记页早就写了"已删除的题库"这个兜底文案，说明这个概念一直在，只是没实现。

新增三处能力，都是"缺了就做不成"的最小集：

- `TextStorage.delete(name)`（端口）+ 三处实现：桌面与 Android 删文件、删空了把紧邻的那一层目录
  也收掉（`banks/<id>/` 会留下来一堆空目录，打开数据目录看的人会以为里面还有东西）；内存实现删 map 项。
- `BankRepository.delete(bankId)`：**先改目录、再删题目文件**。目录是提交点 —— 反过来的话，
  进程在两步之间被杀，首页会显示"N 题"而点进去一道题都没有；现在最坏只留一个孤儿文件，列表不说谎。
  返回 `false` 表示库里本来就没有它。
- `GraphController.pruneBank(bankId)` / `PracticeController.dropResumableOf(bankId)`：
  各自收拾自己那一摊。前者删掉指向它的手建关系（关系描述的是两段内容之间的**联系**，
  一端没了，联系就没意义；留着只会让图谱页永远挂着"缺失"），后者丢掉挂在它上面的未完成练习
  （否则首页一直挂着"继续练习"，点进去一道题都找不到）。两处都返回"动了几条/有没有动"，
  由组合根写进横幅。

组合根 `deleteBank` 只做编排：题库 → 关系 → 会话 → 媒体字节（`MediaStore.removeAll` 早就为
"题库被外部清空"准备好了）→ 重读 → 横幅。**历史、复核、笔记、收藏一条不动** ——
它们是已经发生的事，删题库不等于删记忆。

界面是二次确认但**不弹模态框**：点「移除题库」原地换成"题目文件会删除，历史与笔记保留"
+ `确认移除` / `取消`，换卡片或离开首页自动退回。删除是唯一不可逆的操作，确认态贴着按钮
比浮一层对话框更难误点，也不会出现"对话框开着、人在看别处"。

新增 5 条用例：移除后历史与笔记仍在（钉住"不级联删记忆"）、指向它的关系一起消失且条数说得出来、
没做完的练习确实不在了（换控制器模拟重启再验一次）、磁盘上本来就没有时不报"已移除"
（对一次没发生的删除说"已移除"是编）、仓储层删完文件真的不在了且删第二次返回 `false`。

631 用例全绿；`:desktopApp:compileKotlin`、`:webApp:compileKotlinWasmJs` 均通过。

### 17. 练习页：修好一个点不到的按钮，然后让它能用手回答

> 补记（做深色时顺手遇上）：`PracticeKeyboardGestureTest` 原来从 `SampleBank` 里**随机抽题**，
> 而示例题库混着简答题 —— 抽到简答时"按 A 选中第一个选项"这件事无从谈起，
> 断言 `options.size >= 2` 就红了。它一直是靠运气绿的：抽样随机，跑十次有两三次能撞上。
> 改成自带三道单选的固定题库。教训：**接线测试的输入必须确定**，
> 需要随机的是产品，不是测试；"偶发失败"从来不是玄学，是有一半的情况没被想到。

这一节是两件事，但它们同源 —— 都出在「界面自己另写一份规则」。

**先是一个真缺陷。** 提交栏自己算「有没有答案」，只认 `selectedOptionIds`；而重组题
（`ORDERING`）的作答存在 `orderedOptionIds` 里。于是：领域层判得出次序、`submit()` 也提交得了、
`GradingTest` 里那几个重组题用例全绿 —— 但用户面前那个「提交作答」按钮永远是灰的。
判题策略齐全、界面点不到，这条题型从上线起就没法真正做完一道。

修法不是把界面的判断改对，而是**让它没有第二份可写**：`PracticeController.currentAnswerDraft()`
把草稿折成 `AnswerInput`（`submit()` 本来就在做同一件事，现在它也走这一个），
`canSubmitCurrent` 读它的 `isEmpty`。提交按钮、回车键、`submit()` 从此共用一条判定。
新增用例钉住：排一段片段按钮就该亮；排完三段提交得到 `CORRECT`，次序原样进历史 ——
把 `canSubmitCurrent` 换回旧规则，这条用例会失败（验过）。

**然后是键盘。** 这是一个训练器：一天几百次「看题 → 选 → 提交 → 下一题」，
每一次都要求手离开键盘去够鼠标，等于把成本加在最热的路径上。而此前整个界面
一个 `KeyEvent` 都没有。

| 按键 | 行为 |
|---|---|
| `A`–`Z` / `1`–`9` | 选中屏幕上对应位置的选项（字母与数字同义） |
| `Enter` | 未提交时提交；已判完时继续下一题（最后一题去总结） |
| `Ctrl/Cmd + Enter` | 简答题提交（裸回车留给换行：逐字符判定的答案可能有多行） |
| 带 `Ctrl`/`Alt`/`Cmd` | 一律不管 —— 剪贴板、输入法、浏览器快捷键照常 |

规则放在 `presentation/practice/PracticeKeyboard.kt`：一个 `translate(keystroke, context)`
的纯函数，不碰 Compose，所以能被单测钉住 —— **重点是「什么时候不许管」**：
简答题里按 `2` 是打字，回车是换行，`Ctrl+C` 是复制。抢错一个，用户会觉得输入框坏了，
而这种坏法在界面上点一遍发现不了。12 条用例全是这一类。

界面这一侧只剩接线：整页挂 `focusRequester + focusable + onPreviewKeyEvent`，
点过按钮之后事件仍然从这一层过一道 preview（否则「点一下就再也按不动键盘」）。
**这一半只靠编译看不出来**：修饰符少一个 `focusable()`，整页看着完全正常，键盘悄悄地什么都不做。
所以另补 3 条接线测试（`PracticeKeyboardGestureTest`）：用离屏场景真的渲染练习页、
真的往里发按键，断言「按 A 选中第一个选项 / 按 2 选中第二个 / 按 Enter 提交并落到总结」，
以及「简答题里按 A 不会被抢走」。用的是 `ScreenshotTest` 同一套 Skia 栈，不需要显示器。

提示只在有物理键盘的设备上显示（`Platform.hasPhysicalKeyboard`，Android / iOS 为 false）：
手机上写「按 A 选择」，只会让人到处找按键。

664 用例全绿；`:desktopApp:compileKotlin`、`:webApp:compileKotlinWasmJs`、
`:androidApp:compileDebugKotlin` 均通过。

### 18. 深色主题：把「只有浅色」这条限制消掉

首页右上角多了一个胶囊 —— `◐ 跟随系统 / 浅色 / 深色`，点一下换下一个，选完立刻写盘。

| 项 | 结果 |
|---|---|
| 令牌 | `PaletteScheme`（33 项）一套，`LightScheme` / `DarkScheme` 各一份；`Palette` 变成"读当前方案"的转发层，825 处调用点一行没改 |
| 主色 | 拆成两个：`Accent`（当底、上面压白字）与 `AccentInk`（当字：链接、图标、胶囊文字） |
| 语义色 | 深色下 `Correct/Wrong/Pending` 换成亮色、`*Soft` 换成暗底，仍是"深底浅字"的同一种关系 |
| 硬编码清零 | Hero 的浅紫、题库头像四组渐变、日历四档热度、顶部晕染、进度条渐变全部收进令牌；`theme/` 之外已搜不到 `Color(0x` |
| 持久化 | 新增 `application/port/Preferences.kt` + `JsonPreferencesRepository`（`prefs-v1.json`）。放在数据目录里，于是天然进 `.qbk` 备份 |
| 切换 | `AppContent` 用 `key(dark)` 包住整棵树 |
| 用例 | 647 → **664**（对比度 4 / 控制器 8 / 接线 1 / 深色截图 2 / 深色像素护栏 1 / 防手滑扫描 1） |

三条值得记下来的：

1. **深色不是把浅色调暗。** 第一版直接让 `Accent` 既当底又当字，结果是练习页的
   「关联 / 工具 / 笔记」和顶栏胶囊糊在黑底上 —— `#4A3ECB` 压在纸上 7.4:1，压在墨夜上只有 2.3:1。
   数学上这两个用途的区间不相交（白字要求它暗，当字要求它亮），所以主色必须分叉。
2. **Hero 那颗白底按钮的字反而要固定。** 它是全页唯一不随主题变的底色（白），
   `AccentDeep` 在深色下变亮之后，白底上的字直接消失。新增 `HeroActionInk` 恒为深靛蓝，
   并由对比度断言钉住 —— 这是本次唯一一处"看着没问题、把截图放大才发现"的缺陷。
3. **`key(dark)` 是保险，不是必需。** 把 `key` 拿掉再跑接线用例仍然全绿：`KtTheme` 换掉色方案后，
   Material 的 CompositionLocal 已经把失效一路传了下去。注释与用例里都如实写了这一点；
   保留 `key` 是因为"整屏跟着换"该由结构保证，而不是依赖运行时的传播细节。

还有一条**防手滑**的源码扫描（`AccentInkUsageTest`）：`AccentInk` 一旦出现在
`background(...)`、`bg =`、`containerColor =` 这些"这是块底色"的位置就报错。
起因是本次真的写错过一次 —— 练习页选项左侧的序号徽标用 `AccentInk` 当底、
上面压着 `OnAccent`，深色下就是白底白字。类型系统看不出两种 `Color` 的区别，
所以留一条一句话的规则。

4. **一个没有字的方块，边就是它的全部。** 题目编辑页「标出正确答案」的 32dp 勾选框，
   深色下差点看不见：它用分隔线色（`#332E24`）当边，压在自己那块底（`#262218`）上只有 1.26:1。
   把截图放大才确认。改成 `Muted` 当边（浅 5.2 / 深 6.9，与 Material 自己的未选中勾选框一致），
   并把 `muted/panelMuted` 加进对比度清单 —— 这条规则不只管这一个方块。

**这道护栏现在有自动化的一半了**：`DarkSurfaceTest`（每次 `:shared:jvmTest` 都跑）
把练习、导入、历史三页渲染出来，逐 8×8 小块看"是不是整块都亮"——文字的笔画夹着深底，
永远过不了这一关，漏掉令牌的白卡片则整块都是亮的。判据的两次修正很值得记：
按"平均亮度"判定时，大号粗体白字能把均值抬到 0.61，误报；按"块内最小亮度"才对。
另外"这是不是深色页"不能看左上角像素——顶栏自己就是一块纸面，被改成白之后
反倒会被当成浅色页跳过；改成读 `Palette.current`。护栏验证过：把深色的 `Panel` 改亮，
它立刻报"5838 个 8×8 小块整块都是亮的，最亮的一块在 (0,0)"。

深色**逐张看过**的截图（20 张全部拍，20 张全部看过）：
`55-home-dark`、`56-practice-feedback-dark`、`57-home-dark-phone`（420dp 手机宽度，胶囊不挤标题）、
`58-history-dark`（判定色同框）、`59-toolbox-dark`（计算器键盘）、`60-import-dark`、
`61-question-editor-dark`（第 4 条就是在这里发现的）、`62-notes-dark`、`63-note-editor-dark`、
`64-graph-dark`、`65-extensions-dark`、`66-ai-dark`、`67-backup-dark`（空状态）、
`68-wrong-dark`、`69-summary-dark`、`70-standard-dark`、
`71-backup-overview-dark`、`72-backup-encrypt-dark`、`73-backup-exported-dark`、`74-backup-restore-dark`。
最后补看的两张（`63-note-editor-dark`、`68-wrong-dark`）都没问题：卡片比底色亮一档、边线看得见，
错题本右上角的「错误 / 部分正确」在墨底上清楚可读 —— 这两个颜色本来就在 4.5:1 的断言清单里
（`wrong/panel`、`pending/panel`），图与断言对得上，不是"看着还行"。

**备份主界面那份债已经还了**：`renderBackup()` 的准备代码抽成 `backupFixture()`（真实临时目录 +
一个题库 + 一条历史），浅色与深色共用同一份 —— 这一页的每句话都挂在真实数字上（几个题库、
多少文件、多大），假数据拍出来没有核对价值。新增 `renderBackupDark`：总览、加密口令、刚导出、
恢复预览四张。顺带发现的坑：加密开着的时候导出卡片高出一大截，恢复预览被推到画面外面 ——
所以恢复那张先把开关关掉再导出（与浅色那套的顺序一致）。

## 19. 截图进 CI：先证明它真的会拍，再证明它拍了 74 张

> 现在是 76 张：2026-10-29 补了 Skill 页的浅色与深色两张（见第 23.3 节），`EXPECTED_SHOTS` 同步改了。

上一节留下的空缺是：74 张图与那条"深色页某块还是浅色"的像素断言只在本地生效，
因为 `ScreenshotTest` 要 `KT_SHOTS=1` 才渲染，而 CI 上没人设这个变量。补法是加一个
`screenshots` job（push 到主分支与手动触发时跑），`KT_SHOTS=1` 渲染完把
`shared/build/ui-shots` 作为 artifact 上传。

不难，但"跑起来了"和"跑对了"是两件事；这个 job 最可能的失败方式是**绿着、图一张没有**。
写之前先把三种"静默空跑"逐一验过（本机真实执行，Windows + 同一个 Gradle daemon）：

| 情形 | 命令 | 结果 |
|---|---|---|
| 图目录被删后重跑 | `KT_SHOTS=1 ./gradlew :shared:jvmTest --tests "*ScreenshotTest*"` | `:shared:jvmTest` **UP-TO-DATE**，**0 张**，退出码 0 |
| 测试结果命中构建缓存 | 同上 + `cleanJvmTest` | `:shared:jvmTest` **FROM-CACHE**，**0 张**，退出码 0 |
| 强制重跑、绕过缓存 | 同上 + `--rerun-tasks --no-build-cache` | **74 张**，用时 80s（含编译） |

第二种是 CI 上真会发生的那种：`gradle/actions/setup-gradle` 默认恢复上一次的构建缓存，
`jvmTest` 一旦 `FROM-CACHE`，用例"通过"了、报告也有，就是没有图。所以渲染那一步固定带
`--rerun-tasks --no-build-cache`。

顺带把另一个猜测验掉了：**`KT_SHOTS` 不需要像 `KT_BENCH` 那样在构建脚本里转发**。
同一个 daemon 下，设变量 74 张、不设 0 张 —— 变量是按每次调用进测试进程的。
`build.gradle.kts` 里 `KT_BENCH` 那行没动：它读的是**构建脚本**里的 `System.getenv`
（跑在 daemon 内），与测试进程自己读环境是两条路，这一轮没测它，就不顺手改。

**用例数会骗人，文件数不会**。所以渲染之后单独一步核对张数，并且卡死 74：
`--tests` 写错、变量没生效、缓存命中，这三种都会让"一张图都没有"的 job 变成绿的。
三个分支在临时目录里都演练过（0 张 → 报错退出 1；73 张 → 报"期望 74"退出 1；74 张 → 通过），
张数与文件清单同时写进 job summary，点开就能看见拍了哪 74 张。这个数字写在
`ci.yml` 的 `EXPECTED_SHOTS` 里，新增截图时要一起改 —— 宁可让人改一个数字，
也不要让"少拍了一半"安静地过去。

两样渲染前提写进了 job：`libgl1`（skiko 的 Linux 原生库会 dlopen `libGL.so.1`）与
`fonts-noto-cjk`（界面全是中文，缺字体拍出来是豆腐块，那样的产物等于没拍）。

### 真跑了两轮：首轮三个 job 全红，两个根因都不是 CI 配置

这台机器一直没推过（main 领先 27 个提交），所以 2026-10-28 这次是**工作流第一次在真实 runner 上跑**。
首轮 `40670881743`：`toolchain`、`jvm-tests`、`screenshots` 三个全红。两个根因都在仓库里，不在 YAML 里：

**① `./gradlew: Permission denied`（exit 126）—— 三个 job 同一个死法。**
索引里 `gradlew` 是 `100644`：在 Windows 上提交时从来没带过 +x，本地一直是靠 shell 忽略权限位跑起来的，
所以"本地能跑"这件事完全没暴露它。`git update-index --chmod=+x gradlew` 补回。
顺带确认了那批 Python 脚本不需要：`selfcheck.py` 的 `run()` 统一用 `[sys.executable] + args` 起子进程。

**② `随附包与现场打包一致：advanced.qbank` 在 Linux 上必红，而且只有 advanced 一条。**
本机 `core.autocrlf=true`，`advanced-authoring/media/index.ndjson` 被检出成 CRLF（索引里是 LF），
用它打出来的 `.qbank` 里就带着 `\r\n`。于是 Windows 上"随附包 == 现场打包"永远成立（两边都是 CRLF），
Linux 上用 LF 重建必然不同；minimal 没有 `media/`，所以它是绿的 —— 这也解释了为什么只失败一条。
修法不是在 CI 里迁就，而是**把换行规则写进仓库**：`.gitattributes`（`* text=auto eol=lf`，
`.qbank/.apk/.msi/.ico/.png/.jpg` 标 binary，`gradlew.bat` 留 CRLF），再把工作区里 55 个被 autocrlf
改过的文本文件按 LF 重检，最后用 LF 源重打两个随附包并重出校验报告（advanced 的 digest 与内容摘要
随之变化，这是预期的）。

修完第二轮 `40672228922`：`toolchain`、`jvm-tests`、`screenshots` **三个全绿**，截图 job 用时 4 分 17 秒，
`核对张数` 那一步报的是 74/74。

**③ 手动触发后还发现第三个：Windows 桌面打包 `light.exe` 退 311（LGHT0311）。**
这一条差点漏过去 —— Gradle 对 jpackage 的失败只说 `External tool execution failed / Exit code: 1`，
真正的错因在 `desktopApp/build/compose/logs/packageMsi/jpackage-*-err.txt` 里，而它没有被上传。
先补了"失败时上传 jpackage 日志"这一步，再跑一次才看到：`light.exe` 拒收一句字符串，
因为它装不进 MSI 数据库的码页。那句字符串就是 `--description "离线优先的知识训练工具：…"`。
MSI 的码页跟着**构建机的 locale** 走（本机 zh-CN → 936，中文装得下；runner 是 en-US → 1252，中文装不下），
所以这不是 CI 的问题 —— 任何英文 Windows 上都打不出这个包。`description` 改成英文，
中文说明留在应用内与 README（要中文安装包属性得自定义 WiX 资源目录把 `Codepage` 设成 936，没做）。

三个根因都修完之后，手动触发的 `40674749451` **五个 job 全绿**：
`toolchain`、`jvm-tests`、`screenshots`、`android`（4m21s，首次在 runner 上打出 APK）、
`desktop`（首次在 en-US runner 上打出 MSI，63.9 MiB）。产物都下载核对过：
MSI 在 `msi/KnowledgeTrainer-1.0.0.msi`，截图 74 张齐全。

三次失败的共同点值得记一句：**它们都不是"CI 配置写错了"，而是"只有换一台机器才会暴露的假设"** ——
文件权限位、换行符、构建机 locale。本机能跑这件事，在这三处全都没有证明力。

### 绿灯之后还得把图下载下来看

"张数对"只证明拍了 74 个文件，不证明这些文件能看 —— 缺 CJK 字体时文字会变成豆腐块，
而**没有任何断言会因此失败**（像素护栏只看"有没有整块发亮"，豆腐块是浅色的规整方块，反而过得更轻松）。
所以把 artifact 下载回来逐张抽查了：`01-home`（浅色首页）与 `58-history-dark`（深色历史）
中文都是正常字形，深色页墨底浅字、判定色清楚，与本地渲染一致。

一处**必须写下来的差别**：拉丁文字的字体不一样。Linux 上没有 Windows 那套字体，
fontconfig 做了替换，标题 "Knowledge Trainer" 与正文里的 "SQLite"、"Kotlin" 明显更粗更方。
所以 CI 的图适合核对**版面、间距、配色、溢出**，不适合核对字重与字形细节；
要判断"字看起来对不对"仍然得在本机看。

## 20. 存储基线：SQLite 开工前先量清楚"慢在哪"（2026-10-29）

P11 的 SQLite 是唯一一处"换实现要跨平台分叉"的地方（Web 没有可用驱动），
按主提示词第 10 节的要求，性能要用**量化指标**说话，而不是"换完更快了"。
所以动端口之前先补一组对照：改完之后如果取一道题还是两百毫秒，这次改造就没有意义。

新增 `LargeBankTest.addingOneQuestionToLargeBankReadsAndRewritesEverything`。它刻意不走合成路径，
走的是用户点「保存」的那条：`QuestionEditorController.add()` = `find(bankId)` → `bank.questions + question` → `save(...)`。
十万题、三次采样取中位数、每步 `System.gc()` 后量驻留堆，**不写死耗时上限**（机器差异太大，
写一个数只会变成"改大让它过"）。机器：Ryzen 9 7940HX / 15.3 GB / Win11 / Zulu JDK 21 —— 数字只作量级参考。

**整库操作**（`KT_BENCH=1`，三次中位数）

| 阶段 | 耗时 | 数据量 | 驻留堆 |
|---|---|---|---|
| 导入解析（NDJSON → 领域对象） | 694ms | 19.7 MB | 113 MB |
| 写入题库（全量序列化 + 换名提交） | 876ms | 49.9 MB | 113 MB |
| 重新打开（只读目录） | 3ms | 0 KB | 7 MB |
| 取题面（触发整库解析） | 804ms | 49.9 MB | 103 MB |

**日常操作**（同一个库，"加一道题"的拆解）

| 阶段 | 耗时 | 读 / 写 | 驻留堆 |
|---|---|---|---|
| 精确取一道题（`locate`，读整份文件再扫到该行） | 292ms | 读 49.9 MB | 7 MB |
| 搜题干（全表扫描，命中在文件末尾） | 1289ms | 读 49.9 MB | 7 MB |
| 找到题库（只读目录） | 3ms | 0 KB | 7 MB |
| 读回整库（加一题必须先全读） | 508ms | 读 49.9 MB | 104 MB |
| 重算整库指纹（`save` 内部还要做一遍） | 200ms | 49.9 MB | 104 MB |
| 重写整库（新增一行 → 整个文件） | 470ms | 写 49.9 MB | 106 MB |

一句话读法：**在十万题的库上加一道题 ≈ 1.0 秒、读写各 49.9 MB、峰值 106 MB**，
而它实际新增的内容是 512 字节 —— 为一行重写了十万倍的数据。

这组数字对 P11 的四个含义：

1. **"换 SQLite"不是启动优化。** 打开首页已经是 3ms / 7MB（目录与题目分离第一版就做了），
   换库在这里大概率**更慢**（建连接、查两张表）。收益不在这条路上，别拿它当理由。
2. **取一道题是最大的单点收益。** 292ms 的全部成本是"读 49.9MB 再逐行扫"，而按主键查应当是
   1~5ms 量级。图谱刷新、搜索结果点进去、错题定位、反馈草稿都走这条。
3. **搜一个词要 1.3 秒**，因为它是纯全表扫描；这是第二大的点，SQLite 上一条 `LIKE`/FTS 就能到几十毫秒。
4. **整库指纹是隐形天花板。** 见下节：为了不让目录说谎，写库要把整库内容再哈希一遍（200ms）。
   SQLite 只写一行也躲不掉它 —— 想让"改一道题"真的变成 O(1)，得把整库指纹换成增量结构，
   或改成"写入时不维护、需要时才算"。这是格式层面的决定，不在存储层里。
   **已在第 21 节做掉**：换成可增量的多重集指纹，「加一道题」现在只写 658 字节。

### 顺手修掉的一个真缺陷：目录里的指纹会说谎

这条基线第一次跑就是红的，红的不是耗时而是断言：

```
目录里的指纹必须等于实际内容的指纹 expected:<38f31380321e3299> but was:<4e83d3752d94f986>
```

根因：`QuestionBank.copy(questions = …)` 会**原样保留**旧的 `fingerprint` 字段，
而 `JsonBankRepository.save()` 又照抄调用方给的指纹写进目录。于是"手工加了一道题"之后，
目录里写的还是老内容的指纹。看起来只是字段不同步，实际后果在导入侧：

- `ImportBankUseCase` 先按 `bankId` 找到库，再**用指纹判断"内容是不是一样"**；
  指纹停在旧值时，改过的库会被判成"这个包已经导入过"（`Duplicate`），而不是"同一个库的不同版本"（`IdentityConflict`）；
- `findByFingerprint()` 也因此会把本不该算重复的情形判成重复。

修法是交给唯一看得见全部题目的那一层：`writeQuestionsLocked()` 在写的同时算指纹，
返回 `WrittenQuestions(count, typeCounts, fingerprint)`，`save()` 与旧布局迁移都用它，
**不再信任调用方传进来的值**。代价是写入从 789ms 涨到 876ms（中位数，约 +11%）—— 已计入上表。

守卫加在两处：`PersistenceTest.addingAQuestionUpdatesCatalogCounts`（快，日常跑）、
`LargeBankTest` 十万题那条（`KT_BENCH=1` 才跑十万）。两条断言在改之前都是红的。

## 21. 「加一道题」不再重写整个题库（2026-10-29）

第 20 节第 4 条点出的天花板是：目录里的指纹必须等于实际内容，而算它要看见全部题目，
所以"加一行"永远要付出"整库读 + 整库写"。这一节把它拆掉。

### 先说结论

| | 整库重写（`save`） | 追加（`addQuestion`） |
|---|---|---|
| 耗时（十万题） | ≈0.83s（找到 4 + 读回 373 + 算指纹 93 + 重写 363） | **226ms** |
| 写盘 | **49.9 MB** | **658 B** |
| 峰值驻留堆 | **106 MB** | **7 MB** |
| 风险面 | 整份文件重写一遍 | 只追加一行 + 更新目录 |

658 B 里约 220 B 是题目那一行，其余是该库的目录条目（题数、题型分布、覆盖度、指纹）。
两者都与**库有多大无关** —— 这正是原先缺的那条性质。

### 做法

1. **端口长出一等操作**：`BankRepository.addQuestion(bankId, question): AddQuestionResult`。
   契约写在接口上（目录四件套必须等于实际内容、内容版本加一、不碰别的库、id 重复时什么都不写），
   默认实现就是"读出来 copy 一份再 save"——语义最直白的那个，任何存储实现都可以先继承它。
2. **指纹换成可增量的多重集指纹**：`Fingerprint.ofQuestions` 从"排序后拼接再哈希"改成
   "逐项相加（模 2^64）"。两者都与顺序无关，但只有相加能 O(1) 更新：
   加一道题就是 `旧值 + 新题指纹`。目录里同时记 `fingerprintVersion`，缺省 1 = 首版算法。
3. **文件实现只在"能增量"时增量**：`JsonBankRepository` 覆盖了 `addQuestion`。
   库在不在、id 重不重都只看目录与一次文本扫描，不解析整库；
   命中"首版指纹"的老库则走一次整库重写，顺便把目录升到新版 —— 那是升级成本，不是日常成本。
4. **旧值不被误判**：`findByFingerprint` 未命中时，对首版算法的库按内容重算一遍再比；
   `ImportBankUseCase` 同理（先比存储值，只有版本落后才重算）。

### 代价与边界（别把它读成"存储层已经行了"）

- **226ms 里绝大部分还是"读整份 49.9MB"**：为了确认 id 不重复，得把题目文件读成字符串扫一遍。
  这是唯一剩下的全量读，靠端口解决不了 —— 那是"按主键查"的事，也就是第 20 节第 2 条留给 SQLite 的。
  它随库大小线性增长，而手工加题的库通常远小于十万题。
- **只做"加"**：改题、删题仍是 `save`（整库重写）。`.qbpatch` 那种批量变更是另一条路，没有动。
- **指纹换了算法，这是破坏性变更**：`baseFingerprint` 是 `.qbpatch` 的公开锚点，Python 制包工具
  与应用端必须同时换。这一刀把 `tools/qbank/ktqbf/fingerprint.py` 与制包 Skill 里那份一起改了，
  重出了 `examples/starter-v2.qbpatch`（`827015e596399cd3`），并更新两侧 golden。
  **升级前生成的补丁在新版本上会因为指纹对不上而报"版本不符"** —— 应用未发布，接受这个代价；
  老目录里按首版算法写下的值仍然认得出来（见上面第 4 条）。
- 顺带的好处：`save()` 不再为了算指纹去排序十万个字符串，写入从 876ms 降到 558ms
  （机器有波动，方向明确）。

### 守卫（都验过会红）

| 用例 | 守什么 | 注入什么会让它红 |
|---|---|---|
| `PersistenceTest.addQuestionLeavesCatalogEqualToContent` | 增量算出的指纹/题数/分布/覆盖度等于实际内容 | 把 `Fingerprint.plus(...)` 换成 `meta.fingerprint` → `expected:<aaa4eb66…> but was:<3e07ee84…>` |
| `PersistenceTest.addQuestionGrowsLocaleCoverageTheSameWayAFullRewriteWould` | 覆盖度增量与整库重算等价（新题没英文时，英文 covered 不该涨、total 要涨） | 不更新 `locales` |
| `PersistenceTest.addQuestionOnALegacyCatalogUpgradesTheFingerprintOnce` | 老库第一次追加会升级指纹版本，之后不再整库重写 | 不做版本判断 |
| `LargeBankTest.appendingOneQuestionWritesOneLineNotTheWholeBank` | **写盘字节数**与库大小无关 | 强制走整库重写 → `实际写了 12775325 字节（整个库才 2553893 字节）` |
| `FingerprintParityTest` | Python 与 Kotlin 的指纹算法不漂移 | 只改一侧 → 立刻红（这次就是它先抓住的） |

最后一条值得单说：改 `ofQuestions` 之后先跑全量，**17 个用例变红**，头两条就是
`FingerprintParityTest`。如果只有 Kotlin 侧改，作者那边打出来的补丁在用户机上会全部"版本不符"，
而作者本地一切正常 —— 这条守卫存在的意义就在这儿。

## 22. 安装包验收：装 → 升 → 卸 真跑了一遍（2026-10-29）

P10 自 10-28 起挂着一条"MSI 没装过"。这一节把它做掉了，用的不是 CI 里那份产物，
而是**当前 HEAD（`1a62053`）在本机重打的包**——CI 那份是三天前的代码，装它只能证明"安装器能用"，
证明不了"现在的代码装出来能用"。

### 22.1 产物

| 项 | 值 |
|---|---|
| 命令 | `./gradlew :desktopApp:packageMsi`（无需预装 WiX：Compose 插件有 `unzipWix` 任务自己拉） |
| 文件 | `desktopApp/build/compose/binaries/main/msi/KnowledgeTrainer-1.0.0.msi` |
| 大小 | 66,998,500 字节（63.9 MiB） |
| SHA-256 | `56a616465837f56f4643bafbbd29453cc957cbb6f405702d26b06d527db6aef5`（MSI 内含时间戳，每次构建都不同，只能对"这一次"对账） |

顺带纠正一条之前的猜测：本机 `C:\Program Files` 与 `Program Files (x86)` 里都没有 WiX，
`light.exe` 也不在 `PATH` 上，但打包照样成功 —— 因为插件自己把 WiX 解到 `build/wix` 再用。
（我先后下载了一份便携版 WiX 想手动挂上去，最后没用上，已删。）

### 22.2 安装

`msiexec /i … /qn /norestart` → **exit 0**，无交互、无提权提示。

| 检查项 | 结果 |
|---|---|
| 安装位置 | `%LOCALAPPDATA%\KnowledgeTrainer`（即 `C:\Users\…\AppData\Local\KnowledgeTrainer`） |
| 文件数 / 体积 | 206 个 / 127.8 MB（含精简运行时与 `app\*.jar`） |
| 开始菜单 | 1 项（`Programs\Knowledge Trainer\KnowledgeTrainer.lnk`，按 `menuGroup` 建组） |
| 桌面快捷方式 | 1 个 |
| 卸载注册项 | 1 条，`DisplayName=KnowledgeTrainer`、`DisplayVersion=1.0.0` |

**一个观察，不下结论**：`perUserInstall = true` 确实把程序装进了用户目录（所以不需要管理员），
但卸载项写在 `HKLM` 而不是 `HKCU`。也就是说"用户级安装"只体现在目录与权限上，注册项仍在机器级。
这是 jpackage 的 MSI 模板行为，没有深挖；记在这里是因为它会影响"多用户机器上每个用户各装一份"这种场景。

### 22.3 启动：窗口是真的建出来了

这条差点被自己的诊断方法骗过去：

1. `Start-Process` 拿到的 pid 抓栈报 **`jvm.dll not loaded by target process`**，线程才 5 个；
2. 于是怀疑它卡在 JVM 加载之前。

实际上 **jpackage 的 `KnowledgeTrainer.exe` 只是个启动器**，它把真正的 JVM 起在**子进程**里。
抓到的是启动器的栈，当然没有 `jvm.dll`。看进程树就清楚了：

| 进程 | 线程 | 常驻 | 说明 |
|---|---|---|---|
| 启动器（父） | 5 | 11–12 MB | 只负责装载运行时时序 |
| 应用（子，JVM） | **136–138** | **286–305 MB** | `MainWindowTitle = "Knowledge Trainer"`，窗口句柄非零（例：`364577632`） |

子进程稳定存活 20s 与 50s 各一次，没有崩溃退出、没有重启循环。
（对照实验也踩了个坑：拿 `notepad.exe` 当基准是错的 —— Win11 的记事本是 Store 应用，
UI 在另一个进程里，所以它的 `MainWindowHandle` 恒为 0。别拿它当"这个环境不能开窗"的证据。）

**没验到的**：窗口里的像素。本会话用 `Add-Type` 做 `PrintWindow` 截图被安全策略拦掉，
所以"画面画对了"这一条仍由 CI 的 74 张离屏截图（第 19 节）负责，不是靠这次安装证明的。

### 22.4 升级：`upgradeUuid` 那层保证真的成立

这是文档里一直标着"只有 `upgradeUuid` 这一层保证"的地方，这次实测了：

1. 装 1.0.0 → 卸载项 `1.0.0`；
2. 临时把 `packageVersion` 改成 `1.0.1` 打第二个包（改完立刻改回，工作区没有留下改动）；
3. 拿 1.0.1 直接覆盖装 → **exit 0**。

结果：卸载项变成 `1.0.1`，**只有一条**（不是并存两个产品）；安装目录不变；
桌面快捷方式仍是 1 个、开始菜单仍是一组（没有出现 `KnowledgeTrainer (1)` 这种）；
升级后再启动，窗口标题与线程数（136 / 286 MB）与升级前一致。

### 22.5 卸载：清得干净，且不碰用户数据

`msiexec /x … /qn` → **exit 0**。

| 检查项 | 卸载后 |
|---|---|
| 安装目录 | 整个消失（没有留空壳） |
| 桌面 / 开始菜单快捷方式 | 0 / 0 |
| 卸载注册项 | 0 |
| `~/.knowledge-trainer`（用户数据） | **33 个文件，长度与 mtime 逐条未变** |

最后一行的口径：`main.kt` 把数据根写死在 `user.home` 下，所以验收时用
`JAVA_TOOL_OPTIONS=-Duser.home=<临时目录>` 把它引开，**全程没让安装版碰过真实数据**；
即便如此，安装/卸载本身也没动它 —— 两次（装 1.0.0 后卸、升级到 1.0.1 后卸）都核对了这一条。
"卸载不删用户数据"是设计（删题库不等于删记忆），这次是第一次真的验到。

### 22.6 这一节仍然没解决的

- **界面串没在安装版上点过**：导入 → 练习 → 错题 → 移库这一串，需要的是一块能看见、能点的桌面，
  本会话只能证明"窗口存在、进程稳定"。这条继续挂在下面的限制表里；
- **APK 仍未上真机**：没有设备，只能做到编译 + 打包 + 清单/资源核对；
- **只在装了这套 .NET/Windows 组件的本机跑过**：没在干净虚拟机里从零走一遍。

## 23. 应用内的说明也得是真的（2026-10-29）

这一节没有性能数字，修的是"应用自己说的话"。

### 23.1 页面上写着"未实现"，其实早就做完了

「题库标准」页底部有一块「当前实现到哪一步」。它落后了整整三个阶段：

| 页面上写的 | 实际状态 |
|---|---|
| 「尚未实现：媒体在题目里渲染」 | 练习页 / 复核页都在画（`QuestionFigure`，取不到媒体时显示占位） |
| 「尚未实现：媒体区渲染、.qbext 扩展、.qbpatch 补丁、.qbk 加密备份」 | 三样都有完整流程与用例（扩展包、补丁、加密备份各自的 FlowTest） |

对用户说"没做"和"说做了其实是空的"是同一件事的两个方向，而前者更难被发现 ——
没人会去质疑一句"还没做"。改法不是把那三行删掉，而是：

- 清单改成逐条"已实现 / 尚未实现"，并补上真正的缺口（包内 `enhancements` 目前只做结构与审核状态校验，
  练习里还不消费它）；
- 加 `BankStandardDocsTest`（3 条）盯着两个方向：`PackageFormat` 里登记了的格式必须在清单里
  有一条"已实现"讲到它；已经做完的能力不许再出现在"尚未实现"里。
  注入一条错误声明后，两条守卫分别按"该有却没有"和"不该有却有"变红，然后把文案还原。

### 23.2 Skill 清单漏了唯一有实现的那一层

「AI 制包 Skill」页列出"必须一起提供的文件"。清单里有 `scripts/build_qbank.py` 与
`scripts/validate_qbank.py`，但没有 `scripts/ktqbf/` —— 而这两个脚本各自只有一行：

```python
from ktqbf.builder import main
```

照着清单挑文件的人，会拿到两个一跑就 `ModuleNotFoundError` 的壳。现象是"AI 说打包失败"，
看不出是清单漏了文件。同类问题还有两处文案：`promptTemplate` 里写着更早的
`skills/qbank-authoring/SKILL.md`；`verifyHints` 里写 `tools/qbank/validate_qbank.py`，
那是仓库内路径，Skill 目录里对应 `scripts/validate_qbank.py`。

- 清单补 `scripts/ktqbf/`，并说明"入口只有一行导入，实现在这里"；
- 提示词改成"先读我给你的 SKILL.md（在这份 Skill 的根目录）"，不再假设上传后的父目录名；
- 复核命令统一成 `scripts/…`，并注明在 Skill 目录里执行；
- 加 `SkillGuideBundleTest`（4 条）：每条清单路径在仓库里真实存在（支持 `schema-*.json` 通配与
  `dir/` 目录）、两个入口脚本依赖的 `ktqbf` 必须在清单里、文案里出现的每个 `.py` 路径都能找到、
  每条都要写清"缺了会怎样"。注入两处错误（`scripts/ktqbf-core/` 与仓库路径）后，
  对应两条按预期变红。

### 23.3 Skill 页从来没被截图覆盖过

74 张截图里没有它 —— 而它正是 23.2 改的那个页面，改动完全没有视觉验证。
补两张：`09b-skill.png`（浅色，高 1900）与 `70b-skill-dark.png`（深色）。
看过图：8 条清单完整、`scripts/ktqbf/` 在内、没有折行溢出或重叠，深色下对比度正常。

连带改动：`ci.yml` 的 `EXPECTED_SHOTS` 74 → **76**。这个数字是硬校验，
只加截图不改它，`screenshots` job 会直接红。

### 23.4 结果

用例 701 → **708**，全量重跑 0 失败；截图 74 → **76**；`:shared:compileAndroidMain` 通过。

这一节的教训可以一句话说完：**"还没做"是一种需要维护的声明，和"已做"一样会过期**，
而且过期时没有任何东西会报错 —— 所以两边的守卫都得写。

## 24. 用制包 Skill 真做一份题库：专升本 43 题（2026-10-29）

前面几节都在验"工具能打包、能校验"，这一节验的是**换个身份用一遍**：
拿 `tools/qbank-authoring-skill/` 当外部 Skill 使，从零做一份能练的题库，看它缺什么。

### 24.1 交付物

| 路径 | 内容 |
|---|---|
| `tools/qbank/examples/zhuanshengben-authoring/` | 源目录（manifest + 2 个结构分片 + 1 个文案分片） |
| `tools/qbank/examples/zhuanshengben.qbank` | 真包，10,813 字节，sha256 `ba9a0aa2…`，digest `249d234a…` |

43 题 / 4 科 / 12 个知识点：高等数学 13、大学英语 10、计算机基础 11、大学语文与马哲 8。
题型：单选 24 / 多选 5 / 判断 10 / 简答 4；语言 zh-Hans 43/43；媒体 0；增强资料 0。

### 24.2 「知识点」在格式里怎么放

KT-QBF 没有独立的"知识点"字段。这份包用两条已有的路把它表达清楚：

1. **每道题的解析就是一张知识点卡片**（`explanationMarkdown` 以 `**知识点：…**` 开头，写定义、
   关键结论、易错点）。练习答完立刻能看到，不需要额外界面。
2. **4 道「默知识点」题**（规范附录 A.2：`short_answer` + `grading.strategy = keywords`）：
   要求把要点写出来，按 `required` 关键词判 —— 全命中判对、部分命中判「部分正确」、
   一个都没命中判错。这是"知识点"和"题目"之间的一层：会做选择题不等于能说出要点。

`tags` 用「知识点名 + 学科」，同一个知识点下的几道题标签一致，界面上可按标签归组。

### 24.3 判题测试：43 题全部按标准答案答一遍

新增 `ZhuanshengbenBankTest`（6 条）：

| 用例 | 断言 |
|---|---|
| `parsesAllQuestionsWithTheirKnowledgeCards` | 43 题与题型分布；每题解析以知识点卡片开头、tags ≥ 2；判断题固定 2 选项且答案唯一；4 道默写题必须真的带 `required` |
| `everyQuestionAcceptsItsOwnCorrectAnswer` | **43 题逐题**按包内标准答案作答应判 CORRECT |
| `everyChoiceRejectsAWrongSelection` | 单选/判断换一个非正确选项、多选漏选一项，都应判 INCORRECT |
| `keywordQuestionsGradeInThreeSteps` | 同一道默写题：全命中 → CORRECT、只写一半 → PARTIALLY_CORRECT、什么都没写到 → INCORRECT |
| `explanationsReferToOptionContentNotToPositions` | 解析里不许用 `$o3$`、`选项 A` 这类按位置/编号的指代（选项会打乱，读者对不上） |
| `importsIntoRepositoryAndStaysPracticeable` | 走 `ImportBankUseCase` 真导入，43 题；重复导入被指纹识别为 Duplicate |

守卫验证：把 `cs04` 的参考答案改成不含「除基取余」的写法（作者改答案忘同步关键词的典型情形），
`everyQuestionAcceptsItsOwnCorrectAnswer` 立刻变红；另把某题解析改回用 `$o4$` 指代选项，
`explanationsReferToOptionContentNotToPositions` 也立刻变红。两次还原后包 sha256 都回到 `ba9a0aa2…`
（同一份源目录两次打包字节完全相同，这一条也顺带验了可复现）。

**这条测试守什么、不守什么**：它守"标准答案与判题规则自洽"（选项 ID 错位、关键词写漏、
判断题答案填反都会被抓住）。它**不判断答案在学科上对不对** —— 那是人的事。所以 manifest 把
`origin` 全部标成 `ai-assisted`，描述里写明"正式使用前请对照你所在省份的考试大纲复核"。

### 24.4 这一遍暴露的三个问题

1. **生成脚本自己会把多选题做成单选题**。一次性生成脚本里 `choice()` 默认 `single_choice`，
   5 道多选忘了传 `multiple_choice`，结果"多选 0 道、单选 29 道"。**校验器不会报** ——
   每道题单独看都是自洽的（一个正确选项的单选）。只有统计题型分布时才发现。
   教训：题型分布要打印出来看，不能只看"打包 OK"。
2. **Gradle 不把 `.qbank` 当成测试输入**。只改包、不改 Kotlin 源码时，`jvmTest` 被判 UP-TO-DATE
   直接跳过 —— 测试报告是绿的，但它一次都没跑。我第一次注入错误答案时就是这样"通过"的。
   已在 `shared/build.gradle.kts` 里把 `tools/qbank/examples/**/*.qbank` 与 `banks/**/*.qbank`
   登记为测试输入；再验一次：改包后同一命令不再 UP-TO-DATE，注入的错误立刻被抓到。
3. **解析里"按第几条"指代选项，打乱后就是错的**。我第一版写了「前三条是必背项」「第四条应为…」
   以及 `$o4$ 少了 +C` 这类句子 —— 而选择题默认 `shuffleAllowed: true`，选项顺序每次都可能不同。
   界面上没有"第四条"，只有一个被打乱的列表，用户读到的解析对不上任何一项。已全部改成引用选项内容，
   并用 `explanationsReferToOptionContentNotToPositions` 守住这个方向。

## 已知限制（P10 之后仍然成立）

| 限制 | 说明 |
|---|---|
| release APK 未签名 | 没有作者的 keystore，只能出 `-unsigned`；直接在 `applicationId` 相同的设备上装会被拒 |
| 只在 Windows 上验证 | `Deb` / `Dmg` 只在配置里声明，没有在对应平台真正构建过 |
| MSI 装/升/卸已实测，但界面串没点过 | 第 22 节：本机从当前 HEAD 重打包，装 → 启动（窗口句柄非零）→ 覆盖升级 1.0.0→1.0.1 → 卸载全部真跑，且全程没碰用户数据。**没验到的是窗口里的像素与"导入 → 练习 → 错题"这一串点击**（本会话截图被安全策略拦，只能证明进程与窗口存在）；也没在干净虚拟机里从零走一遍 |
| Android 未上真机 | 只做了编译、打包、清单与资源核对；没跑仪器测试，也没在设备上走一遍导入 → 做题 |
| 架构债务 4 处 | 见 P10-1 登记表；新增越层会被测试拦住，存量还在 |
| 组合根仍偏大 | 已从 3371 行降到 1622 行（工具箱、历史、练习设置、AI 讲解、手工编辑、历史复核、批量队列、导入、练习、知识图谱、备份、笔记十二刀）；它仍是唯一接触 infrastructure 的界面文件，靠的是把用例包成 lambda 递给子控制器 |
| CI 的产物只验到「打得出」 | 2026-10-28 修完三处根因后，五个 job 全部在真实 runner 上绿过，APK 与 MSI 都能产出；但**没有在 runner 上装过、跑过** —— runner 是临时机器，"能打"与"能装能跑"是两件事。装/升/卸后来在本机补验（第 22 节），补的是"MSI 这一形态可用"，不是"CI 产物可用" |
| MSI 的描述是英文 | MSI 数据库的码页跟着构建机 locale 走，中文 `description` 在任何 en-US Windows 上都会让 light.exe 退 311；要中文得自定义 WiX 资源目录（`Codepage=936`），当前没做，安装包属性用英文、应用内仍是中文 |
| 存储层仍是文件 | 目录 + 每库 NDJSON，不是 SQLite。读路径已由 [STATUS-P11](./STATUS-P11.md) 拆开：取一道题 245ms → **1ms**、搜一个词 964ms → **5ms**、加一道题 226ms → **12ms**；换来的是三张自维护的派生表（偏移表、倒排表、尾巴），各自带锁、"撒谎即重建"。**仍未动**的是整库替换（打补丁 / 重新导入）走 `save`，十万题仍要重写 49MB / 约 0.5s —— 这是"整体替换"语义的代价，不是日常路径（日常"写一题"走 `addQuestion`，只写一行） |
| 整库指纹只在追加时是 O(1) | 指纹已换成可增量结构（第 21 节），加一道题是 O(1)；但 `save`（整库替换：打补丁 / 重新导入）仍要把整库哈希一遍（十万题约 93ms）。另外这是一次**破坏性格式变更**：升级前生成的 `.qbpatch` 在新版本上会报「版本不符」 |
| 界面语言只有中文 | 多语言做的是题库语言；`SKILL.md`、应用文案仍是硬编码中文 |
| 键盘作答只覆盖选择与提交 | 图谱、笔记、备份这些页面还没有快捷键；练习页也还没有「跳过 / 收藏」的键位 —— 先把最热的那条路走通 |
| 像素护栏只有三页常驻 | `DarkSurfaceTest` 只渲染练习 / 导入 / 历史，每次 `:shared:jvmTest` 都跑；全量 74 张那条要 `KT_SHOTS=1`，只在 push 到主分支的 `screenshots` job 里跑（PR 上没有） |
| CI 的图拉丁字体与 Windows 不同 | Linux 上没有 Windows 那套字体，fontconfig 会替换：标题与正文里的英文明显更粗更方。CI 的图可用于核对版面、间距、配色、溢出，不适合核对字重与字形 |
| 深色下 Hero 主按钮仍是白底 | "纸上一块白"是刻意的层次；代价是它的字色 `HeroActionInk` 必须固定，不跟着主题走 |
| 浅色的 `Faint` 只有 2.95:1 | 它只用于计数、脚注这类提示级文字，不承担正文；深色下同一项是 4.46:1 |
| 换主题时整棵树重建 | 滚动位置与局部展开状态会重置（切主题是低频动作，换来的是"一处定义、处处跟随"） |
| 跟随系统依赖平台上报 | 桌面 / Android / Web 由各自运行时提供；iOS 未在真机验证过 |
| 只能整库移除，不能删单题 | 题库内容属于作者：要改单题走 `.qbpatch`，本机只能追加自己的题。移除不可撤销，退路只有备份 |

## 下一步建议

1. **界面串的安装版验收**：MSI 的装 / 升 / 卸已经在本机真跑过（第 22 节），剩下的是一次
   "在能看见的桌面上，用装好的那份走完 导入 → 练习 → 错题 → 移库"—— 这一步需要人来点，
   截图与自动化都替代不了；APK 同理（还要先有签过名的包和一台设备）；
2. **SQLite 存储层**（P11）：当初按收益排的三条目标，前两条已在 P11 用"派生表 + 定点读"做到，
   第三条判定为不需要做：
   - ~~取一道题 245ms → ≤10ms~~：**1ms**（`locate` 走偏移表，只读那一行）；
   - ~~搜一个词 964ms → ≤50ms~~：**5ms**（二元组倒排 + 成批复核，首解析 77ms）；
   - ~~改题/删题整库重写~~：产品里没有就地改/删单题的入口，`save` 只在整库替换（补丁 / 重新导入）时走，
     那是语义本身，不是待优化项（详见 `STATUS-P11` 第 8 节第 2 条）。
   于是"换 SQLite"这件事**失去了唯一的硬理由**——它原本是为了这三条。真要走，仍按原来的顺序：
   端口先长语义（`TextStorage` 只会整份读/整份写），SQLite 有价值的部分是"只读需要的那些行"，
   而那部分已经由三张派生表实现；换来的是"自维护索引 + 三道锁"这套复杂度。
   首页列表不许退步（现在 3ms / 7MB），别拿它当换库的理由。
