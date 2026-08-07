// SoundEffectManageSection.kt — "我的音效"管理区（Compose UI）
// ============================================================
// 功能：
//   1. 区块标题行："我的音效" + "导入音频" / "导入音效包" 两个按钮
//      （OpenDocument 系统选择器，audio/* 与 application/zip 两个 launcher）。
//   2. 搜索框：按名称实时过滤。
//   3. 列表：按分组展示（每组一个小节标题，如 "未分组"），
//      每条卡片显示 名称 / 时长(mm:ss) / 大小(KB/MB)。
//   4. 点击卡片试听：播放中再点停止；播放中卡片高亮。
//   5. 每张卡片提供 重命名 / 移动分组 / 删除（AlertDialog 交互）。
//   6. 空态：无音效时居中灰字提示。
//
// 依赖：
//   SoundEffectStore  —— loadAll / delete / rename / updateGroup / groups / effectFile
//   SoundEffectImporter —— importFromUri（单音频）/ importFromZip（zip 音效包）
//   SoundEffectPlayer —— play(file, onComplete) / stop()，MediaPlayer 媒体音量流外放
//
// UI 风格：深色主题，与 EffectsLibraryPage 一致（MaterialTheme MaidMicDarkColors：
//   primary=#CE93D8 紫罗兰 / secondary=#80CBC4 青 / surface=#1C1B1F / surfaceVariant=#2A2930）。
//
// 注意：本区块作为 EffectsLibraryPage（外层 LazyColumn）的单个 item 展开，
//   不使用内部滚动（音效通常不会太多，整页滚动由外层 LazyColumn 负责）。

package aoeck.dwyai.com.ui

import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.soundeffect.ImportResult
import aoeck.dwyai.com.soundeffect.SoundEffect
import aoeck.dwyai.com.soundeffect.SoundEffectImporter
import aoeck.dwyai.com.soundeffect.SoundEffectPlayer
import aoeck.dwyai.com.soundeffect.SoundEffectStore
import aoeck.dwyai.com.voicepack.formatDuration
import aoeck.dwyai.com.ui.components.SectionHeader
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ============================================================
// 颜色常量（与 VoicePackListPage / FloatingPanel 对齐）
// ============================================================

private val SurfacePlayColor = Color(0xFF2A1A2E)  // 播放中卡片底色
private val TextMainColor = Color(0xFFE6E1E5)
private val TextSubColor = Color(0xFFCAC4D0)
private val TextDimColor = Color(0xFF888888)
private val TextFadeColor = Color(0xFF666666)
private val DangerColor = Color(0xFFF2B8B5)

// ============================================================
// 辅助：文件大小格式化
// ============================================================

