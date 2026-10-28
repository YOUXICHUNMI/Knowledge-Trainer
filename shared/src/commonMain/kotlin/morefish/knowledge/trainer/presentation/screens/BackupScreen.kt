package morefish.knowledge.trainer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import morefish.knowledge.trainer.application.port.RestoreRecoveryKind
import morefish.knowledge.trainer.domain.backup.BackupCryptoIds
import morefish.knowledge.trainer.domain.backup.BackupProblem
import morefish.knowledge.trainer.domain.backup.RefGap
import morefish.knowledge.trainer.presentation.AppController
import morefish.knowledge.trainer.presentation.BackupIntake
import morefish.knowledge.trainer.presentation.theme.Palette
import morefish.knowledge.trainer.presentation.ui.BannerHost
import morefish.knowledge.trainer.presentation.ui.Divider
import morefish.knowledge.trainer.presentation.ui.GhostButton
import morefish.knowledge.trainer.presentation.ui.KtChip
import morefish.knowledge.trainer.presentation.ui.Panel
import morefish.knowledge.trainer.presentation.ui.PrimaryButton
import morefish.knowledge.trainer.presentation.ui.ScreenScaffold
import morefish.knowledge.trainer.presentation.ui.SecondaryButton
import morefish.knowledge.trainer.presentation.ui.SectionTitle
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 备份与迁移（`.qbk`）。
 *
 * 这一页是"数据不在云上"的代价与补偿：没有账号、没有同步，换设备只能靠一个文件。
 * 所以页面要回答的只有三个问题 —— **导出的是什么、恢复会覆盖什么、覆盖之后还能不能退回去。**
 *
 * 四个刻意的取舍：
 *
 * 1. **恢复不是主按钮。** 导出是安全动作（多一份拷贝），恢复是危险动作（覆盖本机）。
 *    两个做成同样重量，等于把"确认覆盖"训练成肌肉记忆。
 * 2. **恢复前必须摊开清单。** 时间、来源设备、里面有几个题库几条历史，
 *    先看再看本机现在有多少 —— 然后才是那个按钮。
 * 3. **加密是可选项，两边的说明都得在场。** 不加密时用琥珀块直说"谁拿到文件谁能看"；
 *    加密时换成"忘记口令就打不开"，并把 KDF/密码写出来。默认关，因为默认加密
 *    会让"备份完了却打不开"成为最常见的失败。
 * 4. **"要口令"不是错误。** 加密备份读进来只拿到信封，界面给输入框；
 *    输错也只说"解不开" —— 分不清口令错还是文件坏，是密码学的性质。
 */
