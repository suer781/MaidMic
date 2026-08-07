// HighRefreshRateHelper.kt — 高刷新率适配
// ============================================================
// 在 Activity.onCreate 中调用，将 Window 帧率设置为屏幕支持的最高刷新率。
// 遵循 Android Frame Rate API 规范，不在每帧调用。

package aoeck.dwyai.com.util

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.WindowManager

object HighRefreshRateHelper {
    private const val TAG = "HighRefreshRate"

    /**
     * 在 Activity.onCreate 中调用，将 Window 帧率设置为屏幕支持的最高刷新率。
     * 遵循 Android Frame Rate API 规范，不在每帧调用。
     */
    fun applyHighRefreshRate(activity: Activity) {
        val window = activity.window
        val display = window.decorView.display ?: return

        // 找到最高刷新率对应的 mode
        val modes = display.supportedModes
        if (modes.isNullOrEmpty()) return

        val maxRefreshMode = modes.maxByOrNull { it.refreshRate } ?: return
        val maxRefreshRate = maxRefreshMode.refreshRate
        android.util.Log.i(TAG, "屏幕支持最高刷新率: ${maxRefreshRate}Hz, modeId=${maxRefreshMode.modeId}")

        // API 23+: 用 preferredDisplayModeId 设置最高刷新率 mode（最终方案）
        val params = window.attributes
        params.preferredDisplayModeId = maxRefreshMode.modeId
        window.attributes = params
        android.util.Log.i(TAG, "已设置 preferredDisplayModeId=${maxRefreshMode.modeId}")
    }
}
