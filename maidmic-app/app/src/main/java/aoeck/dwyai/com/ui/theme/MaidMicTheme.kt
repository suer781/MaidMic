// MaidMicTheme.kt — 主题系统（黄金比例 + M3 Expressive 基础）
// ============================================================
// 统一管理 MaidMic 的配色方案、字体层级、形状系统和动画规格。
// 从 MainActivity.kt 迁移而来，供全 App 复用。

package aoeck.dwyai.com.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 配色 — 深色音频工作室风格（M3 darkColorScheme）
// ============================================================

val MaidMicDarkColors = darkColorScheme(
    primary = Color(0xFFCE93D8),         // 柔和紫罗兰
    onPrimary = Color(0xFF1A0D2E),
    primaryContainer = Color(0xFF4A2561),
    onPrimaryContainer = Color(0xFFF3E5F5),
    secondary = Color(0xFF80CBC4),       // 柔和青色
    onSecondary = Color(0xFF00201E),
    secondaryContainer = Color(0xFF004D47),
    onSecondaryContainer = Color(0xFFA7F3EC),
    tertiary = Color(0xFFFFAB91),        // 暖橙（用于强调）
    onTertiary = Color(0xFF2D1509),
    background = Color(0xFF100F14),      // 更深背景
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),         // 表面色
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2930),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

// ============================================================
// 字体层级 — 斐波那契数列（比例接近 1.618 黄金比例）
// ============================================================
// 36 → 22 → 14 → ... 行高约为字号 × 1.618

val MaidMicTypography = Typography(
    // 标题 — 36sp，行高 58sp (≈36×1.618)
    titleLarge = TextStyle(
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 56.sp,
        letterSpacing = (-0.02).sp
    ),
    // 副标题 — 22sp，行高 36sp (≈22×1.618)
    titleMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 29.sp,
        letterSpacing = (-0.02).sp
    ),
    // 正文 — 14sp，行高 23sp (≈14×1.618)
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 23.sp
    ),
    // 正文辅助 — 14sp，行高 23sp
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 23.sp
    ),
    // 标签 — 14sp，Medium
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    // 小标签 — 12sp
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (0.01).sp
    )
    // 其他样式（display/headline/titleSmall/bodySmall/labelSmall）使用 Material3 默认值
)

// ============================================================
// 形状系统 — 统一圆角规格
// ============================================================

val MaidMicShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

// ============================================================
// 动画规格 — 全 App 复用的 Spring 动画常量
// ============================================================

object MaidMicMotion {
    // 页面切换（Float 维度，用于 fadeIn / fadeOut）
    val PageTransitionSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    // 页面切换（IntOffset 维度，用于 slideInHorizontally / slideOutHorizontally）
    val PageTransitionSpringIntOffset = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    // 组件展开/折叠
    val ExpandCollapseSpring = spring<IntSize>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    // 按钮点击
    val ButtonPressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh
    )
    // 滑块拖动
    val SliderDragSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    // 预设选中
    val PresetSelectSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium
    )
    // 页面切换时长（毫秒）— 用于 tween 动画场景
    const val PageTransitionDurationMs = 300
}

// ============================================================
// Haptic Tokens — 7 vibration semantics (5 general + 2 LRA-exclusive)
// ============================================================

enum class HapticTokens {
    BASIC,
    SUCCESS,
    WARNING,
    ERROR,
    CONTINUOUS,
    MECHANICAL,
    LRA_RHYTHM
}

// ============================================================
// 8pt Spacing System — Unified spacing tokens
// ============================================================

object MaidMicSpacing {
    val xs = 8.dp
    val s = 16.dp
    val m = 24.dp
    val l = 32.dp
    val xl = 48.dp
    val xxl = 64.dp
}

// ============================================================
// Motion Duration Tokens — 5-tier timing specification
// ============================================================

object MaidMicMotionDurations {
    const val InstantMs = 80L
    const val QuickMs = 150L
    const val StandardMs = 250L
    const val ComplexMs = 400L
    const val ElaborateMs = 600L
}

// ============================================================
// M3 Expressive Transformative Tier Constants
// ============================================================

object MaidMicExpressive {
    val HeroMomentShape = RoundedCornerShape(24.dp)

    val AccentGradientColors = listOf(
        Color(0xFF80CBC4),
        Color(0xFFFFAB91),
    )

    const val LraRhythmIntervalMs = 30L
}

// ============================================================
// 主题入口 — 统一应用配色/字体/形状
// ============================================================

@Composable
fun MaidMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaidMicDarkColors,
        typography = MaidMicTypography,
        shapes = MaidMicShapes,
        content = content
    )
}