@Composable
fun BackupScreen(controller: AppController) {
    ScreenScaffold(
        title = "备份与迁移",
        subtitle = "整份数据打成一个文件带走；或者用一份备份把本机整体换掉",
        onBack = controller::closeBackup,
    ) {
        BannerHost(controller.banner) { controller.dismissBanner() }

        // 上次恢复被打断：写在最上面，因为"现在生效的是哪一份数据"比什么都重要
        if (controller.restoreRecovery != null) {
            RecoveryCard(controller)
            Spacer(Modifier.height(18.dp))
        }

        if (!controller.backupSupported) {
            Panel(background = Palette.PanelMuted) {
                Text("这个平台没有可备份的数据", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    controller.backupUnavailableReason ?: "数据不在磁盘上，关掉应用就会消失",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "备份做的是「把磁盘上的文件原样打包」。桌面端的数据写在用户目录下，所以只有那里有备份入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
            return@ScreenScaffold
        }

        ExportPanel(controller)

        Spacer(Modifier.height(18.dp))
        AuditPanel(controller)

        Spacer(Modifier.height(18.dp))
        Divider()
        Spacer(Modifier.height(18.dp))

        RestorePanel(controller)

        Spacer(Modifier.height(18.dp))
        Text(
            "备份是给你自己的：它不做内容校验，也不判断题目对不对 —— 只保证把文件原样搬回去。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

/**
 * "上一次恢复被打断"的卡片。
 *
 * 放在最上面，因为它回答的是**这一页最要紧的那个问题**：现在生效的到底是哪一份数据。
 * 六种情况的底色不同（需要动手的用错误色），但都写清三件事：
 * 发生了什么、现在数据在哪、旧数据在哪（要退回去就自己把它改回原位）。
 *
 * 为什么不自动消失：这是"你的数据刚才差点出事"的记录。用户看没看过，
 * 只有他自己知道；替他决定"这条不重要"就是在替他判断他的数据重不重要。
 */
@Composable
private fun RecoveryCard(controller: AppController) {
    val recovery = controller.restoreRecovery ?: return
    val error = controller.restoreRecoveryIsError
    val title = when (recovery.kind) {
        RestoreRecoveryKind.ROLLED_BACK -> "上次恢复被打断：本机数据没被动过"
        RestoreRecoveryKind.ROLLED_BACK_AFTER_SWAP -> "上次恢复被打断：已经退回原来的数据"
        RestoreRecoveryKind.ROLLED_FORWARD -> "上次恢复被打断：备份里的数据已经接上"
        RestoreRecoveryKind.ALREADY_DONE -> "上次恢复其实已经完成了"
        RestoreRecoveryKind.DATA_MISSING -> "上次恢复被打断，而且数据目录不见了"
        RestoreRecoveryKind.JOURNAL_UNREADABLE -> "发现一份读不懂的恢复日志"
        RestoreRecoveryKind.RECOVERY_FAILED -> "收拾上次的恢复现场时出错"
    }
    val body = when (recovery.kind) {
        RestoreRecoveryKind.ROLLED_BACK ->
            "恢复在写新数据的时候断了（断电、被杀掉、磁盘满都算）。没写完的部分（如果有）已经清掉 —— " +
                "本机数据一个字节都没动，你现在看到的就是原来那份。"
        RestoreRecoveryKind.ROLLED_BACK_AFTER_SWAP ->
            "旧数据已经换下来、新数据还没接上就断了。现在已经把原来的数据放回原位，" +
                "也就是恢复之前的样子。要重新恢复，再走一遍就行。"
        RestoreRecoveryKind.ROLLED_FORWARD ->
            "新数据其实已经写完并逐个文件核对过了，只差最后「换上去」那一下。现在它已经生效 —— " +
                "当前数据目录里就是那份备份的内容。"
        RestoreRecoveryKind.ALREADY_DONE ->
            "上次恢复做完了，只是没来得及收尾（删掉恢复日志）。现在生效的就是那份备份，" +
                "被换下来的旧数据还在旁边。"
        RestoreRecoveryKind.DATA_MISSING ->
            "数据目录不见了，而且没有可以自动放回去的旧数据。别自己删任何东西 —— " +
                "先按下面的路径去看一眼，那里通常还找得回来。"
        RestoreRecoveryKind.JOURNAL_UNREADABLE ->
            "恢复日志读不懂（可能写到一半被改坏）。正因为看不懂，所以什么都没敢动 —— " +
                "本机数据还是你上次看到的样子。那份日志没有删：它是当时唯一的证据。"
        RestoreRecoveryKind.RECOVERY_FAILED ->
            "收拾现场时出错了。为了不造成第二次损坏，什么都没有动 —— " +
                "本机数据还是你上次看到的样子；下次启动会再试一次。"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (error) Palette.WrongSoft else Palette.PanelMuted)
            .padding(14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (error) Palette.Wrong else Palette.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        Spacer(Modifier.height(8.dp))
        Text(
            "现在生效的数据：${recovery.dataLocation}" +
                (recovery.filesInPlace?.let { " · $it 个文件" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        recovery.previousLocation?.let { previous ->
            Spacer(Modifier.height(2.dp))
            Text(
                "被换下来的旧数据：$previous（需要退回去就把它改回原位）",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        recovery.detail?.let { detail ->
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.height(8.dp))
        GhostButton("知道了", onClick = controller::dismissRestoreRecovery)
    }
}

/**
 * 引用自检：这份数据里**谁指着谁、指的那个东西还在不在**。
 *
 * 三条写死在这一屏的原则：
 *
 * 1. **只报告，不拦截。** 指空的引用是既有状态，不是打包失败。为它挡住导出，
 *    等于因为"有十条关系没接上"就不让用户拿到唯一的退路。
 * 2. **按目标聚合，不按记录罗列。** 十条关系指着同一个没装的题库，用户要处理的是
 *    "一个题库"，不是"十条关系"。按记录列出来只会让人以为问题有十条那么大。
 * 3. **接不上不等于坏。** 卸载题库、更新题库删掉题，都会让引用暂时指空 ——
 *    所以底色用琥珀而不是红，文案里明说装上同 bankId 的题库会自动接上。
 */
@Composable
private fun AuditPanel(controller: AppController) {
    val audit = controller.backupAudit
    val at = controller.backupAuditAtMillis

    SectionTitle("引用自检", at?.let { "核对于 ${formatStamp(it)}" })
    Spacer(Modifier.height(10.dp))

    if (audit == null) {
        Panel(background = Palette.PanelMuted) {
            if (controller.backupBusy) {
                Text("正在核对引用…", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "把图谱关系、笔记、扩展包绑定、历史记录各自指向的题库与题目对一遍，" +
                        "顺便看看题目里写的图片在不在。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                return@Panel
            }
            Text("核对这份数据里的引用有没有指空", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "看的是「谁指着谁」：图谱关系、笔记、扩展包绑定、历史记录分别指向哪个题库或哪道题，" +
                    "以及题目里写的图片在这份数据里到底有没有那一张。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "进这一页时会自动核对一次；收起之后也可以在这里重新跑。它只报告、不拦截 —— " +
                    "指空的引用不该成为导不出备份的理由。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                "核对一次",
                onClick = controller::recheckBackupAudit,
                enabled = !controller.backupBusy,
            )
        }
        return
    }

    val clean = audit.isClean
    Panel(background = if (clean) Palette.CorrectSoft else Palette.PendingSoft) {
        Text(
            audit.summary,
            style = MaterialTheme.typography.titleSmall,
            color = if (clean) Palette.Correct else Palette.Pending,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // 零项不写：一行"0 个扩展包 · 历史覆盖 0 个题库"只是噪声，
            // 而缺了哪一项又让人以为没核对它。
            buildList {
                add("${audit.banks} 个题库")
                add("${audit.questions} 道题")
                if (audit.graphEdges > 0) add("${audit.graphEdges} 条图谱关系")
                if (audit.notes > 0) add("${audit.notes} 条笔记")
                if (audit.extensionPacks > 0) add("${audit.extensionPacks} 个扩展包")
                if (audit.historyBanks > 0) add("历史覆盖 ${audit.historyBanks} 个题库")
            }.joinToString(" · ").let { "看了：$it" },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )

        if (audit.gaps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "接不上的目标（${audit.gapCount} 个，指向同一个目标的已合并成一条）",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Ink,
            )
            Spacer(Modifier.height(6.dp))
            audit.gaps.take(AUDIT_MAX_ROWS).forEach { gap -> GapRow(gap) }
            if (audit.gaps.size > AUDIT_MAX_ROWS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "还有 ${audit.gaps.size - AUDIT_MAX_ROWS} 个没列出来 —— 上面的数字是全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }

        if (audit.mediaGaps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "题目里写了、但这份数据里没有的图（${audit.mediaGaps.size} 张）",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Ink,
            )
            Spacer(Modifier.height(6.dp))
            audit.mediaGaps.take(AUDIT_MAX_ROWS).forEach { gap ->
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(
                        "${gap.assetId}" + (gap.bankTitle?.let { " · 《$it》" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.Ink,
                    )
                    Text(
                        "被 ${gap.questionCount} 道题引用，图片文件不在数据里",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
            }
            if (audit.mediaGaps.size > AUDIT_MAX_ROWS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "还有 ${audit.mediaGaps.size - AUDIT_MAX_ROWS} 张没列出来",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }

        // 干净的时候不再补一句"没有接不上的引用，也没有缺图"：
        // 上面那句 summary 已经说了同样的事，重复一遍只会让人怀疑两句话是不是在说不同的东西。

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                "重新核对",
                onClick = controller::recheckBackupAudit,
                enabled = !controller.backupBusy,
            )
            Spacer(Modifier.width(10.dp))
            GhostButton("收起", onClick = controller::dismissBackupAudit)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "接不上不等于坏：题库被卸载、题库更新时删了题，都会让一条引用暂时指空。" +
                "装上 bankId 相同的题库，它会自己接上；题目级的引用不会被悄悄抹掉 —— " +
                "那是用户自己建过的东西。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

/** 一条接不上的引用：目标是什么、谁指着它、几处、以及该怎么办。 */
@Composable
private fun GapRow(gap: RefGap) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            gap.target,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(2.dp))
        val owners = gap.owners.joinToString("、") { it.label }
        Text(
            "${gap.kind.label} · $owners 共 ${gap.occurrences} 处",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            gap.kind.hint,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
    }
}

/** 列出来的行数上限：剩下的用一句"还有 N 个"带过，免得一屏全变成清单。 */
private const val AUDIT_MAX_ROWS = 5

@Composable
private fun ExportPanel(controller: AppController) {
    val summary = controller.backupSummary
    val exported = controller.backupExport

    val encrypt = controller.backupEncrypt
    val passphrase = controller.backupExportPassphrase
    val confirm = controller.backupExportConfirm
    val acknowledged = controller.backupExportAck

    val minChars = BackupCryptoIds.MIN_PASSPHRASE_CHARS
    val tooShort = passphrase.length < minChars
    val mismatch = confirm.isNotEmpty() && confirm != passphrase

    SectionTitle("导出", if (summary.isEmpty) "本机还没有数据" else null)
    Spacer(Modifier.height(10.dp))

    Panel {
        Text(
            "把题库、作答历史、复核决定、笔记、图谱、扩展包与 AI 记录一起打包成一个 .qbk 文件。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "本机：${summary.banks} 个题库 · ${summary.questions} 道题 · ${summary.attempts} 条历史 · " +
                "${summary.notes} 条笔记" + if (summary.reviews > 0) " · ${summary.reviews} 条复核决定" else "",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "数据位置：${controller.backupDataLocation}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KtChip(
                label = if (encrypt) "加密：开" else "加密：关",
                selected = encrypt,
                onClick = { controller.toggleBackupEncrypt() },
                enabled = !controller.backupBusy,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (encrypt) "只有输对口令的人能打开这份文件" else "任何人拿到这个文件都能看到全部内容",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }

        if (encrypt) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = controller::updateBackupExportPassphrase,
                label = { Text("口令（至少 $minChars 个字符）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = passFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = controller::updateBackupExportConfirm,
                label = { Text("再输一次") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = mismatch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = passFieldColors(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    tooShort -> "还差 ${minChars - passphrase.length} 个字符"
                    mismatch -> "两次输入不一样"
                    else -> "四个不相关的词（例如「自行车-海豚-毛线-九月」）比一个复杂但短的词更牢"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (mismatch) Palette.Wrong else Palette.Faint,
            )
            Spacer(Modifier.height(8.dp))
            KtChip(
                label = if (acknowledged) "我知道：忘记口令就打不开" else "请确认：忘记口令就打不开",
                selected = acknowledged,
                onClick = { controller.updateBackupExportAck(!acknowledged) },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = when {
                    controller.backupBusy && encrypt -> "正在加密打包…"
                    controller.backupBusy -> "正在打包…"
                    encrypt -> "导出加密备份"
                    else -> "导出备份"
                },
                onClick = controller::exportBackup,
                enabled = !controller.backupBusy && controller.backupExportBlockReason == null,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                controller.backupExportBlockReason ?: "打包后会让选保存位置",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
        }

        if (exported != null) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.CorrectSoft)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已导出 ${exported.fileName}", style = MaterialTheme.typography.titleSmall, color = Palette.Correct)
                    if (exported.encrypted) {
                        Spacer(Modifier.width(8.dp))
                        EncryptedBadge()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${exported.fileCount} 个文件 · 文件大小 ${formatBytes(exported.bytes)} · " +
                        formatStamp(exported.createdAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "内容摘要 ${exported.digest.take(16)}…",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Palette.Faint,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (exported.encrypted) {
                        "摘要在密文里重算得出来，所以两台机器导出同一份数据仍然可以对上；" +
                            "但加密备份每次的盐和 nonce 不同，文件字节本身不会一样。"
                    } else {
                        "在另一台机器上导出同一份数据，摘要会一样 —— 可以用它核对两份备份是不是同一份内容。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        if (encrypt) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.PanelMuted)
                    .padding(12.dp),
            ) {
                Text("加密之后只有口令能打开", style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "口令 → PBKDF2-HMAC-SHA256（600000 轮）→ AES-256-GCM。口令不写进这个文件，" +
                        "软件也不保存它 —— 关掉这一屏就没有了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "忘记口令就打不开：没有找回、没有后门、没有提示问题。这是加密的定义，不是缺陷。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Wrong,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "明文里只剩几个统计数字（几个题库、几条历史）；文件名与每个文件的摘要都在密文里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Faint,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.PendingSoft)
                    .padding(12.dp),
            ) {
                Text("备份没有加密", style = MaterialTheme.typography.titleSmall, color = Palette.Pending)
                Spacer(Modifier.height(4.dp))
                Text(
                    "能打开这个文件的人就能看到你的全部题目、笔记与作答记录。别放到公共网盘、群聊或别人的电脑上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }
    }
}

/** 口令输入框：与 AI 页的密钥框同一套配色，不另造一种"安全输入"的观感。 */
@Composable
private fun passFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.PanelBorderStrong,
    focusedContainerColor = Palette.Panel,
    unfocusedContainerColor = Palette.Panel,
    cursorColor = Palette.Accent,
    focusedLabelColor = Palette.Accent,
    unfocusedLabelColor = Palette.Muted,
)

@Composable
private fun EncryptedBadge() {
    Text(
        "已加密",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.AccentSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RestorePanel(controller: AppController) {
    val intake = controller.backupIntake
    val summary = controller.backupSummary

    SectionTitle("恢复", if (intake == null) "会整体替换本机数据" else null)
    Spacer(Modifier.height(10.dp))

    Panel {
        Text(
            "选一份 .qbk，先看清里面有什么，再决定要不要换掉本机的数据。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "也可以直接把 .qbk 拖进窗口。恢复之前一个字节都不会写；恢复失败会回滚，本机数据原样不动。",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                text = if (controller.backupBusy) "读取中…" else "选择备份文件",
                onClick = controller::pickBackupFile,
                enabled = !controller.backupBusy,
            )
            if (intake != null) {
                Spacer(Modifier.width(10.dp))
                GhostButton("取消", onClick = controller::cancelRestore)
            }
        }

        if (intake != null) {
            Spacer(Modifier.height(14.dp))
            IntakeCard(intake, summary.banks, summary.attempts, controller)
        }
    }
}

@Composable
private fun IntakeCard(
    intake: BackupIntake,
    localBanks: Int,
    localAttempts: Int,
    controller: AppController,
) {
    val manifest = intake.preview?.manifest
    val tint = when {
        intake.canRestore -> Palette.PanelMuted
        intake.requiresPassphrase -> Palette.PanelMuted
        else -> Palette.WrongSoft
    }
    val encrypted = manifest?.encrypted == true
    var passphrase by remember(intake) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tint)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                intake.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (encrypted) {
                Spacer(Modifier.width(8.dp))
                EncryptedBadge()
            }
        }

        if (manifest == null) {
            Spacer(Modifier.height(6.dp))
            intake.problems.forEach { problem ->
                Text("· ${problem.message}", style = MaterialTheme.typography.bodySmall, color = Palette.Wrong)
            }
            return@Column
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "${formatStamp(manifest.createdAtMillis)} 于 ${manifest.deviceLabel.ifBlank { "未知设备" }} 导出" +
                (if (manifest.appVersion.isBlank()) "" else " · 应用 ${manifest.appVersion}"),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(2.dp))
        // 未解锁时清单里没有文件明细（明细在密文里），只能报统计。
        val fileCount = if (manifest.files.isEmpty()) manifest.counts.totalFiles else manifest.files.size
        val rawBytes = if (manifest.files.isEmpty()) manifest.counts.totalBytes else manifest.files.sumOf { it.bytes }
        Text(
            "备份格式 v${manifest.backupVersion} · 数据布局 v${manifest.layoutVersion} · " +
                "$fileCount 个文件 · 原始数据 ${formatBytes(rawBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Faint,
        )

        Spacer(Modifier.height(10.dp))
        val counts = manifest.counts
        Text(
            "里面有：${counts.banks} 个题库 · ${counts.questions} 道题 · ${counts.attempts} 条历史 · " +
                "${counts.notes} 条笔记" +
                (if (counts.reviews > 0) " · ${counts.reviews} 条复核决定" else "") +
                (if (counts.mediaFiles > 0) " · ${counts.mediaFiles} 张图片" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "本机现在：$localBanks 个题库 · $localAttempts 条历史",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )

        // 加密但没解锁（或解锁失败）：给一个输入框，而不是一句"不能用"。
        if (encrypted && !intake.canRestore) {
            Spacer(Modifier.height(12.dp))
            Text(
                "这份备份用口令加密了。口令不对、文件被改过、写到一半断掉 —— 这三种情况都会解不开，" +
                    "而且在密码学上分不出是哪一种。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = passFieldColors(),
                )
                Spacer(Modifier.width(10.dp))
                SecondaryButton(
                    text = if (controller.backupBusy) "解锁中…" else "解锁",
                    onClick = { controller.unlockBackup(passphrase) },
                    enabled = !controller.backupBusy && passphrase.isNotEmpty(),
                )
            }
            // "还没给口令"不算失败，别把它打成红字。
            val realProblems = intake.problems.filter { it.code != BackupProblem.PASSPHRASE_REQUIRED }
            if (realProblems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                realProblems.take(4).forEach { problem ->
                    Text(
                        "· ${problem.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Wrong,
                    )
                }
            }
        } else if (intake.problems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("这份备份不能用：", style = MaterialTheme.typography.bodySmall, color = Palette.Wrong)
            intake.problems.take(6).forEach { problem ->
                Text(
                    "· [${problem.code}] ${problem.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Wrong,
                )
            }
            if (intake.problems.size > 6) {
                Text(
                    "· 还有 ${intake.problems.size - 6} 条没有列出来",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Wrong,
                )
            }
        }

        if (intake.canRestore) {
            if (encrypted) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "已解锁 · AES-256-GCM 校验通过：这份文件没有被截断，也没有被改过任何一个字节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Correct,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(
                    text = if (controller.backupBusy) "正在恢复…" else "用这份备份替换本机数据",
                    onClick = controller::confirmRestore,
                    enabled = !controller.backupBusy,
                )
                Spacer(Modifier.width(12.dp))
                Text("本机现有数据会被移到旁边的目录，不删除", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            }
        }
    }
}

/** 绝对时间比"3 分钟前"更适合备份：三个月前那份备份叫什么，用户得看得懂。 */
@OptIn(ExperimentalTime::class)
private fun formatStamp(millis: Long): String {
    if (millis <= 0) return "未记录时间"
    val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun pad(v: Int) = if (v < 10) "0$v" else "$v"
    return "${local.year}-${pad(local.month.ordinal + 1)}-${pad(local.day)} ${pad(local.hour)}:${pad(local.minute)}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    bytes >= 1024L * 1024 -> {
        val mb = bytes / (1024L * 1024)
        val frac = (bytes % (1024L * 1024)) * 10 / (1024L * 1024)
        if (frac == 0L) "$mb MB" else "$mb.$frac MB"
    }
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
