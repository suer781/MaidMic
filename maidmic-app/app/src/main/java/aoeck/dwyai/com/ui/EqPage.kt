// EqPage.kt — EQ 页（Task 4 完整实现）
// ============================================================
// 黄金比例 + M3 Expressive 风格的均衡器主控台。
// 三大分区（自上而下）：
//   1. 增益 / 低音 / 高音（三滑块并排，-12dB ~ +12dB）
//   2. 效果调节区（可折叠：混响 / 失真 / 回声延迟 / 回声衰减）
//   3. 引擎选择（直通 / Echio 均衡）
// 所有参数实时同步到 NativeAudioProcessor 并持久化到 SharedPreferences。

package aoeck.dwyai.com.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import aoeck.dwyai.com.AudioEngine
import aoeck.dwyai.com.NativeAudioProcessor
import aoeck.dwyai.com.ui.components.AnimatedSlider
import aoeck.dwyai.com.ui.components.GradientCard
import aoeck.dwyai.com.ui.components.PresetChip
import aoeck.dwyai.com.ui.theme.MaidMicMotion
import aoeck.dwyai.com.ui.theme.MaidMicSpacing
import aoeck.dwyai.com.util.HapticHelper

// ============================================================
// 数值格式化器
// ============================================================

/** dB 数值格式化：正数带 + 号，保留 1 位小数 */
private val dbFormatter: (Float) -> String = { v ->
    val sign = if (v > 0f) "+" else ""
    "$sign${"%.1f".format(v)} dB"
}

/** 百分比格式化（0.0 ~ 1.0 → 0% ~ 100%） */
private val percentFormatter: (Float) -> String = { v ->
    "${(v * 100f).toInt()}%"
}

/** 毫秒格式化 */
private val msFormatter: (Float) -> String = { v ->
    "${v.toInt()} ms"
}

/** 衰减系数格式化（保留 2 位小数） */
private val decayFormatter: (Float) -> String = { v ->
    "%.2f".format(v)
}

// ============================================================
// EQ 页
// ============================================================