/** 字节数 → 人类可读大小："512 B" / "12 KB" / "1.5 MB"。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes >= 1024 * 1024 ->
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024 ->
        String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

/** 导入结果 → Toast 文案（成功 / 失败 / 错误信息）。 */
private fun showImportToast(context: Context, result: ImportResult, sourceLabel: String) {
    val msg = when {
        result.errorMessage != null -> "$sourceLabel：${result.errorMessage}"
        result.successCount > 0 && result.failureCount > 0 ->
            "$sourceLabel：成功 ${result.successCount} 条，失败 ${result.failureCount} 条"
        result.successCount > 0 -> "$sourceLabel：成功导入 ${result.successCount} 条"
        else -> "$sourceLabel：导入失败"
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

// ============================================================
// 主 Composable：我的音效管理区
// ============================================================

/**
 * "我的音效"管理区（深色主题，与 EffectsLibraryPage 配色一致）。
 *
 * 作为 EffectsLibraryPage 外层 LazyColumn 的一个 item 直接展开（不内嵌滚动）。
 * 内部管理：导入 / 搜索 / 分组展示 / 试听播放 / 重命名 / 移动分组 / 删除。
 *
 * @param context 应用上下文（用于导入 / 加载列表 / 播放 / Toast）。
 * @param modifier Compose 修饰符。
 */
@Composable
fun SoundEffectManageSection(context: Context, modifier: Modifier = Modifier) {
    // ---- 状态 ----
    // 音效列表：首次组合时加载（loadAll 按 createdAt 升序）
    var effects by remember { mutableStateOf(SoundEffectStore.loadAll(context)) }
    // 搜索关键字
    var searchQuery by remember { mutableStateOf("") }
    // 当前播放中的音效 id（null = 未播放）
    var playingEffectId by remember { mutableStateOf<String?>(null) }
    // 音效播放器（MediaPlayer 外放，与悬浮窗"我的音效"一致）
    val effectPlayer = remember { SoundEffectPlayer() }

    // 对话框状态
    var deleteTarget by remember { mutableStateOf<SoundEffect?>(null) }
    var renameTarget by remember { mutableStateOf<SoundEffect?>(null) }
    var renameText by remember { mutableStateOf("") }
    var groupTarget by remember { mutableStateOf<SoundEffect?>(null) }

    // 退出组合（页面离开）时停止播放并释放 MediaPlayer
    DisposableEffect(effectPlayer) {
        onDispose {
            effectPlayer.stop()
        }
    }

    // ---- 刷新列表 ----
    fun reload() {
        effects = SoundEffectStore.loadAll(context)
        AppLogger.i("SoundEffectManage", "reload: ${effects.size} 条音效")
    }

    // ---- 播放控制（与悬浮窗"我的音效"逻辑一致）----
    fun togglePlay(effect: SoundEffect) {
        if (playingEffectId == effect.id) {
            // 正在播放该条 → 停止（stop 会回调 onComplete 清空状态，这里先显式置 null 双保险）
            AppLogger.i("SoundEffectManage", "停止音效: ${effect.name}")
            effectPlayer.stop()
            playingEffectId = null
        } else {
            // 播放该条：先记录目标 id 再 play。
            // play 内部会先停掉旧播放（不触发旧回调，令牌机制兜底），
            // 因此切换曲目不会出现"旧回调把新状态清掉"的竞态。
            AppLogger.i("SoundEffectManage", "播放音效: ${effect.name}")
            playingEffectId = effect.id
            val file = SoundEffectStore.effectFile(context, effect.fileName)
            effectPlayer.play(file) {
                // onComplete 在自然播完 / 出错 / 停止时由主线程回调 → 清空播放中状态
                playingEffectId = null
            }
        }
    }

    // ---- 删除 ----
    fun doDelete(effect: SoundEffect) {
        // 若正在播放该条，先停止
        if (playingEffectId == effect.id) {
            effectPlayer.stop()
            playingEffectId = null
        }
        val ok = SoundEffectStore.delete(context, effect.id)
        Toast.makeText(
            context,
            if (ok) "已删除「${effect.name}」" else "删除失败",
            Toast.LENGTH_SHORT
        ).show()
        reload()
    }

    // ---- 重命名 ----
    fun doRename(effect: SoundEffect, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = SoundEffectStore.rename(context, effect.id, trimmed)
        Toast.makeText(context, if (ok) "已重命名" else "重命名失败", Toast.LENGTH_SHORT).show()
        reload()
    }

    // ---- 移动分组 ----
    fun doMoveGroup(effect: SoundEffect, newGroup: String) {
        val ok = SoundEffectStore.updateGroup(context, effect.id, newGroup)
        Toast.makeText(context, if (ok) "已移动到「$newGroup」" else "移动分组失败", Toast.LENGTH_SHORT).show()
        reload()
    }

    // ---- 导入 launcher ----
    // 音频导入：mime "audio/*"
    val audioImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            AppLogger.i("SoundEffectManage", "导入音频: $uri")
            val result = SoundEffectImporter.importFromUri(context, uri)
            showImportToast(context, result, "导入音频")
            reload()
        }
    }
    // 音效包导入：mime "application/zip"
    val zipImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            AppLogger.i("SoundEffectManage", "导入音效包: $uri")
            val result = SoundEffectImporter.importFromZip(context, uri)
            showImportToast(context, result, "导入音效包")
            reload()
        }
    }

    // ---- 搜索过滤（实时按名称包含匹配，忽略大小写）----
    val filtered = remember(effects, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) effects
        else effects.filter { it.name.contains(q, ignoreCase = true) }
    }
    // 按分组组织（groupBy 保留首次出现顺序，组内保持 loadAll 顺序）
    val grouped = remember(filtered) { filtered.groupBy { it.group } }

    // 文本输入框统一配色（深色主题）
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedTextColor = TextMainColor,
        unfocusedTextColor = TextMainColor,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedPlaceholderColor = TextDimColor,
        unfocusedPlaceholderColor = TextDimColor,
        focusedLabelColor = TextSubColor,
        unfocusedLabelColor = TextDimColor
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- 标题行：我的音效 + 导入按钮 ----
        SectionHeader(
            title = "我的音效",
            icon = Icons.Default.MusicNote,
            trailing = {
                // 导入音频（单文件，audio/*）
                OutlinedButton(
                    onClick = { audioImporter.launch(arrayOf("audio/*")) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("导入音频", fontSize = 12.sp, color = TextMainColor)
                }
                Spacer(Modifier.width(8.dp))
                // 导入音效包（zip 批量）
                OutlinedButton(
                    onClick = { zipImporter.launch(arrayOf("application/zip")) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("导入音效包", fontSize = 12.sp, color = TextMainColor)
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        // ---- 搜索框 ----
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索音效", fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = TextDimColor,
                    modifier = Modifier.size(18.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = tfColors
        )

        Spacer(Modifier.height(10.dp))

        // ---- 列表 / 空态 ----
        when {
            effects.isEmpty() -> {
                // 无任何音效
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.MusicOff,
                        contentDescription = null,
                        tint = TextFadeColor,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("还没有音效，点击上方导入", fontSize = 13.sp, color = TextDimColor)
                }
            }
            filtered.isEmpty() -> {
                // 有音效但搜索无结果
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("没有找到匹配的音效", fontSize = 13.sp, color = TextDimColor)
                }
            }
            else -> {
                // 按分组展示
                grouped.forEach { (group, list) ->
                    // 分组小节标题
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            group,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSubColor,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${list.size} 条", fontSize = 11.sp, color = TextFadeColor)
                    }
                    // 组内音效卡片
                    list.forEach { effect ->
                        SoundEffectCard(
                            effect = effect,
                            isPlaying = playingEffectId == effect.id,
                            onTogglePlay = { togglePlay(effect) },
                            onRename = {
                                renameText = effect.name
                                renameTarget = effect
                            },
                            onMoveGroup = { groupTarget = effect },
                            onDelete = { deleteTarget = effect }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // ---- 删除确认对话框 ----
    deleteTarget?.let { effect ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除音效", color = TextMainColor) },
            text = {
                Text(
                    "确定删除「${effect.name}」？\n音频文件将被一并删除，此操作无法撤销。",
                    color = TextSubColor,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    doDelete(effect)
                    deleteTarget = null
                }) {
                    Text("删除", color = DangerColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = TextDimColor)
                }
            }
        )
    }

    // ---- 重命名输入对话框 ----
    renameTarget?.let { effect ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名音效", color = TextMainColor) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("名称", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColors
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    doRename(effect, renameText)
                    renameTarget = null
                }) {
                    Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消", color = TextDimColor)
                }
            }
        )
    }

    // ---- 移动分组对话框（列出全部分组，含"未分组"，点击选择）----
    groupTarget?.let { effect ->
        // 分组选项：SoundEffectStore.groups() 全部分组，不足"未分组"时补在首位
        val groupOptions = remember(effect.id) {
            val list = SoundEffectStore.groups(context).toMutableList()
            if (SoundEffect.DEFAULT_GROUP !in list) list.add(0, SoundEffect.DEFAULT_GROUP)
            list
        }
        AlertDialog(
            onDismissRequest = { groupTarget = null },
            title = {
                Text(
                    "移动分组：${effect.name}",
                    color = TextMainColor,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groupOptions.forEach { group ->
                        val selected = effect.group == group
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    doMoveGroup(effect, group)
                                    groupTarget = null
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                group,
                                fontSize = 14.sp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    TextMainColor
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "当前分组",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupTarget = null }) {
                    Text("取消", color = TextDimColor)
                }
            }
        )
    }
}

