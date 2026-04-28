// ShizukuStatus.kt — 全局 Shizuku 状态追踪
// ============================================================

package aoeck.dwyai.com

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuState {
    /** Shizuku 未安装或未运行 */
    UNAVAILABLE,
    /** 已安装但未授权 */
    UNAUTHORIZED,
    /** 已授权可用 */
    READY
}

object ShizukuStatus {

    var state: ShizukuState = ShizukuState.UNAVAILABLE
        private set
    var listeners: MutableList<(ShizukuState) -> Unit> = mutableListOf()

    fun refresh() {
        state = try {
            if (!Shizuku.pingBinder()) {
                ShizukuState.UNAVAILABLE
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ShizukuState.UNAUTHORIZED
            } else {
                ShizukuState.READY
            }
        } catch (_: Exception) {
            ShizukuState.UNAVAILABLE
        }
        notifyListeners()
    }

    /** 请求授权，返回授权结果 */
    fun requestAuth(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                if (granted) state = ShizukuState.READY else state = ShizukuState.UNAUTHORIZED
                notifyListeners()
                onResult(granted)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            state = ShizukuState.UNAVAILABLE
            notifyListeners()
            onResult(false)
        }
    }

    fun addListener(l: (ShizukuState) -> Unit) {
        listeners.add(l)
        l(state) // 立刻回调当前状态
    }

    fun removeListener(l: (ShizukuState) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        listeners.forEach { it(state) }
    }
}
