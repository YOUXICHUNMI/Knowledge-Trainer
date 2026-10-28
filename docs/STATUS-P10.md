# Knowledge Trainer — 开发状态（P10）

> 更新时间：2026-10-28
> 本文件只记录**真实执行过**的检查与产物；没跑过的平台、没签名的包，一律如实标注。

## 一句话结论

P10 第一切片完成：**分层架构从"文档约定"变成可执行断言**（7 条，0 越层）；
**Windows 与 Android 首次产出真实可分发产物**（MSI 63.7 MB、debug APK 13.6 MB、
release APK 9.7 MB 未签名），桌面端 exe 实测可启动；
**制包 Skill 与工程内实现重新对齐** —— Skill 副本此前落后两天，会拒收应用已经接受的
`sources` / `contentDigest` 包，现在两边规范与校验器逐字节一致，四份真实题库（A/B/C/总）
在新校验器下仍全部通过。

## P10-1 架构验收：把"依赖只能指向内层"变成测试

新增 `shared/src/jvmTest/.../architecture/ArchitectureTest.kt`（7 个用例），
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
| `desktopApp/build/compose/binaries/main/msi/KnowledgeTrainer-1.0.0.msi` | 63.7 MB | `cd414e298ece176d…` | 可安装 |
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
| 共享层全量单测 | `./gradlew :shared:jvmTest` | **615 用例 0 失败**（P9 后 601 + 架构 7 + 拆分相关 7） |
| 十万题基准 | `KT_BENCH=1 ./gradlew :shared:jvmTest --tests "*LargeBankTest*"` | 导入 604ms / 写入 567ms / **重开 3ms** / 读题 538ms，峰值堆 113 MB |
| 界面离屏截图 | `KT_SHOTS=1 … --tests "*ScreenshotTest*"` | 54 张，含真实 B 类题库附图 |
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

`.github/workflows/ci.yml` 三个触发面：

| 触发 | 任务 | 内容 |
|---|---|---|
| push / PR（必检） | `toolchain` | `python tools/verify/run_all.py` —— 12 项：坏包 49、扩展包、补丁 39、Skill 自检 44、Skill 坏包 49、可复现构建 ×2、样本包校验 ×6 |
| push / PR（必检） | `jvm-tests` | `./gradlew :shared:jvmTest`，615 用例，失败时上传测试报告 |
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

## 已知限制（P10 之后仍然成立）

| 限制 | 说明 |
|---|---|
| release APK 未签名 | 没有作者的 keystore，只能出 `-unsigned`；直接在 `applicationId` 相同的设备上装会被拒 |
| 只在 Windows 上验证 | `Deb` / `Dmg` 只在配置里声明，没有在对应平台真正构建过 |
| MSI 只在本机试装流程 | 没有在干净虚拟机里完整走"安装 → 运行 → 升级 → 卸载"，升级兼容性只有 `upgradeUuid` 这一层保证 |
| Android 未上真机 | 只做了编译、打包、清单与资源核对；没跑仪器测试，也没在设备上走一遍导入 → 做题 |
| 架构债务 4 处 | 见 P10-1 登记表；新增越层会被测试拦住，存量还在 |
| 组合根仍偏大 | 已从 3371 行降到 2972 行（工具箱、历史、练习设置三刀），还有 8 个关注点按 [PLAN-APPCONTROLLER.md](./PLAN-APPCONTROLLER.md) 逐刀拆；它仍是唯一接触 infrastructure 的界面文件 |
| CI 尚未在真实 runner 上跑过 | 工作流已提交（Python 全量回归 + 615 用例），但本机无法验证 GitHub Actions 第一次运行；Ubuntu 上 compileSdk 37 是否能被 AGP 自动补齐，要等首次运行确认 |
| 存储层仍是文件 | 与 P9 相同：目录 + 每库 NDJSON，不是 SQLite；十万题能开（3ms），但整库解析仍要 538ms / 113MB |
| 界面语言只有中文 | 多语言做的是题库语言；`SKILL.md`、应用文案仍是硬编码中文 |

## 下一步建议

1. **首次跑通 CI**：push 一次看 `toolchain` 与 `jvm-tests` 是否在 Ubuntu 上绿；重点盯 compileSdk 37；
2. **继续拆 `AppController`**：第 1–3 刀已完成（工具箱、历史与日历、练习设置），下一步是 AI 讲解与离线手工编辑，每刀单独提交；
3. **真机验收**：MSI 在干净机器上跑一遍安装/卸载，Android 装 debug 包走一遍导入 → 练习 → 错题；
4. **SQLite 存储层**：解决"整库常驻内存 / 改一道题重写整个文件"两个已登记的限制。
