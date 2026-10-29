# Knowledge Trainer — 开发状态（P11）

> 更新时间：2026-10-29
> 本文件只记录**真实跑过**的数字；没跑过的平台、没做的方案，一律如实标注。

## 一句话结论

P11 第一切片：**「取一道题」不再读整份文件**。十万题的库上从 **389ms 降到 1ms**
（同一次运行里的对照，不是两次采样比）。

做法不是换数据库，而是先让端口长出能力：存储层多了一个可选的「按字节读一段」，
题库目录旁多了一张**题号 → 行位置**的旁注索引。索引是派生数据 ——
丢了、旧了、被改坏了都不影响正确性，只影响下一次要不要重建。
这一刀同时把「加一道题」里最后一趟全量读拿掉了：**226ms → 26ms**。

SQLite 这一步仍然没上，原因写在最后一节（本机拿不到任何依赖）。

## 1. 端口先长语义：`RandomAccessText`

`TextStorage` 只会「整份读 / 整份写」，所以"取一道题"必然要把 49.9MB 读成字符串再逐行扫。
新增的是能力本身，不是索引格式：

```kotlin
interface RandomAccessText {
    fun byteLength(name: String): Long?
    fun readRange(name: String, offset: Long, length: Int): String?
}
```

- 只有真文件后端实现得了（桌面 / Android 的 `FileTextStorage`）；
- 内存与 Web 后端**不假装拥有**这个能力，`locate` 自动退回整份读 + 扫描，结果一致、只是慢；
- 偏移是 **UTF-8 字节**，不是字符：一个汉字三字节，按字符算出来的偏移 `seek` 过去会读到半个字。

## 2. 索引长什么样

`banks/<bankId>/offsets-v1.tsv`，一行一条，与题目文件同序：

```
q-000001	0	512
q-000002	512	509
```

十万题约 **2.1MB**（题目文件 49.9MB，约占 4%）。刻意不是 JSON：十万行三列，解析器越薄越好。

它在三条路径上生成：整库写入（导入 / `save`）时顺手排出来、追加一道题时**只追加一行**、
以及校验不过时从题目文件重建。

## 3. 三道锁：索引可以错，但不能被当成真数据

索引是派生数据，风险不是"丢了"（丢了就重建），而是**撒谎**——指到一个错位置，然后被人当真。

| 锁 | 判据 | 不成立时 |
|---|---|---|
| 结构锁 | 最后一条的结束位置 == 题目文件字节长度 | 重建 |
| 条数锁 | 解析出的条数 **不超过**目录里的题数（多出来 = 文件里有目录不知道的题） | 重建 |
| 内容锁 | 按索引读回来的那一行，`questionId` 就是要找的那个 | 这一库的索引结果全作废，重建后退回扫描 |

"一次图谱刷新查两三道题"里，任意一道触发内容锁，本次查询就老老实实扫一遍 ——
结果是**这次慢、下次不慢**，而不是快但可能错。

条数锁为什么用"不超过"：文件里若有读不懂的行，重建出来的条目天然少几条；要求相等
会让这种库**每次**定位都重建一遍 —— 把一个数据问题变成了性能悬崖。反过来"多出条目"
不能接受：那说明文件里有目录还不知道的题。

内容锁还有一条分寸：题号对得上、内容却不成形（缺选项之类），算「这一道用不了」，
不算「索引撒谎」。否则每次定位都会触发一次重建。

重建时也不收"半截行"：`{"questionId":"q-1","type":"sing` 这种崩在写一半的残行，
从行首字段里能抠出一个像样的 id，但它不是一道题；收进索引就会让每次校验都失败。
判据很便宜 —— 整行以 `}` 结尾才算写完。

## 4. 数字（十万题 / 49.9MB，同一次运行内的对照）

| 阶段 | 之前 | 现在 | 说明 |
|---|---|---|---|
| 定点取一道题（无随机读能力，对照） | 389ms | 389ms | 读整份 + 扫描，Web / 内存后端走这条 |
| 定点取一道题·第一次 | — | **83ms** | 解析该库索引（2.1MB） |
| 定点取一道题·之后 | — | **1ms** | 只读那一行（512 B） |
| 加一道题（`addQuestion`） | 226ms / 写 658 B | **26ms / 写 682 B** | 查重从"读整份"变成查表；多出的 24 B 是索引行 |
| 搜一个词 | 964–1262ms | 1262ms | **没动**：仍是全表扫描（见第 6 节） |
| 改 / 删一道题（`save`） | 471ms / 整库重写 | 471ms | **没动**：仍是整库重写 |
| 打开首页（只读目录） | 2–3ms / 6MB | 2–6ms / 6MB | 不许退步的那一条，没退 |
| 整库写入（导入） | 539–579ms | 539–668ms | 多编码一遍算字节长度 + 写 2.1MB 索引；在这台机器的噪声里 |

报告文件：`shared/build/large-bank-writes-report.txt`、`large-bank-locate-report.txt`、
`large-bank-append-report.txt`（`KT_BENCH=1 ./gradlew :shared:jvmTest`）。

## 5. 守卫（都真跑过，也会真红）

