package aoeck.dwyai.com.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object HapticHelper {
    private const val TAG = "HapticHelper"
    private const val THROTTLE_MS = 50L

    private var vibrator: Vibrator? = null
    private var effectsSupported = false
    private var hasVibrator = false
    private var enabled = true
    private var isLRA = false
    private var lastTriggerMs: Long = 0L

    fun init(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        hasVibrator = v?.hasVibrator() ?: false
        effectsSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasVibrator && v?.areEffectsSupported(VibrationEffect.EFFECT_CLICK)?.firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        } else {
            false
        }
        isLRA = hasVibrator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        Log.d(TAG, "init: hasVibrator=$hasVibrator, effectsSupported=$effectsSupported, isLRA=$isLRA")
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        Log.d(TAG, "setEnabled: $enabled")
    }

    fun isEnabled(): Boolean = enabled

    fun isAvailable(): Triple<Boolean, Boolean, Boolean> = Triple(hasVibrator, effectsSupported, isLRA)

    private fun canTrigger(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerMs < THROTTLE_MS) {
            Log.d(TAG, "throttled: ${now - lastTriggerMs}ms since last trigger")
            return false
        }
        lastTriggerMs = now
        return true
    }

    fun basic(context: Context? = null) {
        trigger(
            durationMs = 8L,
            amplitude = 128,
            degradedMs = 30L,
            context = context
        )
    }

    fun success(context: Context? = null) {
        triggerWaveform(
            timings = longArrayOf(15, 30),
            amplitudes = intArrayOf(80, 200),
            context = context
        )
    }

    fun warning(context: Context? = null) {
        triggerWaveform(
            timings = longArrayOf(30, 20),
            amplitudes = intArrayOf(200, 80),
            context = context
        )
    }

    fun error(context: Context? = null) {
        triggerWaveform(
            timings = longArrayOf(15, 15, 30),
            amplitudes = intArrayOf(200, 180, 100),
            context = context
        )
    }

    /**
     * 连续（循环）震动，用于演示"持续振动"语义。
     *
     * 注意：repeatIndex = 0 表示无限循环（80ms 震 / 120ms 停 / 80ms 震 / 120ms 停），
     * 不会自行结束。连续震动由调用方负责限时停止——请配合 [stop] 使用
     * （如开发者页测试中触发约 1.5s 后调用 stop()）。当前调用方仅开发者页触感诊断。
     */
    fun continuous(context: Context? = null) {
        triggerWaveform(
            timings = longArrayOf(80, 120, 80, 120),
            amplitudes = intArrayOf(60, 0, 60, 0),
            repeatIndex = 0,
            context = context
        )
    }

    /**
     * 停止所有震动。
     *
     * API 31+ 走 VibratorManager.defaultVibrator、旧 API 走 Vibrator 服务，
     * 但 init() 中两条分支最终都收敛到同一个 Vibrator 引用（vibrator），
     * 因此这里统一对 vibrator.cancel() 即可覆盖两条路径。
     * 注意：作为清理操作不受 enabled 开关限制，保证任何情况下都能停震。
     */
    fun stop() {
        val v = vibrator ?: return
        try {
            v.cancel()
            Log.d(TAG, "stop: vibrator cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
        }
    }

    fun mechanical(context: Context? = null) {
        if (!isLRA) {
            Log.d(TAG, "mechanical: skipped (not LRA device)")
            return
        }
        trigger(
            durationMs = 3L,
            amplitude = 255,
            degradedMs = 30L,
            context = context,
            forceLRA = true
        )
    }

    fun lraRhythm(context: Context? = null) {
        if (!isLRA) return
        trigger(
            durationMs = 5L,
            amplitude = 100,
            degradedMs = 0L,
            context = context,
            forceLRA = true
        )
    }

    private fun trigger(
        durationMs: Long,
        amplitude: Int,
        degradedMs: Long,
        context: Context? = null,
        forceLRA: Boolean = false
    ) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!hasVibrator) return
        if (!canTrigger()) return

        try {
            if (effectsSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (forceLRA && !isLRA) return
                val clampedAmplitude = amplitude.coerceIn(1, 255)
                val effect = VibrationEffect.createOneShot(durationMs, clampedAmplitude)
                v.vibrate(effect)
            } else if (degradedMs > 0) {
                @Suppress("DEPRECATION")
                v.vibrate(degradedMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "trigger failed: ${e.message}")
        }
    }

    private fun triggerWaveform(
        timings: LongArray,
        amplitudes: IntArray,
        repeatIndex: Int = -1,
        context: Context? = null
    ) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!hasVibrator) return
        if (timings.size != amplitudes.size) {
            Log.w(TAG, "triggerWaveform: timings and amplitudes must be same length")
            return
        }
        if (!canTrigger()) return

        try {
            if (effectsSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val clamped = amplitudes.map { it.coerceIn(0, 255) }.toIntArray()
                val effect = VibrationEffect.createWaveform(timings, clamped, repeatIndex)
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings.sum())
            }
        } catch (e: Exception) {
            Log.w(TAG, "triggerWaveform failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    internal fun vibrateLegacy(milliseconds: Long) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!hasVibrator) return
        if (milliseconds <= 0) return
        try {
            v.vibrate(milliseconds.coerceAtMost(30))
        } catch (e: Exception) {
            Log.w(TAG, "vibrateLegacy failed: ${e.message}")
        }
    }
}