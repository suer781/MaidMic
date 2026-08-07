// VoicePackListPage.kt — 语音包列表（Compose UI）
// ============================================================
// Task 6: 语音包列表 UI + 外放 + 分享 + 删除/重命名
//
// 功能：
//   6.1 列表 UI：展示所有语音包（按 createdAt 降序），
//        每条卡片显示 名称 / 时长(mm:ss) / 创建时间(相对) / 效果链摘要。
//   6.2 外放播放：内置 VoicePackPlayer（AudioTrack USAGE_MEDIA，不调系统音量）。
//        点击播放，再次点击停止；播放中卡片高亮。
//   6.3 分享：Intent.ACTION_SEND + FileProvider content URI，type=audio/wav。
//   6.4 删除/重命名：带确认/输入对话框，操作后刷新列表。
//   6.5 效果链摘要：ChainSnapshot.toSummary() 扩展函数。
//
// 化繁为简（2026-08 清理）：
//   - 卡片不再强制 13:8 宽高比，高度随内容自适应（原 13:8 全宽卡 ≈221dp，
//     信息量却很少，属于"空占大块可操作区"）。
//   - 新增 VoicePackSection 扁平区块：无内嵌滚动、无固定高度，
//     供 EffectsLibraryPage 直接嵌入，避免 LazyColumn 套 LazyColumn。
//   - VoicePackListPage 与 VoicePackSection 共用 VoicePackListState。
//
// UI 风格：深色主题，与 MaidMicDarkColors 配色一致
//   （primary=#CE93D8 紫罗兰 / secondary=#80CBC4 青 / surface=#1C1B1F / surfaceVariant=#2A2930）。

package aoeck.dwyai.com.voicepack

import aoeck.dwyai.com.AppLogger
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 颜色常量（与 MainActivity MaidMicDarkColors 对齐）
// ============================================================

private val PrimaryColor = Color(0xFFCE93D8)      // 柔和紫罗兰
private val SecondaryColor = Color(0xFF80CBC4)    // 柔和青
private val SurfaceColor = Color(0xFF1C1B1F)
private val SurfaceVariantColor = Color(0xFF2A2930)
private val SurfacePlayColor = Color(0xFF2A1A2E)  // 播放中卡片底色
private val TextMainColor = Color(0xFFE6E1E5)
private val TextSubColor = Color(0xFFCAC4D0)
private val TextDimColor = Color(0xFF888888)
private val TextFadeColor = Color(0xFF666666)
private val DangerColor = Color(0xFFF2B8B5)
private val StopColor = Color(0xFFFF6B6B)

// ============================================================
// 效果链摘要扩展函数
// ============================================================

/**
 * 将 [ChainSnapshot] 转为人类可读的摘要文本。
 *
 * 示例：
 *   - 直通引擎 → "直通"
 *   - 全部参数为默认值 → "原声"
 *   - 萌妹预设 → "Echio: 增益+1.0 · 变调+4 · 混响0.18 · 高音+2.0 · 共振峰+2.0"
 *
 * 模块 ID 与 [aoeck.dwyai.com.ui.editor.BUILTIN_MODULES] 对齐：
 *   1=Gain / 3=Compressor / 4=Pitch / 5=Reverb / 7=Distortion /
 *   11=Bass / 12=Treble / 13=Formant / 14=Echo
 */
fun ChainSnapshot.toSummary(): String {
    // 直通引擎：音频不经处理
    if (engine == "passthrough") return "直通"

    val parts = mutableListOf<String>()
    for (m in modules) {
        if (m.bypass) continue
        when (m.moduleId) {
            1 -> { // Gain
                val v = m.params["gain_db"] ?: 0f
                if (v != 0f) parts += "增益${fmtSigned(v)}"
            }
            3 -> { // Compressor
                val t = m.params["comp_threshold"] ?: 0f
                val r = m.params["comp_ratio"] ?: 0f
                if (t != 0f || r != 0f) parts += "压缩"
            }
            4 -> { // Pitch Shift
                val v = m.params["pitch_semitones"] ?: 0f
                if (v != 0f) parts += "变调${fmtSignedInt(v.toInt())}"
            }
            5 -> { // Reverb
                val v = m.params["reverb_mix"] ?: 0f
                if (v != 0f) parts += "混响${"%.2f".format(v)}"
            }
            7 -> { // Distortion
                val v = m.params["distortion"] ?: 0f
                if (v != 0f) parts += "失真${"%.2f".format(v)}"
            }
            11 -> { // Bass
                val v = m.params["bass_db"] ?: 0f
                if (v != 0f) parts += "低音${fmtSigned(v)}"
            }
            12 -> { // Treble
                val v = m.params["treble_db"] ?: 0f
                if (v != 0f) parts += "高音${fmtSigned(v)}"
            }
            13 -> { // Formant
                val v = m.params["formant_shift"] ?: 0f
                if (v != 0f) parts += "共振峰${fmtSigned(v)}"
            }
            14 -> { // Echo
                val d = m.params["echo_delay_ms"] ?: 0f
                val dec = m.params["echo_decay"] ?: 0f
                if (d != 0f || dec != 0f) parts += "回声${d.toInt()}ms"
            }
        }
    }
    if (parts.isEmpty()) return "原声"

    val engineName = when (engine) {
        "echio_eq" -> "Echio"
        else -> engine
    }
    return "$engineName: ${parts.joinToString(" · ")}"
}