| 用例 | 守什么 | 注入什么会让它红 |
|---|---|---|
| `QuestionIndexTest.locatingAQuestionReadsTheRightBytesEvenWithMultiBytePrompts` | 偏移按**字节**算 | 把行长换成 `line.length`（字符数）→ 读到半个汉字 |
| `QuestionIndexTest.locateReadsOnlyTheIndexedLineRatherThanTheWholeFile` | 定点读只读那一行 | `locate` 改回整份读 → 读到 2.4MB |
| `QuestionIndexTest.indexIsRebuiltWhenTheFileGrowsBehindItsBack` | 结构锁：索引落后于文件就不许用 | 去掉 `endOffset` 校验 → 拿旧表照着读 |
| `QuestionIndexTest.aCorruptIndexIsTreatedAsSuspectNotAsTruth` | 内容锁：读到的必须是这一道 | 去掉 `dto.questionId != questionId` → 返回别人的题 |
| `QuestionIndexTest.aBankWithOneUnreadableLineStillUsesItsIndexAfterHealing` | 坏行跳过后表仍可用，不变成"每次重建" | 把"残行"也收进索引 → 条目数多一条，永远重新校验 |
| `QuestionIndexTest.addingAQuestionExtendsTheIndexInsteadOfRewritingIt` | 追加时索引跟着长 | 只写题目行不写索引行 → 下次必然重建 |
| `QuestionIndexTest.removingABankRemovesItsIndexToo` | 删库连索引一起删 | 漏删 → 空目录收不掉 |
| `LargeBankTest.locatingAQuestionReadsOneLineNotTheWholeBank` | **整份读次数 = 0** | `locate` 走扫描 → 立刻变成 1 |
| `BackupFlowTest.encryptedExportUnlocksOnAnotherMachine`（既有） | 备份文件数 | 不把索引排除出备份 → `expected:<2> but was:<3>` |

最后一行值得单说：这一刀改完第一次跑全量，**唯一变红的是备份用例**，不是新代码 ——
它发现"每个库目录里多了一个文件"。结论不是改测试，而是改实现：索引是派生数据，
备份不该带它（否则每个包白胖 4%，恢复出来的索引还多一层没必要的信任）。改的是
`DirectoryVault.isIgnoredName`，测试原样不动就绿了。

## 6. 代价与边界（别把它读成"存储层已经行了"）

- **搜索仍是全表扫描**（1.26s / 十万题）。要 ≤50ms 得有倒排索引或 FTS，那是另一种数据结构，
  不是这张表能顺手带出来的。这一条仍是 P11 的验收缺口。
- **改题 / 删题仍是整库重写**（`save`）。定点改写一行需要"等长替换或重排偏移"，
  这一刀没做 —— 现在删掉中间一行，后面每一个偏移都会变。
- **第一次定位要 83ms** 解析索引。之后靠内存缓存（最多 2 个库）；换库会重新解析。
- **索引不是免费**：占题目文件的 4%；整库写入多一次编码（为了知道每行几字节）。
- **Web / iOS 走老路径**：它们没有随机读实现（Web 用内存存储，重启即丢），
  `locate` 仍是读整份 —— 行为一致，只是慢。
- **备份不含索引**：恢复之后第一次定位会重建（一次整份读），这是有意的。

### 另一个真事故：磁盘被自己的基准写满

跑第三轮十万题基准时报 `IOException: 磁盘空间不足`，C 盘 0 字节可用。
根因不是代码：每个基准用例都会 `createTempDir` 造一份 50–100MB 的题库，
**跑完不删**，一天几次就攒到 2.1GB（198 个目录）。现在每个基准用例收尾都会删掉自己的目录
（`cleanup(root)`），清理失败也不影响结论 —— 它只是缓存。

## 7. 为什么 SQLite 仍然没上

这次不是优先级问题，是**拿不到依赖**：

```
repo1.maven.org            连不上
dl.google.com/dl/android/maven2  连不上
github.com                 连不上（TLS 握手失败）
本地缓存                   sqlite-jdbc / SQLDelight / Room 一个都没有
```

三个候选方案（Room KMP、SQLDelight、各平台原生驱动包一层）都需要新增依赖，一个都拉不下来。
硬扛的做法是自己写一个存储引擎 —— 那比现在这套索引复杂得多，也更可能出错。

所以这一刀走的是**SQLite 之前必须先做完的那一半**：端口长出语义（`locate` / `searchQuestions`
早已是一等操作，这一刀补的是"按位置读"），并让文件实现先把能拿到的收益拿到手。
真要换库时，`BankRepository` 的实现换掉即可 —— 索引表对应一张 `(id, offset)` 的表，
内容锁与结构锁退化成 SQLite 自己的约束。

## 8. 下一步

1. **推上去**：本地 `main` 领先 origin 34 个提交（含本刀），GitHub 连不上，等网络恢复后 push 并看 CI；
2. **搜索**：给题干建倒排索引（中文按二元组切），目标 ≤50ms；这是 P11 剩下的最大一项；
3. **改 / 删单题**：`save` 目前整库重写；有偏移表之后，"改一行 + 重排后面偏移"是可以做的；
4. **真机验收**：MSI 与 APK 仍从未装过（P10 起就挂着）。