// ============================================================
// 单条音效卡片
// ============================================================

/**
 * 单个音效的展示卡片。
 *
 * 整卡可点击（播放 / 停止），播放中高亮；
 * 右侧三个小图标按钮：重命名 / 移动分组 / 删除。
 *
 * @param effect 音效数据。
 * @param isPlaying 当前是否正在播放该条（控制图标 + 卡片高亮）。
 * @param onTogglePlay 点击卡片播放 / 停止。
 * @param onRename 点击重命名。
 * @param onMoveGroup 点击移动分组。
 * @param onDelete 点击删除。
 */
@Composable
private fun SoundEffectCard(
    effect: SoundEffect,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onRename: () -> Unit,
    onMoveGroup: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePlay() },
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) SurfacePlayColor else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放 / 停止图标
            Icon(
                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isPlaying) MaterialTheme.colorScheme.primary else TextDimColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            // 名称 + 元信息（时长 mm:ss · 大小 KB/MB）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    effect.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else TextMainColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = buildString {
                    if (effect.durationMs > 0) {
                        append(formatDuration(effect.durationMs))
                        append(" · ")
                    }
                    append(formatFileSize(effect.fileSizeBytes))
                }
                Text(
                    meta,
                    fontSize = 11.sp,
                    color = TextDimColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 操作按钮：重命名 / 移动分组 / 删除
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名",
                    tint = TextFadeColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onMoveGroup, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.DriveFileMove,
                    contentDescription = "移动分组",
                    tint = TextFadeColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = TextFadeColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
