# KT-QBF 1.0 — Knowledge Trainer Question Bank Format

**状态**：本项目内部格式，1.0.0 已冻结（2026-10-19）。**不是行业通用标准**，目前只有 Knowledge Trainer 与随附工具读写。
**扩展名**：`.qbank` 是一个符合本规范的 **ZIP 容器**。把任意文件改名为 `.qbank` 不构成有效题库包。
**1.0.0 冻结后的可选扩展**（不升 `schemaVersion`，见 §12）：`sources` / `origin` / `sourceId` /
`sourceLocator`（§4.1、§5.1）与 `contentDigest`（§4.2）—— 出处声明与整包内容摘要。

---

## 1. 为什么要把"身份"和"文案"分开

一道题在中文版和英文版里，题干、选项文字完全不同，但它**是同一条学习记录**：
昨天错过的 q001，换成英文重练时仍应是"那道错过的题"。

因此 1.0 把包内容切成三层：

| 层 | 文件 | 内容 | 是否语言相关 |
|---|---|---|---|
| 结构层 | `questions/part-*.ndjson` | questionId、type、optionIds、correctOptionIds、grading、tags | ❌ 语言无关 |
| 文案层 | `locales/<locale>/questions.ndjson` | 题干、选项文字、简答标准答案、解析 | ✅ 每语言一份 |
| 资源层 | `media/index.ndjson` + `media/**` | 图片等二进制资源，按 assetId 引用 | ❌ |

**规则**：`questionId` 与 `optionId` 是身份，跨语言共享；`promptMarkdown`、选项文字、`answerText` 是文案，按 locale 分开。
任何语言版本都不得为同一道题生造新的 questionId。

---

## 2. 目录结构

```
starter.qbank                 [ZIP，无外层目录]
├── manifest.json             必需
├── questions/
│   ├── part-0001.ndjson      必需，至少一个分片
│   └── part-0002.ndjson      可选，多分片按文件名排序拼接
├── locales/
│   └── zh-Hans/
│       └── questions.ndjson  每个 supportedLocale 一份
├── media/
│   ├── index.ndjson          有媒体时必需；无媒体时省略
│   └── img/tcp-handshake.png 资源实体，路径必须以 media/ 开头
└── enhancements/
    ├── index.ndjson          有增强资料时必需
    └── reconstruction.ndjson 可选
```

ZIP 条目名一律使用 **UTF-8、正斜杠 `/`、无前导 `/`**。

**允许非 ASCII 条目名**（典型场景：作者把图片命名为 `media/img/中文截图.png`）。
真正的约束是"跨平台解包时路径含义唯一"，所以只禁止这些：控制字符、反斜杠、冒号（NTFS
数据流）、`* ? " < > |`、`..` 路径段、绝对路径与前导盘符。身份标识（`questionId` /
`optionId` / `assetId` / `locale`）仍然只允许 ASCII —— 它们要参与判题与进度匹配，
不该出现在不同编码下的等价写法问题。

---

## 3. 身份与 ID 规范

| 字段 | 语义 | 约束 |
|---|---|---|
| `bankId` | 题库稳定身份，跨设备/跨版本不变 | 小写 UUID v4 字符串，`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` |
| `questionId` | 题目稳定身份 | `^[a-z0-9][a-z0-9_.-]{0,63}$`，包内全局唯一，**删除后不得复用** |
| `optionId` | 选项稳定身份 | 同上，**题目内唯一**；不同题目可复用同名字符串（作用域是题目） |
| `assetId` | 媒体资源身份 | `^[a-z0-9][a-z0-9_.-]{0,63}$`，包内唯一 |
| `contentRevision` | 该题该语言文案的修订号 | 正整数，从 1 开始；文案变更必须 +1 |
| `locale` | 文案语言 | BCP 47，如 `zh-Hans`、`en`、`zh-Hant-TW` |
| `schemaVersion` | 包格式版本 | 语义化版本，1.0.x 表示 1.0 规范的兼容修订 |

**为什么 questionId 允许字符串而不强制 UUID**：作者手写题库时 UUID 不可读也不可维护。
稳定性的保障来自"修订时复用原 ID"这一纪律，而不是 ID 的形状。工具会拒绝同一 bankId 下 ID 冲突。

---

## 4. manifest.json

```json
{
  "format": "knowledge-trainer-question-bank",
  "schemaVersion": "1.0.0",
  "bankId": "550e8400-e29b-41d4-a716-446655440000",
  "contentRevision": 1,
  "title": "Knowledge Trainer 综合演示题库",
  "sourceLocale": "zh-Hans",
  "supportedLocales": ["zh-Hans"],
  "author": {
    "displayName": "演示作者",
    "contacts": [
      {"type": "email", "label": "错误反馈", "address": "feedback@example.org"}
    ]
  },
  "license": "CC-BY-4.0",
  "tags": ["demo"],
  "createdAt": "2026-10-19T00:00:00Z",
  "files": [
    {"path": "questions/part-0001.ndjson", "sha256": "<64 位小写十六进制>"},
    {"path": "locales/zh-Hans/questions.ndjson", "sha256": "<64 位小写十六进制>"}
  ]
}
```

### 字段规则

