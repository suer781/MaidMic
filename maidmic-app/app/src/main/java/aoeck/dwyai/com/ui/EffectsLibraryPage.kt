// EffectsLibraryPage.kt — 音效库页（化繁为简清理版）
// ============================================================
// 功能：
//   1. 页面标题 + 副标题。
//   2. "我的音效"管理区：SoundEffectManageSection（导入 / 搜索 / 分组 / 试听）。
//   3. "语音包"列表区：VoicePackSection 扁平嵌入（无内嵌滚动、无固定高度，
//        列表高度随内容自适应，空态不占大块空白）。
// 页面用单个 LazyColumn 垂直滚动，区块标题统一用 SectionHeader。
//
// 注：音效库只负责"播放音效"（导入音频 / 录音语音包），
//   不再放"修改 EQ 参数的预设包"——那是 EQ/变声页的职责。

package aoeck.dwyai.com.ui

import aoeck.dwyai.com.ui.theme.MaidMicSpacing
import aoeck.dwyai.com.voicepack.VoicePackSection
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ============================================================
// 主 Composable：音效库页
// ============================================================

/**
 * 音效库页（化繁为简版）。
 *
 * 页面布局（单个 LazyColumn 垂直滚动，无嵌套滚动）：
 *   1. 页面标题。
 *   2. 我的音效管理区：SoundEffectManageSection（导入 / 搜索 / 分组 / 试听）。
 *   3. 语音包列表区：VoicePackSection（扁平，高度自适应）。
 *
 * @param context 应用上下文（用于 SharedPreferences / Toast / VoicePackSection）。
 * @param modifier Compose 修饰符。
 */
@Composable
fun EffectsLibraryPage(context: Context, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MaidMicSpacing.s, vertical = MaidMicSpacing.s),
        verticalArrangement = Arrangement.spacedBy(MaidMicSpacing.s)
    ) {
        // ---- 页面标题 ----
        item(key = "page_header") {
            Column {
                Text(
                    text = "音效库",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "导入播放你的音效 · 查看录音生成的语音包",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(MaidMicSpacing.s))
            }
        }

        // ---- 我的音效管理区：导入 / 搜索 / 分组展示 / 试听 / 删除 / 重命名 / 移动分组 ----
        item(key = "sound_effect_section") {
            SoundEffectManageSection(context = context)
        }

        // ---- 语音包列表区：扁平嵌入（无固定高度，无重复标题） ----
        item(key = "voicepack_section") {
            VoicePackSection(context = context)
        }

        // 底部留白
        item { Spacer(Modifier.height(MaidMicSpacing.m)) }
    }
}
