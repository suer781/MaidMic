package aoeck.dwyai.com.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import aoeck.dwyai.com.ui.theme.MaidMicMotion
import aoeck.dwyai.com.util.HapticHelper

/**
 * M3 Expressive 形状变形按钮
 * - 按压时圆角 16dp → 24dp（更圆润，形状变形）
 * - 按压时 scale 0.95（弹簧反馈）
 * - 按压时 fontWeight 700 → 900（可变字体轴）
 */
@Composable
fun PressableButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = MaidMicMotion.ButtonPressSpring,
        label = "buttonScale"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 24.dp else 16.dp,
        label = "cornerRadius"
    )
    val fontWeight = if (isPressed) FontWeight.Black else FontWeight.Bold

    Surface(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    HapticHelper.basic()
                    onClick()
                }
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = fontWeight
            )
        }
    }
}