| 字段 | 必填 | 说明 |
|---|---|---|
| `format` | ✅ | 固定字符串 `knowledge-trainer-question-bank` |
| `schemaVersion` | ✅ | `^1\.0\.\d+$`；**主版本不符必须拒绝导入** |
| `bankId` | ✅ | 见 §3。**唯一的例外**：导入端对"整个字段缺失"的老包仍然放行（现场生成一个 UUID），因为 1.0 定稿前打出来的包就是这么打的。代价写在 §13.2 末尾：这样的库拿不到"按身份更新"，只能靠内容指纹被补丁定位。字段存在但不是 UUID 一律拒绝 |
| `contentRevision` | ✅ | 整个题库内容的修订号，整理性变更 +1。缺失按 `1` 处理 |
| `title` | ✅ | 1–200 字符 |
| `sourceLocale` | ✅ | 必须出现在 `supportedLocales` 中，且**必须覆盖全部题目** |
| `supportedLocales` | ✅ | 非空、无重复的 BCP 47 列表 |
| `author` | ✅ | `displayName` 必填；`contacts`（联系方式）可为空数组 |
| `license` | ❌ | SPDX 标识符或自定义短串 |
| `tags` | ❌ | 字符串数组 |
| `createdAt` | ❌ | ISO 8601 UTC，固定格式 `YYYY-MM-DDTHH:MM:SSZ`。打包器**原样保留**作者写的值，**不会**自动写入：时间戳一旦由打包器生成，同一份源目录在不同时刻打出的字节就不同，反而破坏可复现构建。 |
| `files` | ✅ | 除 `manifest.json` 自身外**每一个**文件的 `path` + `sha256` |
| `sources` | ❌ | 题库级来源清单，见 §4.1。最多 200 条；题目通过 `sourceId` 引用 |
| `contentDigest` | ❌ | 整包内容摘要（`^[0-9a-f]{64}$`），由打包器自动计算，见 §4.2。缺失只给警告（老包兼容） |

### 哈希规则

- `sha256` = 该 ZIP 条目**解压后字节**的 SHA-256，64 位小写十六进制。
- `manifest.json` **不列入** `files`（避免自引用）。
- 校验器**双向核对**：`files` 里的每一项必须在 ZIP 中存在；ZIP 中除 `manifest.json` 外不允许有清单外的条目（"未引用媒体"因此直接失败）。

### 包内容规范化 digest（用于版本比较）

```
digest = sha256( 按 path 字典序拼接的 "path SP sha256 LF" 列表 )
```

即：把 `files` 按 `path` 升序排列，每行写成 `<path> <sha256>\n`，UTF-8 编码后整体再取 SHA-256。
**不依赖** ZIP 压缩时间戳、压缩级别、条目顺序 —— 同样的内容用不同工具重新打包，digest 不变。

> 注意区分：这个 digest 只覆盖**文件清单**，用于版本比较与补丁锚点（16 位短串足够）；
> §4.2 的 `contentDigest` 还包含清单里的语义字段，用于"是不是同一份内容"的核对（64 位）。

### 4.1 来源清单 `sources`

作者声明"这些题是哪来的"。**这是声明，不是证明**：规范无法（也不打算）核对"那本书第 42 页真有这道题"。
规范的职责只有一条 —— 让包内引用自洽，让应用端能把作者的话原样转述给读者。

```json
"sources": [
  {
    "sourceId": "marx-textbook",
    "title": "马克思主义基本原理",
    "kind": "book",
    "author": "本书编写组",
    "edition": "2023 年版",
    "url": "https://example.com/marx",
    "license": "CC-BY-4.0",
    "note": "课后习题部分"
  }
]
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `sourceId` | ✅ | `^[a-z0-9][a-z0-9_.-]{0,63}$`（小写字母/数字开头，可含 `_ . -`）。包内唯一 |
| `title` | ✅ | 1–300 字符。这是给人看的书名/论文题/网页标题，**不是** URL |
| `kind` | ❌ | `book` / `paper` / `web` / `course` / `original` / `other`；缺失表示未声明类型 |
| `author` | ❌ | 该来源的作者/机构（与 `manifest.author` 是两回事：那是题库作者） |
| `edition` | ❌ | 版次、年份、卷号等。**同一本书不同版次可能删改题目，能写就写** |
| `url` | ❌ | `http(s)` 绝对地址；不支持 `javascript:`、`file:`、相对路径 |
| `license` | ❌ | 该来源自身的许可，与题库 `license` 无关 |
| `note` | ❌ | 说明用短句（如"仅参考课后习题"） |

最多 200 条。`sourceId` 重复 → `SOURCE_DUPLICATE`。

### 4.2 整包内容摘要 `contentDigest`

**由打包器自动计算，作者不要手写**（手写必然算错，且难点在于它覆盖 `files` 之外的字段）。
它的用途只有一个：让对方判断"我手上这份包，和作者发出来的那份是不是同一份内容"。

输入是下面这段文本的 UTF-8 字节，整体取 SHA-256，输出 64 位小写十六进制：

```
kt-qbf-digest-1
format=<format>
schemaVersion=<schemaVersion>
bankId=<bankId>
contentRevision=<contentRevision>
sourceLocale=<sourceLocale>
supportedLocales=<locales joined by comma>
title=<title>
author=<author.displayName>
license=<license or empty>
<path> <sha256>
<path> <sha256>
...
```

- 第一行是**版本标签** `kt-qbf-digest-1`：算法若需调整，改这个标签，而不是让新老摘要看起来一样。
- 中间是 `key=value` 行，顺序固定（就是上表顺序），**不做**字典序排序。
- 最后是 `files` 按 `path` 字典序排序后的 `"<path> <sha256>"` 行。
- `supportedLocales` 按清单里的原顺序用 `,` 连接（它本身是有序列表）。
- 每个值都做转义，顺序必须是 `\` → CR → LF → TAB（反斜杠**先**转义，否则后面替换会把它自己产出的 `\n` 再转一遍）：
  `\` → `\\`，`\r` → `\r`，`\n` → `\n`，`\t` → `\t`。缺失字段按空串参与。

**覆盖**：格式与版本、题库身份、内容修订号、语言、标题、作者、许可、每个文件的哈希。
**不覆盖**：`sources`、`tags`、`description` 等说明性字段 —— 否则"改一句说明就换一个摘要"，
作者补一个漏写的出处也会让所有读者的摘要校验全部报警。

**它不是签名**：没有密钥，改完内容重新打一次包，摘要就又自洽了。它能发现"作者改了包但忘了通知你"，
防不住"有人故意改了再发给你"。这两件事规范分得很清，应用端界面上也必须分开说。

**老包兼容**：`contentDigest` 缺失 → 警告 `CONTENT_DIGEST_MISSING`，导入照常进行，
但应用端记作"未提供摘要"，**不得**渲染成"校验通过"。摘要存在但对不上 → `CONTENT_DIGEST_MISMATCH`，
直接拒绝导入。

---

## 5. questions/part-*.ndjson（结构层）

每行一个 JSON 对象。分片按文件名升序读取，题目总顺序 = 拼接顺序。

```json
{"questionId":"q001","type":"single_choice","optionIds":["o1","o2","o3"],"correctOptionIds":["o2"],"shuffleAllowed":true,"grading":{"strategy":"exact-set"},"tags":["tcp"]}
{"questionId":"q002","type":"multiple_choice","optionIds":["o1","o2","o3","o4"],"correctOptionIds":["o1","o2","o3"],"shuffleAllowed":true,"grading":{"strategy":"exact-set"}}
{"questionId":"q003","type":"short_answer","grading":{"strategy":"strict-text"}}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `questionId` | ✅ | 见 §3；包内唯一 |
| `type` | ✅ | `single_choice` / `multiple_choice` / `true_false` / `short_answer` |
| `optionIds` | 选择题必填 | 2–26 个，无重复；顺序即作者建议的默认显示顺序 |
| `correctOptionIds` | 选择题必填 | `single_choice`/`true_false` 恰好 1 个；`multiple_choice` ≥ 2 个；必须是 `optionIds` 子集 |
| `shuffleAllowed` | ❌ | 默认 `true`；判断题建议 `false` |
| `grading.strategy` | ❌ | `exact-set`（选择题默认）/ `strict-text`（简答默认）；题型与策略不匹配直接失败 |
| `tags` | ❌ | 字符串数组，单标签 ≤ 32 字符 |
| `contentRevision` | ❌ | 默认 1 |
| `origin` | ❌ | 出处性质，见 §5.1。枚举 `authored` / `adapted` / `imported` / `ai-assisted`；**缺失 = 未标注** |
| `sourceId` | ❌ | 引用 §4.1 里的一条 `sourceId`；引用不存在的值直接失败 |
| `sourceLocator` | ❌ | 定位（章节、页码、题号、URL 锚点），1–200 字符 |