/** 带符号的浮点格式化：+1.0 / -2.5。 */
private fun fmtSigned(v: Float): String =
    if (v >= 0f) "+${"%.1f".format(v)}" else "%.1f".format(v)

/** 带符号的整数格式化：+4 / -3。 */
private fun fmtSignedInt(v: Int): String =
    if (v >= 0) "+$v" else v.toString()

// ============================================================
// 时间 / 时长格式化辅助
// ============================================================

/**
 * 毫秒时长 → "mm:ss"。
 * 超过 1 小时 → "h:mm:ss"。
 */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 时间戳 → 相对时间字符串。
 * 顺序：刚刚 / N分钟前 / N小时前 / 昨天 / N天前 / yyyy-MM-dd。
 */
fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return "未知"
    val diff = now - timestamp
    return when {
        diff < 0 -> "未来"                       // 时钟回拨兜底
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L}分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
        diff < 2L * 86_400_000L -> "昨天"
        diff < 7L * 86_400_000L -> "${diff / 86_400_000L}天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}

// ============================================================
// 语音包列表共享状态（VoicePackListPage / VoicePackSection 共用）
// ============================================================

/**
 * 语音包列表状态与操作逻辑。
 * 持有列表数据、播放状态、对话框状态及播放/分享/删除/重命名操作。
 */