@Composable
fun EqPage(context: Context, modifier: Modifier = Modifier) {
    // ---------- 持久化存储 ----------
    // maidmic_eq：EQ 参数（gain/bass/treble/reverb/pitch/formant/distortion/echo_delay/echo_decay）
    // maidmic_prefs：引擎选择（audio_engine）
    val eqPrefs = remember { context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE) }
    val appPrefs = remember { context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE) }

    // ---------- EQ 参数状态（从存储恢复） ----------
    var gain by remember { mutableFloatStateOf(eqPrefs.getFloat("gain", 0f)) }
    var bass by remember { mutableFloatStateOf(eqPrefs.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(eqPrefs.getFloat("treble", 0f)) }
    var reverb by remember { mutableFloatStateOf(eqPrefs.getFloat("reverb", 0f)) }
    var distortion by remember { mutableFloatStateOf(eqPrefs.getFloat("distortion", 0f)) }
    var echoDelay by remember { mutableFloatStateOf(eqPrefs.getFloat("echo_delay", 0f)) }
    var echoDecay by remember { mutableFloatStateOf(eqPrefs.getFloat("echo_decay", 0f)) }
    // pitch / formant 由变声页管理，此处仅读取以保持 setEqParams 调用完整
    val pitch by remember { mutableIntStateOf(eqPrefs.getInt("pitch", 0)) }
    val formant by remember { mutableFloatStateOf(eqPrefs.getFloat("formant", 0f)) }

    // ---------- 引擎状态 ----------
    var currentEngine by remember { mutableStateOf(NativeAudioProcessor.getEngine()) }
    var effectsExpanded by remember { mutableStateOf(false) }

    // ---------- 引擎自检（确保 JNI 已加载，幂等） ----------
    LaunchedEffect(Unit) {
        NativeAudioProcessor.ensureLoaded()
    }

    // ---------- 参数推送 + 持久化 ----------
    // 任意滑块变化：读取当前所有参数 → 更新对应字段 → setEqParams 推送 → 写 maidmic_eq
    fun pushEqParams() {
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(
            gainDb = gain,
            bassDb = bass,
            trebleDb = treble,
            reverbMix = reverb,
            pitchSemitones = pitch,
            formantShift = formant,
            distortion = distortion,
            echoDelayMs = echoDelay,
            echoDecay = echoDecay
        )
        eqPrefs.edit()
            .putFloat("gain", gain)
            .putFloat("bass", bass)
            .putFloat("treble", treble)
            .putFloat("reverb", reverb)
            .putInt("pitch", pitch)
            .putFloat("formant", formant)
            .putFloat("distortion", distortion)
            .putFloat("echo_delay", echoDelay)
            .putFloat("echo_decay", echoDecay)
            .apply()
    }

    // ---------- 引擎切换：setEngine + saveEngine(maidmic_prefs) ----------
    fun applyEngine(engine: AudioEngine) {
        currentEngine = engine
        NativeAudioProcessor.setEngine(engine)
        NativeAudioProcessor.saveEngine(appPrefs) // 写 maidmic_prefs：audio_engine
    }

    // ============================================================
    // 页面布局（垂直滚动，GradientCard 分区）
    // ============================================================
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaidMicSpacing.s)
    ) {
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ---------- 顶部标题 ----------
        Text(
            text = "EQ 均衡器",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "当前引擎：${currentEngine.displayName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 1. 增益 / 低音 / 高音（三滑块并排，增益 -24dB ~ +24dB）
        // ============================================================
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "增益 / 低音 / 高音",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MaidMicSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaidMicSpacing.xs)
            ) {
                AnimatedSlider(
                    label = "增益",
                    value = gain,
                    onValueChange = { gain = it; pushEqParams() },
                    valueRange = -24f..24f,
                    modifier = Modifier.weight(1f),
                    valueFormatter = dbFormatter
                )
                AnimatedSlider(
                    label = "低音",
                    value = bass,
                    onValueChange = { bass = it; pushEqParams() },
                    valueRange = -12f..12f,
                    modifier = Modifier.weight(1f),
                    valueFormatter = dbFormatter
                )
                AnimatedSlider(
                    label = "高音",
                    value = treble,
                    onValueChange = { treble = it; pushEqParams() },
                    valueRange = -12f..12f,
                    modifier = Modifier.weight(1f),
                    valueFormatter = dbFormatter
                )
            }
        }
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 2. 效果调节区（可折叠，animateContentSize + ExpandCollapseSpring）
        // ============================================================
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            // 折叠头（触控区 ≥ 48dp）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable {
                    HapticHelper.basic()
                    effectsExpanded = !effectsExpanded
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "效果调节",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (effectsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (effectsExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 折叠内容（弹簧动画）
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = MaidMicMotion.ExpandCollapseSpring
                )
            ) {
                if (effectsExpanded) {
                    AnimatedSlider(
                        label = "混响",
                        value = reverb,
                        onValueChange = { reverb = it; pushEqParams() },
                        valueRange = 0f..1f,
                        valueFormatter = percentFormatter
                    )
                    Spacer(Modifier.height(MaidMicSpacing.xs))
                    AnimatedSlider(
                        label = "失真",
                        value = distortion,
                        onValueChange = { distortion = it; pushEqParams() },
                        valueRange = 0f..1f,
                        valueFormatter = percentFormatter
                    )
                    Spacer(Modifier.height(MaidMicSpacing.xs))
                    AnimatedSlider(
                        label = "回声延迟",
                        value = echoDelay,
                        onValueChange = { echoDelay = it; pushEqParams() },
                        valueRange = 0f..2000f,
                        valueFormatter = msFormatter
                    )
                    Spacer(Modifier.height(MaidMicSpacing.xs))
                    AnimatedSlider(
                        label = "回声衰减",
                        value = echoDecay,
                        onValueChange = { echoDecay = it; pushEqParams() },
                        valueRange = 0f..0.9f,
                        valueFormatter = decayFormatter
                    )
                }
            }
        }
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 3. 引擎选择（直通 / Echio 均衡）
        // ============================================================
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "引擎选择",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MaidMicSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaidMicSpacing.xs)
            ) {
                AudioEngine.entries.forEach { engine ->
                    PresetChip(
                        label = engine.displayName,
                        selected = currentEngine == engine,
                        onClick = { applyEngine(engine) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(MaidMicSpacing.xs))
            Text(
                text = currentEngine.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(MaidMicSpacing.m)) // 底部留白
    }
}
