// OverlayPermissionHelper.kt — 悬浮窗（SYSTEM_ALERT_WINDOW）权限工具
// ============================================================
// 提供 检测 / 请求 / 回调重检 三段式逻辑。
// Android 6.0+ 需用户在系统设置中显式授予 "显示在其他应用上层" 权限。

package aoeck.dwyai.com.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object OverlayPermissionHelper {

    /**
     * 检测当前应用是否已拥有悬浮窗权限。
     * Android 6.0 (API 23) 以下系统默认授予，返回 true。
     */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 跳转到系统 "显示在其他应用上层" 设置页，请求用户授权。
     * 调用方在 [Activity.onActivityResult] 中调用 [onActivityResult] 重新检测。
     */
    fun requestPermission(activity: Activity, requestCode: Int) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * 在 onActivityResult 中调用，重新检测权限状态。
     * 返回 true 表示用户已授予悬浮窗权限。
     */
    fun onActivityResult(context: Context): Boolean =
        Settings.canDrawOverlays(context)
}