**答案定义（不可含糊）**

- 单选/判断：`correctOptionIds` 必须恰好一个元素，多选一、少选均判错。
- 多选：**集合完全相等**。ABC 正确时，AB、ABD、ABCD 全判错；顺序无关。
- 判断：`true_false` 是 2 个选项的特例，选项文案由文案层给出（通常是"正确/错误"）。
- 简答：`strict-text` = 与 `answerText` **逐字符完全一致**，不做任何归一化（空格、全半角、换行都算差异）。

### 5.1 出处声明（`origin` / `sourceId` / `sourceLocator`）

出处写在**结构层**，不写在文案层。理由是它回答的是题目的身份问题（这道题从哪来），
与"这题用中文怎么措辞"无关 —— 同一道题的英文译文不该有自己的一套出处。

```json
{"questionId":"q002","type":"single_choice","origin":"adapted",
 "sourceId":"marx-textbook","sourceLocator":"第一章 第二节 课后练习 3",
 "optionIds":["o1","o2"],"correctOptionIds":["o2"],"grading":{"strategy":"exact-set"}}
```

| `origin` | 含义 | 界面文案 |
|---|---|---|
| `authored` | 作者原创 | `作者原创` |
| `adapted` | 改编自某来源（改写过） | `改编自 …` |
| `imported` | 直接引用（一字未改） | `引自 …` |
| `ai-assisted` | 生成过程中有模型参与 | 琥珀胶囊 `AI 参与生成` |
| *(缺失)* | **作者没说** | `来源未标注` |

**四种状态和"未标注"必须分开**。规范里"缺失"就是"没说"，而不是作者的默认值；
应用端也不许把缺失渲染成 `作者原创`，更不许渲染成空白 —— 空白在中文界面里会被读作"没有特别说明，大概是原创"。
一个作者从没说过的话被界面替他补上，是这一块最严重的错误，而且事后无法从数据里分辨当初到底是"没说"还是"说了原创"。

**拼错不猜**：`origin` 只接受上表四个值（`ai_assisted` 这种下划线写法也接受，输出统一成 `ai-assisted`）。
`copied` / `ai` / `original` 之类一律 `ORIGIN_INVALID` **拒绝**。猜错等于替作者声明了他没说过的事，
而且从数据里看不出来被猜过。

**引用必须落地**：题目写了 `sourceId` 但 §4.1 里没有这条 → `SOURCE_UNKNOWN`，**硬错误**。
引用一条不存在的出处，比不标出处更可疑：读者会以为有据可查，实际查不到。

**两种只警告的情况**（不挡住导入，因为它们在真实题库里太常见）：

| 情况 | 警告码 | 理由 |
|---|---|---|
| 声明了 `adapted` / `imported` 却没写 `sourceId` | `ORIGIN_WITHOUT_SOURCE` | "抄来的"却不说是从哪抄的，比不写更糟 —— 它让人以为出处已经交代过了 |
| 写了 `sourceId` 却没写 `sourceLocator` | `SOURCE_LOCATOR_MISSING` | 位置缺失很常见（"整本参考书都算"），但读者没法核对到具体章节 |

