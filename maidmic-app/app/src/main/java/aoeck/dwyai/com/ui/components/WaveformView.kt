package aoeck.dwyai.com.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import aoeck.dwyai.com.ui.theme.MaidMicExpressive
import aoeck.dwyai.com.util.HapticHelper
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun WaveformView(
    modifier: Modifier = Modifier,
    barCount: Int = 32,
    color: Color = Color(0xFFCE93D8),
    isActive: Boolean = true,
    enableLraRhythm: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val colorPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colorPhase"
    )

    // LRA 变声节拍：波形同步触感（仅 LRA 设备 + 显式启用时生效）
    LaunchedEffect(isActive, enableLraRhythm) {
        if (isActive && enableLraRhythm) {
            while (isActive) {
                HapticHelper.lraRhythm()
                delay(MaidMicExpressive.LraRhythmIntervalMs)
            }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(60.dp)) {
        val barWidth = size.width / barCount
        val centerY = size.height / 2
        for (i in 0 until barCount) {
            val x = i * barWidth + barWidth / 2
            val amplitude = if (isActive) {
                val base = sin((i * 0.3 + phase).toFloat()) * 0.4f
                val harmonic = sin((i * 0.8 + phase * 1.5f).toFloat()) * 0.12f
                val noise = Random.nextFloat() * 0.1f - 0.05f
                (0.5f + base + harmonic + noise).coerceIn(0.05f, 1f)
            } else {
                0.05f
            }

            val barColor = if (isActive) {
                val purple = Color(0xFFCE93D8)
                val teal = Color(0xFF80CBC4)
                val offset = (i.toFloat() / barCount) * 0.3f
                val localPhase = (colorPhase + offset) % 1f
                if (localPhase < 0.5f) {
                    lerp(purple, teal, localPhase * 2f)
                } else {
                    lerp(teal, purple, (localPhase - 0.5f) * 2f)
                }
            } else {
                color
            }

            val barHeight = size.height * amplitude
            drawLine(
                color = barColor,
                start = Offset(x, centerY - barHeight / 2),
                end = Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth * 0.4f
            )
        }
    }
}