@Stable
class VoicePackListState(
    private val context: Context,
    private val onPlay: ((VoicePack) -> Unit)?
) {
    var packs by mutableStateOf<List<VoicePack>>(emptyList())
        private set
    var playingId by mutableStateOf<String?>(null)
        private set
    var showDeleteDialogFor by mutableStateOf<VoicePack?>(null)
    var showRenameDialogFor by mutableStateOf<VoicePack?>(null)
    var renameText by mutableStateOf("")

    val player = VoicePackPlayer()

    /** 从存储重新加载列表（按创建时间降序） */
    fun reload() {
        packs = VoicePackStore.loadAll(context).sortedByDescending { it.createdAt }
        AppLogger.i("VoicePackList", "reload: ${packs.size} 条语音包")
    }

    /** 播放 / 停止切换（再次点击同一卡片停止） */
    fun playPack(pack: VoicePack) {
        if (playingId == pack.id) {
            player.stop()
            playingId = null
            return
        }
        player.stop()
        playingId = pack.id
        if (onPlay != null) {
            // 外部接管播放（UI 态由用户再次点击停止）
            try {
                onPlay.invoke(pack)
            } catch (e: Exception) {
                AppLogger.e("VoicePackList", "外部 onPlay 回调异常", e)
                playingId = null
            }
        } else {
            // 内置 AudioTrack 外放，完成回调清 playingId
            player.play(context, pack) { playingId = null }
        }
    }

    /** 分享语音包（FileProvider → 系统分享面板） */
    fun sharePack(pack: VoicePack) {
        try {
            val file = VoicePackStore.wavFile(context, pack.wavFile)
            if (!file.exists()) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享语音包：${pack.name}"))
            AppLogger.i("VoicePackList", "sharePack: ${pack.id} uri=$uri")
        } catch (e: Exception) {
            AppLogger.e("VoicePackList", "sharePack 失败", e)
            Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 删除语音包（同时删除 WAV 文件） */
    fun deletePack(pack: VoicePack) {
        if (playingId == pack.id) {
            player.stop()
            playingId = null
        }
        val ok = VoicePackStore.delete(context, pack.id)
        Toast.makeText(
            context,
            if (ok) "已删除「${pack.name}」" else "删除失败",
            Toast.LENGTH_SHORT
        ).show()
        reload()
    }

    /** 重命名语音包 */
    fun renamePack(pack: VoicePack, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = VoicePackStore.rename(context, pack.id, trimmed)
        Toast.makeText(context, if (ok) "已重命名" else "重命名失败", Toast.LENGTH_SHORT).show()
        reload()
    }

    /** 打开重命名对话框（预填当前名称） */
    fun requestRename(pack: VoicePack) {
        renameText = pack.name
        showRenameDialogFor = pack
    }

    /** 停止播放并释放 */
    fun stop() {
        player.stop()
        playingId = null
    }
}

/** 创建语音包列表状态，并在离开组合时自动停止播放。 */
@Composable
fun rememberVoicePackListState(
    context: Context,
    onPlay: ((VoicePack) -> Unit)? = null
): VoicePackListState {
    val state = remember { VoicePackListState(context, onPlay) }
    DisposableEffect(state) {
        onDispose { state.stop() }
    }
    return state
}

// ============================================================
// 删除确认 / 重命名输入对话框（两种展示共用）
// ============================================================

@Composable
private fun VoicePackDialogs(state: VoicePackListState) {
    state.showDeleteDialogFor?.let { pack ->
        AlertDialog(
            onDismissRequest = { state.showDeleteDialogFor = null },
            title = { Text("删除语音包", color = TextMainColor) },
            text = {
                Text(
                    "确定删除「${pack.name}」？\nWAV 文件将被一并删除，此操作无法撤销。",
                    color = TextSubColor,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.deletePack(pack)
                    state.showDeleteDialogFor = null
                }) {
                    Text("删除", color = DangerColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showDeleteDialogFor = null }) {
                    Text("取消", color = TextDimColor)
                }
            }
        )
    }

    state.showRenameDialogFor?.let { pack ->
        AlertDialog(
            onDismissRequest = { state.showRenameDialogFor = null },
            title = { Text("重命名语音包", color = TextMainColor) },
            text = {
                OutlinedTextField(
                    value = state.renameText,
                    onValueChange = { state.renameText = it },
                    singleLine = true,
                    label = { Text("名称", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.renamePack(pack, state.renameText)
                    state.showRenameDialogFor = null
                }) {
                    Text("保存", color = PrimaryColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showRenameDialogFor = null }) {
                    Text("取消", color = TextDimColor)
                }
            }
        )
    }
}

// ============================================================
// 主 Composable：语音包列表页（独立全屏）
// ============================================================

/**
 * 语音包列表页（独立使用，含下拉刷新）。
 *
 * @param context 应用上下文（用于加载列表 / 播放 / 分享 / 删除 / 重命名）。
 * @param onPlay 可选外部播放回调。非 null 时由调用方接管播放（页面只切换 UI 态）；
 *               为 null 时使用内置 [VoicePackPlayer] 外放。
 * @param modifier Compose 修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePackListPage(
    context: Context,
    onPlay: ((VoicePack) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state = rememberVoicePackListState(context, onPlay)
    val refreshState = rememberPullToRefreshState()

    // 首次进入加载一次
    LaunchedEffect(Unit) { state.reload() }

    // 下拉刷新：isRefreshing 变 true 时执行 reload，完成后收起指示器
    LaunchedEffect(refreshState.isRefreshing) {
        if (refreshState.isRefreshing) {
            state.reload()
            refreshState.endRefresh()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(refreshState.nestedScrollConnection)
            .clipToBounds()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶部栏：标题 + 手动刷新按钮 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = PrimaryColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "语音包",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMainColor,
                    modifier = Modifier.weight(1f)
                )
                if (state.packs.isNotEmpty()) {
                    Text(
                        "${state.packs.size} 条",
                        fontSize = 12.sp,
                        color = TextDimColor,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(onClick = { state.reload() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = PrimaryColor
                    )
                }
            }

            // ---- 列表 / 空态 ----
            if (state.packs.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.packs, key = { it.id }) { pack ->
                        VoicePackCard(
                            pack = pack,
                            isPlaying = state.playingId == pack.id,
                            onPlay = { state.playPack(pack) },
                            onShare = { state.sharePack(pack) },
                            onRename = { state.requestRename(pack) },
                            onDelete = { state.showDeleteDialogFor = pack }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        // ---- 下拉刷新指示器（叠加在顶部居中） ----
        PullToRefreshContainer(
            state = refreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    // ---- 删除确认 / 重命名对话框 ----
    VoicePackDialogs(state = state)
}

// ============================================================
// 语音包区块（扁平化，供外层页面直接嵌入）
// ============================================================

/**
 * 语音包区块：作为外层滚动区域的普通内容直接展开。
 * - 无内嵌 LazyColumn、无固定高度，列表高度随内容自适应（空态不占大块空白）。
 * - 复用 [VoicePackListState]，播放 / 分享 / 删除 / 重命名逻辑与独立页一致。
 *
 * @param context 应用上下文。
 * @param modifier Compose 修饰符。
 */
@Composable
fun VoicePackSection(context: Context, modifier: Modifier = Modifier) {
    val state = rememberVoicePackListState(context)

    // 首次进入加载一次
    LaunchedEffect(Unit) { state.reload() }

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- 区块标题行：图标 + 标题 + 数量 + 刷新 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "语音包",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextMainColor,
                modifier = Modifier.weight(1f)
            )
            if (state.packs.isNotEmpty()) {
                Text(
                    "${state.packs.size} 条",
                    fontSize = 12.sp,
                    color = TextDimColor
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = { state.reload() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = PrimaryColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- 列表 / 空态（平铺，不内嵌滚动） ----
        if (state.packs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = TextFadeColor,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("还没有语音包 · 在变声页录音存包", fontSize = 13.sp, color = TextDimColor)
            }
        } else {
            state.packs.forEach { pack ->
                VoicePackCard(
                    pack = pack,
                    isPlaying = state.playingId == pack.id,
                    onPlay = { state.playPack(pack) },
                    onShare = { state.sharePack(pack) },
                    onRename = { state.requestRename(pack) },
                    onDelete = { state.showDeleteDialogFor = pack }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ---- 删除确认 / 重命名对话框 ----
    VoicePackDialogs(state = state)
}

// ============================================================
// 空列表占位
// ============================================================

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.LibraryMusic,
            contentDescription = null,
            tint = TextFadeColor,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有语音包", fontSize = 15.sp, color = TextDimColor)
        Spacer(Modifier.height(4.dp))
        Text("长按悬浮球开始录音", fontSize = 12.sp, color = TextFadeColor)
    }
}

// ============================================================
// 单条语音包卡片
// ============================================================

/**
 * 单个语音包的展示卡片（高度随内容自适应，不强制宽高比）。
 *
 * @param pack 语音包数据。
 * @param isPlaying 当前是否正在播放该包（控制按钮态 + 卡片高亮）。
 * @param onPlay 点击播放/停止。
 * @param onShare 点击分享。
 * @param onRename 点击重命名（名称行点击触发）。
 * @param onDelete 点击删除。
 */
@Composable
private fun VoicePackCard(
    pack: VoicePack,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) SurfacePlayColor else SurfaceColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // ---- 名称行（点击重命名）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRename() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = if (isPlaying) PrimaryColor else SecondaryColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    pack.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMainColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名",
                    tint = TextFadeColor,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // ---- 元信息行：时长 / 创建时间 / 采样率 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(pack.durationMs), fontSize = 11.sp, color = SecondaryColor)
                Spacer(Modifier.width(12.dp))
                Text(formatRelativeTime(pack.createdAt), fontSize = 11.sp, color = TextDimColor)
                Spacer(Modifier.width(12.dp))
                Text("${pack.sampleRate / 1000}kHz", fontSize = 11.sp, color = TextFadeColor)
            }

            // ---- 效果链摘要 ----
            Surface(
                color = SurfaceVariantColor,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text(
                    pack.chainSnapshot.toSummary(),
                    fontSize = 11.sp,
                    color = TextSubColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // ---- 操作按钮行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 播放 / 停止
                TextButton(onClick = onPlay) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "停止" else "播放",
                        modifier = Modifier.size(16.dp),
                        tint = if (isPlaying) StopColor else SecondaryColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isPlaying) "停止" else "播放",
                        fontSize = 12.sp,
                        color = if (isPlaying) StopColor else SecondaryColor
                    )
                }
                // 分享
                TextButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "分享",
                        modifier = Modifier.size(16.dp),
                        tint = PrimaryColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("分享", fontSize = 12.sp, color = PrimaryColor)
                }
                // 删除
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = TextFadeColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 12.sp, color = TextFadeColor)
                }
            }
        }
    }
}