`ai-assisted` 没有对应警告：纯模型生成的题本来就没有来源可言，这个值本身就是全部信息。

**出处不参与内容指纹**。题目指纹（`contentFingerprint`）只算 `type` / `promptMarkdown` / 答案 / 选项文案 ——
否则作者补一个漏写的出处，补丁里每条 `expect` 的逐题指纹都会全部失效，一份"只补出处"的补丁就再也装不进去。
补丁因此把出处改动单独归类（§13.2.4）：它不换 `contentRevision`，也不进复核清单。

---

## 6. locales/<locale>/questions.ndjson（文案层）

每行对应结构层的一个 `questionId`。

```json
{"questionId":"q001","locale":"zh-Hans","contentRevision":1,"promptMarkdown":"TCP 三次握手的主要作用是什么？","options":[{"optionId":"o1","contentMarkdown":"断开连接"},{"optionId":"o2","contentMarkdown":"建立 TCP 连接"},{"optionId":"o3","contentMarkdown":"加密数据"}],"explanationMarkdown":"用于建立 TCP 连接。"}
{"questionId":"q003","locale":"zh-Hans","contentRevision":1,"promptMarkdown":"什么是实践？","answerText":"实践是人类能动地改造世界的客观物质活动。"}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `questionId` | ✅ | 必须存在于结构层 |
| `locale` | ✅ | 必须等于所在目录名，且在 `supportedLocales` 中 |
| `contentRevision` | ❌ | 默认 1，正整数 |
| `promptMarkdown` | ✅ | 非空，≤ 20000 字符 |
| `options` | 选择题必填 | **必须与结构层 `optionIds` 完全一致**（同集合；顺序无关）；缺一个或多的一个都失败 |
| `answerText` | 简答必填 | 非空；与 `promptMarkdown` 同样遵循 Markdown 规则但按**原始文本**比较 |
| `explanationMarkdown` | ❌ | 解析 |

**多语言覆盖**

| 规则 | 说明 |
|---|---|
| `sourceLocale` | 必须 100% 覆盖所有题目，否则包无效 |
| 其他 locale | 允许部分覆盖，但**每道缺失的题都会在校验报告里列明** |
| 共用身份 | 同一 questionId 在不同 locale 下共享结构层定义与 optionId |
| 独立修订 | 各 locale 可各自有 `contentRevision` |
| 翻译状态 | 见 §8，机器翻译不得冒充"作者审核" |

**应用端行为（P6 起）**

| 行为 | 说明 |
|---|---|
| 全语言导入 | 导入时解析 `locales/` 下**每一种**语言，不只 `sourceLocale`；覆盖度写进题库目录，列语言不需要解析题目 |
| 部分覆盖可用 | 非源语言可以只翻一部分题；缺译文的题默认**不参与该语言的练习** |
| 不混语言 | 没有该语言译文时，绝不把源语言题面当译文塞给用户；用户可显式选择"缺译文时看原文"，此时界面标注「原文」 |
| 判题按语言 | 选择题用该语言的选项文案、简答题用该语言的 `answerText`；某语言缺 `answerText` 的简答题视为该语言下不存在（导入阶段即整包拒绝） |
| 语言标签受限 | 只接受 `language[-Script][-REGION]`；变体/扩展/私有段一律拒绝，避免"收了再悄悄丢掉"造成的语言碰撞 |
| 历史分语言 | 每次作答记录所用语言；抽题范围（未练习/错题/已巩固）按当前语言统计 |

---

## 7. 纯文本 / Markdown / LaTeX 边界

- `promptMarkdown`、`contentMarkdown`、`explanationMarkdown` 允许 **CommonMark 子集 + 数学公式**：
  行内 `$...$`、块级 `$$...$$`、代码块、列表、强调、表格、行内代码。
- **禁止**在题库内容中出现：HTML `<script>`、`<iframe>`、外链 CDN 资源、`javascript:` 链接、本地绝对路径。
  出现即校验失败（防注入、防离线环境失效）。
- 图片只能通过 `assetId` 引用包内资源，**不允许**写成 `![](https://...)` 外链。
- `answerText` 是判题基准，**不渲染**；如含 Markdown 语法会原样参与比较。

---

## 8. enhancements/（可选增强资料）

增强资料是**附加**数据：没有它，题目照常可练习、可判题。它不得覆盖标准答案。

`enhancements/index.ndjson`：

```json
{"questionId":"q001","locale":"zh-Hans","kind":"reconstruction","path":"enhancements/reconstruction.ndjson","reviewStatus":"unreviewed","source":"author"}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `questionId` | ✅ | 必须存在 |
| `locale` | ✅ | 必须受支持 |
| `kind` | ✅ | `reconstruction`（语义重组）/ `distractor`（干扰项）/ `graph`（知识关系） |
| `path` | ✅ | 必须是 `enhancements/` 下的文件 |
| `reviewStatus` | ✅ | `unreviewed` / `reviewed`；**AI 生成的必须标 `unreviewed`** |
| `answerHash` | ❌ | 该条增强所依据的答案指纹，**16 位小写十六进制**（sha256 前 16 位）。声明了就必须与当前题干/答案一致，否则校验报 `ENHANCEMENT_STALE`。不声明则不做失效检测。 |

### answerHash 的确切算法

增强资料是从**当前的题干与答案**派生出来的。题干或答案一改，旧增强就该失效 ——
否则用户会读到"解析说选 B、标准答案却是 C"。指纹把这条绑定关系写成可校验的字节：

```python
payload = {
  "questionId":       questionId,
  "locale":           locale,
  "type":             题型,
  "promptMarkdown":   该 locale 的题干原文,          # 不做任何归一化
  "options":          [[optionId, contentMarkdown], ...],  # 按结构层 optionIds 顺序；简答题为 null
  "correctOptionIds": 升序数组,                      # 简答题为 null
  "answerText":       简答题的 answerText,           # 选择题为 null
}
answerHash = sha256(UTF-8(json.dumps(payload, ensure_ascii=False,
                                     sort_keys=True, separators=(",", ":"))))[:16]
```

三条容易搞错的点：

1. 选项按**结构层 `optionIds` 的顺序**取文案，所以文案层里选项写成什么顺序都不影响指纹；
2. `correctOptionIds` **先排序**再入指纹 —— `["o2","o1"]` 与 `["o1","o2"]` 是同一个答案；
3. `explanationMarkdown`（解析）**不参与**指纹 —— 改解析不该让增强失效。

不要手抄这个哈希（和 `manifest.files` 同理，手抄几乎必错）。用工具取：

```bash
python scripts/validate_qbank.py answer-hash <authoring_dir>          # 人读，标注 OK / 缺 / 过期
python scripts/validate_qbank.py answer-hash <authoring_dir> --json    # 机器读
```

---

## 9. 媒体

`media/index.ndjson`：

```json
{"assetId":"tcp-handshake","path":"media/img/tcp-handshake.png","mimeType":"image/png","bytes":12345,"sha256":"<64 位小写十六进制>"}
```

| 检查项 | 失败即拒绝 |
|---|---|
| 路径必须以 `media/` 开头，且不含 `..`、绝对路径、反斜杠 | ✅ |
| `bytes` / `sha256` 与实际条目一致 | ✅ |
| 文件真类型与 `mimeType` 一致（magic bytes） | ✅ |
| 真实 ZIP 条目必须是"普通文件"，不得是目录或符号链接条目 | ✅ |
| 单个资源 ≤ 20 MiB，全部资源合计 ≤ 200 MiB | ✅ |

---

## 10. 安全规则（打包与解包双方都必须执行）

| 威胁 | 处理 |
|---|---|
| ZIP 路径穿越（`../`、绝对路径、Windows 盘符、NTFS ADS `:`） | 解包前逐条校验，命中即整体拒绝 |
| ZIP 炸弹（高压缩比 / 超大解压量） | 限制条目数 ≤ 20000、单条目解压 ≤ 50 MiB、总解压 ≤ 500 MiB、压缩比 > 200:1 拒绝 |
| 符号链接 / 设备文件条目 | 拒绝 |
| 加密条目 | 拒绝 |
| **本地文件头与中央目录矛盾** | 逐条比对 `file_size` / `compress_size` / CRC / 加密标志 / 条目名 / 数据区边界，不一致拒绝（`ZIP_HEADER_MISMATCH`） |
| manifest 中 `files` 与 ZIP 条目不一致（多/少） | 拒绝 |
| `schemaVersion` 主版本不匹配 | 拒绝，并提示需要新版应用 |
| 包内出现可执行内容（脚本、`.exe`、公式外链） | 一律不执行、不解析为代码 |

**为什么必须核对本地头**：主流 ZIP 库（Python `zipfile`、多数平台实现）解压时只读**中央目录**里的
`file_size` 与 CRC，本地头里的同名副本被忽略。只改中央目录的 `file_size` 就能让"实际解出的字节"
与"清单里的哈希"对不上而**不报错** —— 哈希校验形同虚设。因此校验器必须把两处元数据当作
互相独立的证人来交叉核对（置了 bit 3 的条目大小在数据描述符里，比对时按规则跳过）。


**失败即拒绝（fail-closed）**：校验器不会"尽量修复后放行"。

---

## 11. 校验报告格式

`validate_qbank.py --json` 输出：

```json
{
  "ok": false,
  "package": "bad.qbank",
  "schemaVersion": "1.0.0",
  "bankId": "...",
  "title": "...",
  "digest": "<规范化 digest，用于版本比较>",
  "contentDigest": "<整包内容摘要，见 §4.2；未提供时为空串>",
  "counts": {"questions": 3, "singleChoice": 1, "multipleChoice": 1, "trueFalse": 0, "shortAnswer": 1, "assets": 0},
  "locales": {"zh-Hans": {"covered": 3, "total": 3}},
  "enhancements": {},
  "provenance": {"total": 3, "sources": 1, "withSource": 1,
                 "originUndeclared": 1,
                 "byOrigin": {"authored": 1, "adapted": 1, "imported": 0, "ai-assisted": 0}},
  "errors": [{"code": "QID_DUPLICATE", "message": "...", "path": "questions/part-0001.ndjson", "line": 2}],
  "warnings": [{"code": "LOCALE_PARTIAL", "message": "..."}]
}
```

**错误码**（稳定，可用于脚本判断）：

`provenance` 段只在包里有出处声明时出现（老包没有这一段，不是错误）。`originUndeclared`
是**单独一位**，不与 `byOrigin` 相加混算：它统计的是"作者什么都没说"的题数 ——
把这一位并进任何一档，都会让"没说"看起来像"说了什么"。

| code | 含义 |
|---|---|
| `ZIP_NOT_ZIP` | 不是合法 ZIP |
| `ZIP_PATH_TRAVERSAL` | 路径穿越 |
| `ZIP_BOMB` | 超限或压缩比异常 |
| `ZIP_ENTRY_LINK` | 符号链接/特殊条目 |
| `ZIP_ENCRYPTED` | 加密条目 |
| `ZIP_HEADER_MISMATCH` | 本地文件头与中央目录矛盾（篡改/截断/自相矛盾） |
| `MANIFEST_MISSING` / `MANIFEST_INVALID` | 缺失/不符合 Schema |
| `SCHEMA_VERSION_UNSUPPORTED` | 主版本不符 |
| `SCHEMA_NOT_FOUND` | 校验器找不到 `schema-*.json`（部署不完整，不是题库的问题） |
| `FILE_NOT_LISTED` / `FILE_MISSING` / `HASH_MISMATCH` | 清单与条目不一致 |
| `NDJSON_INVALID` | 某行不是合法 JSON |
| `SCHEMA_INVALID` | 对象不符合对应 Schema |
| `QID_DUPLICATE` / `OID_DUPLICATE` / `ASSET_DUPLICATE` | ID 冲突 |
| `OPTION_SUBSET` | correctOptionIds 不是 optionIds 子集 |
| `OPTION_COUNT` | 选择项数量越界 |
| `ANSWER_COUNT` | 单选/判断正确答案个数不为 1，或多选不足 2 |
| `OPTION_MISMATCH` | 文案层选项集合与结构层不一致 |
| `ANSWER_MISSING` | 简答缺 answerText |
| `LOCALE_MISSING` | manifest 声明的 locale 缺文件 |
| `LOCALE_MISMATCH` | 行内 locale 与目录不一致 |
| `LOCALE_INCOMPLETE` | sourceLocale 未覆盖全部题目 |
| `LOCALE_UNKNOWN_QID` | 文案层出现结构层不存在的题 |
| `GRADING_MISMATCH` | 题型与判题策略不匹配 |
| `CONTENT_FORBIDDEN` | 出现脚本/外链/绝对路径 |
| `MEDIA_*` | 媒体相关（路径、类型、大小、哈希、未引用） |
| `ENHANCEMENT_*` | 增强资料相关（绑定幽灵题、缺基础文案、引用文件不存在、未被引用） |
| `ENHANCEMENT_STALE` | 增强资料的 `answerHash` 与当前题干/答案不符（改了题却没更新增强） |
| `SOURCE_DUPLICATE` / `SOURCE_INVALID` | `sources` 里 `sourceId` 重复 / 条目不符合 Schema（含 URL 协议不合法） |
| `SOURCE_UNKNOWN` | 题目引用了 `sources` 里不存在的 `sourceId`（**硬错误**，见 §5.1） |
| `ORIGIN_INVALID` | `origin` 不是四个合法值之一（拼错不猜） |
| `CONTENT_DIGEST_MISSING` | 清单没有 `contentDigest`（**警告**：老包照常导入，应用端记作"未提供"） |
| `CONTENT_DIGEST_MISMATCH` | `contentDigest` 重算结果与清单不符（**硬错误**：这份包被改过或打包不完整） |
| `INTERNAL_ERROR` | 校验器自身抛出未预期异常。**这不是题库的问题，是校验器的缺陷**；按 fail-closed 处理（判为不通过），请连同包与命令一起上报 |

---

## 12. 版本与兼容策略

| 变更类型 | schemaVersion | 兼容性 |
|---|---|---|
| 加可选字段 | `1.0.x` 递增 | 旧应用忽略未知可选字段，可读 |
| 加必填字段 / 改语义 | **`1.1.0`**（次版本） | 旧应用拒绝导入并提示升级 |
| 破坏性重构 | `2.0.0` | 必须提供迁移工具 |

**迁移规则**：
1. 读取时严格校验 `schemaVersion`，只接受 `1.0.*`。
2. 未知的**可选**字段保留在原始 JSON 中（不丢弃），便于降级。
3. 未来 `1.0.x → 1.1.0` 的迁移工具必须能一次性升级 authoring 源目录，而不是只改包内文件。
4. **出处（§4.1 / §5.1）与整包摘要（§4.2）都是 1.0.0 的可选扩展**，没有升 `schemaVersion`：
   老包缺这些字段照常导入，新包被老应用读取时按"未知可选字段"忽略。这条路径的代价是
   诚实记录在案 —— 老应用会显示"来源未标注"，而不是假装读懂了这个字段。

---

## 13. 与其他格式的关系

| 扩展名 | 用途 | 状态 |
|---|---|---|
| `.qbank` | 原始题库（本规范） | ✅ 1.0 已实现 |
| `.qbext` | 绑定单个 bankId 的用户/社区扩展（见 §13.1） | ✅ 已实现（P6 第一块） |
| `.qbpatch` | 题库版本补丁（见 §13.2） | ✅ 已实现（P8 第一块） |
| `.qbk` | 全量加密个人备份 | ❌ 未实现（P9） |

四者**不可互相改名使用**。

个人知识图谱（`RELATED` / `PREREQUISITE` / `EXPLAINS` / `CONTRASTS`，以及导航/判题硬依赖的区分）
是**用户本机数据**，不属于上述任何一种包格式：它只存 `bankId + questionId` 引用与展示快照，
跨设备迁移由 P9 的全量加密备份（`.qbk`）一并处理，不单独定义扩展名。

### 13.1 `.qbext` 扩展包（容器层）

`.qbext` 与 `.qbank` 共用 ZIP + `manifest.json` 的容器规则（§4、§10 的安全规则全部适用），
但**顶层路径集合不同**，因此不能靠改扩展名互换：

```
manifest.json
relations/edges.ndjson       # 跨库关系，一行一条
annotations/annotations.ndjson  # 讲解注释，一行一条（可省略）
```

| 字段 | 要求 |
|---|---|
| `format` | 固定 `knowledge-trainer-bank-extension`（`.qbank` 是 `knowledge-trainer-bank`），用于格式识别 |
| `binds.bankId` | 必填，题库 UUID。`binds.bankTitle` 只是显示提示，**不参与匹配** |
| `files[]` | 每个文件一条 `{path, sha256}`，导入时逐个核对；包内有未登记文件即拒绝 |
| 关系起点 | `source.bankId` 可省略（默认取 `binds.bankId`）；显式写成别的库 → 拒绝 |
| 关系语义 | 与个人图谱完全一致：四种关系 + `dependency` 取 `navigation` 或 `hard`，硬依赖只允许挂在有方向的关系上 |

约束（导入方必须执行）：

1. 扩展包**不得**包含题目、答案、媒体与 `enhancements/` —— 它只加关系与讲解。
2. `packageId` 全局唯一，同 ID 只能按版本升级（版本号按 `major.minor.patch` 数值比较）。
3. 上限：5000 条关系 / 2000 条注释 / 单条注释 2 万字。
4. 任一校验失败 → 整包拒绝，不做部分导入。

工具：`python tools/qbank/qbext.py build <目录> -o <文件>` / `validate <文件>`。
示例源码见 `tools/qbank/examples/qbext-authoring/`。

### 13.2 `.qbpatch` 更新补丁（容器层）

`.qbpatch` 描述的是**改动**，不是一份完整题库。容器规则与 `.qbank` 相同（ZIP + `manifest.json` +
逐文件 `sha256` + §10 安全规则），顶层路径集合不同：

```
manifest.json                    # 清单 + operations（改动全部写在清单里）
media/...                        # 可选，仅新题引用的资源
```

新增的题**内联**在 `operations[].question` 里，不单独放 `questions/part-*.ndjson` ——
一个补丁通常只加几道题，为它们开一套结构层 / 文案层目录只会让作者多点两次复制粘贴。
媒体仍走 `media/`，并在清单 `files[]` 里登记。

清单字段（在 §4 基础上增删）：

| 字段 | 必填 | 要求 |
|---|---|---|
| `format` | ✅ | 固定 `knowledge-trainer-bank-patch` |
| `patchId` | ✅ | `^[a-z0-9][a-z0-9_.-]{0,63}$`，全局唯一 |
| `version` | ✅ | `major.minor.patch`，只用于展示补丁自身的新旧 |
| `name` | ✅ | 1–120 字 |
| `bankId` | ✅ | 目标题库 UUID |
| `baseRevision` | ✅ | 补丁基于第几版（≥ 1） |
| `targetRevision` | ✅ | 应用后是第几版，必须 **> `baseRevision`** |
| `baseFingerprint` | ✅ | **16 位小写十六进制**，第 `baseRevision` 版的整库内容指纹（§13.2.1） |
| `title` | ❌ | 顺带改题库显示名（1–120 字）；不写则不动 |
| `notes` | ❌ | 作者给用户看的说明，原样展示 |
| `operations` | ✅ | 操作数组，1–2000 条 |

`operations[]` 的三种操作：

```json
{"op":"add","questionId":"q004","question":{ /* 与 §5 结构层同字段 + §6 文案层同字段 */ }}
{"op":"update","questionId":"q002","expect":"2cf5290769b52058","set":{"correctOptionIds":["o1","o2","o5"]}}
{"op":"remove","questionId":"q003","expect":"808549b3a724fbab"}
```

约束（导入方必须执行）：

1. `op` 只能是 `add` / `update` / `remove`；**同一道题只能出现一次**（同一题两条操作一律拒绝）。
2. `update.set` 只允许 8 个字段：`promptMarkdown` / `options` / `correctOptionIds` / `answerText` /
   `explanationMarkdown` / `assetIds` / `shuffleAllowed` / `tags`。
   **不允许改 `type` 与 `grading`** —— 题型一变，"什么算答对"和历史口径同时变，那是"换了一道题"。
   要换就 `remove` + `add`。
3. `expect` = 该题在 `baseRevision` 版里的题目指纹。本地那道题的现状对不上 → 拒绝应用（不猜）。
   `add` 上的 `expect` 没有意义，会被警告并忽略。
4. `add` 的新题必须自己站得住（§11 的题目自洽规则全部适用），并且**不允许与本地已有题目 ID 重名**
   —— 除非内容完全相同（那说明这个补丁早就装过了，按"无需更新"处理）。
5. `targetRevision` 必须严格大于 `baseRevision`：往回退不算更新。
6. 任一校验失败 → 整包拒绝，**不做"尽力应用"**。改一半的结果比不改更糟，事后也无法分辨哪里被改过。

#### 13.2.1 内容指纹（FNV-1a 64）

`baseFingerprint` 与 `expect` 都用 FNV-1a 64 位，输出 16 位小写十六进制（**不是** sha256 的 64 位）。
题目指纹的输入按下列顺序用 `U+0001` 连接：

```
type ␁ promptMarkdown ␁ 逗号排序后的 correctOptionIds ␁ answerText ?: ""
     ␁ 每个选项 "optionId=contentMarkdown"（按 optionId 升序）
```

**不含**讲解、标签、判题策略参数 —— 改讲解或补标签不该让补丁失效。
整库指纹 = 把每题指纹**排序后**用 `\n` 连接，再取一次 FNV-1a 64。

Python 侧实现只有 `tools/qbank/ktqbf/fingerprint.py` 一份，Kotlin 侧是 `Question.contentFingerprint()`
与 `Fingerprint.ofQuestions()`；两侧一致性由 `FingerprintParityTest` 的 golden 值锁住。
**不要手写这两个值** —— 用 `qbpatch.py build --base <旧包>` 让工具算。

#### 13.2.2 应用端的判定顺序

顺序是这套东西里唯一不能随便动的部分：

1. `bankId` 与内容指纹**都**对不上本机任何题库 → `BANK_MISMATCH`（这是另一个库的补丁）。
2. 整库指纹一致 → **严格应用**：逐条核对 `expect`。
3. 整库指纹不一致 → **宽松重算**（不校验 `expect`），若结果与本地内容完全一致 → `NoOp`
   （"你要改的每一条都已经是我现在的样子"）。
4. 仍失败、且冲突只有 `EXPECT_MISMATCH` / `PATCH_NO_EFFECT` 一类"其实已经改好了"的原因 → `NoOp`。
5. 其余按版本号给结论：本地更新 → `Outdated`；补丁基于的版本比本地早但内容对不上 → `CONTENT_DIVERGED`；
   版本号相同内容不同 → `BASE_MISMATCH`。

历史（作答记录）**任何情况下都不被补丁改写**：作者改答案不等于用户当时答错了。
"要不要按新答案复核旧记录"是应用端的能力（P8 第二块已实现）：应用把"按当前题面重算的判定"
与记录里的判定对比，差异逐条交给用户决定。补丁本身**不能**声明"这次改动只是修辞、不用复核" ——
所以 `manifest` 里没有这个字段：是否影响用户的判断由应用按实际内容算，不由作者声明。

#### 13.2.3 没有 `bankId` 的老包

1.0 定稿前打的包没有 `bankId`（导入时现场生成）。这类题库：

- 内容**未**被本地修改过 → 补丁可以按 `baseFingerprint` 命中它，正常更新；
- 内容被本地改过 → 既认不出内容、也没有与作者一致的 ID，补丁会报"找不到要更新的题库"。
  修法是让作者在下一版完整包里补上 `bankId`，用完整包覆盖一次。

工具：`python tools/qbank/qbpatch.py build <authoring目录> --base <旧.qbank> -o <补丁.qbpatch>` /
`validate <补丁>` / `fingerprint <包>`。示例源码见 `tools/qbank/examples/patch-authoring/`。

#### 13.2.4 出处变更（`origin` / `sourceId` / `sourceLocator`）

补丁的 `update` 操作可以改这三项。语义按"**`null` = 不改，空串 = 改回没有**"：

```json
{"op":"update","questionId":"q002","expect":"a1b2c3d4e5f60718",
 "origin":"imported","sourceId":"marx-textbook","sourceLocator":""}
```

上例把 `origin` 改成 `imported`、`sourceId` 指向该书、并把定位**清空**（不是"保持原样"）。

三条规则：

1. **只改出处不算内容变更**：`contentRevision` 不递增，题目的 AI 讲解缓存不失效，
   也不进"历史复核"清单 —— 出处与判题无关，改它不该让用户重看一遍自己的作答。
2. **但也不能静默丢弃**：应用端判定"这个补丁有没有生效"时比的是 `contentChanged || provenanceChanged`。
   只比内容指纹的话，一份"只补出处"的补丁会被当成 `PATCH_NO_EFFECT`，作者的心血被静默吞掉。
3. **仍受锚点约束**：`expect` 是内容指纹（不含出处），所以重复应用同一份出处补丁会命中
   `PATCH_NO_EFFECT`。应用端在这种情况下降级为 `NoOp`（"每条改动本地都已经成立"），
   而不是报失败 —— 装第二次不该是错误。

补丁里 `origin` 非法或 `sourceId` 格式不对 → `OPERATION_INVALID`；
`sourceId` 在本机题库的 `sources` 里找不到 → 仍然接受（补丁不携带来源清单，
由作者在完整包里补），但应用端会把它显示成"引自 未注明的来源"，不伪造书名。

---

## 14. 与旧格式的关系

1.0 之前项目只支持"简单 JSON 数组 / `{questions:[]}` / NDJSON"导入。这些**不是** KT-QBF 的一部分，
它们由导入界面的旧格式解析路径处理。`.qbank` 必须走本规范的严格校验，两条路径不会混用。

---

## 附录 A：1.1 草案扩展（ordering / keywords）

> 状态：**草案**。工具链（打包器 / 校验器 / Schema）与应用端（Kotlin 导入 / 判题 / 作答）均已实现并双端对齐；
> 正式并入 1.1 主文档前，生产制包建议仍以 §6 四题型为主。

### A.1 语义重组（type: ordering）

结构层：

```json
{"questionId":"r001","type":"ordering",
 "optionIds":["f1","f2","f3","f4"],
 "correctOptionIds":["f1","f2","f3"],
 "grading":{"strategy":"ordering","alternatives":[["f2","f1","f3"]]}}
```

- 片段 = 选项（含干扰项：不在 `correctOptionIds` 中的选项），文案层与选择题同构（`options[].optionId/contentMarkdown`）。
- `correctOptionIds` 的**书写顺序即标准次序**（canonical）。
- `grading.alternatives` 是作者声明的逻辑等价次序，判题时与 canonical 并列认可。
- 校验：正确片段 ≥ 2；正确集合 ⊆ optionIds；每个 alternative 的集合必须与正确集合一致；`shuffleAllowed` 必须为 false。

### A.2 默知识点（short_answer + strategy: keywords）

结构层简答题可选声明关键词规则：

```json
{"questionId":"k001","type":"short_answer",
 "grading":{"strategy":"keywords",
            "required":["实践","能动","改造"],
            "forbidden":["被动"]}}
```

- 语义：`required` 全命中 → CORRECT；部分命中 → PARTIALLY_CORRECT；命中任一 `forbidden` → INCORRECT；
  规则为空 → **PENDING**（无可靠依据不得默认判错）。
- 关键词匹配为大小写不敏感的子串包含；不做同义、词形还原等语义处理 —— 保持确定性、可审计。
- 简答题不声明 `grading` 或声明 `strict-text` 时，维持 §6 的逐字符默写语义，不受本扩展影响。
