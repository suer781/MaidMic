package aoeck.dwyai.com.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 分区卡片（化繁为简：默认平卡，无渐变无阴影）
 * - 默认使用表面色 + 半透明，视觉安静，避免多卡堆叠显得脏
 * - 仅当调用方显式传入 gradientColors 时才渲染渐变（如彩色预设卡）
 * - 圆角 16dp（M3 medium）
 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    gradientColors: List<Color>? = null,
    cornerRadius: Int = 16,
    content: @Composable ColumnScope.() -> Unit
) {
    val effectiveGradient = gradientColors ?: listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(effectiveGradient)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}
