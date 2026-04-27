// MaidMicApp.kt — Application 入口
// ============================================================
// 应用启动时初始化 Shizuku、日志系统、Echio 引擎加载

package aoeck.dwyai.com

import android.app.Application
import android.util.Log
import rikka.shizuku.Shizuku

class MaidMicApp : Application() {

    companion object {
        private const val TAG = "MaidMicApp"
        lateinit var instance: MaidMicApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.i(TAG, "MaidMic starting...")
        
        // 加载 Echio 引擎 native 库
        initNativeEngine()
        
        // 检查 Shizuku 状态（但不请求权限——让用户在设置页面操作）
        checkShizuku()
    }

    private fun initNativeEngine() {
        try {
            System.loadLibrary("maidmic_jni")
            Log.i(TAG, "Echio engine loaded (maidmic_jni.so)")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Echio engine not available: ${e.message}")
            Log.w(TAG, "Running in JVM-only mode (some features disabled)")
        }
    }

    private fun checkShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                Log.i(TAG, "Shizuku available, version: ${Shizuku.getVersion()}")
            } else {
                Log.i(TAG, "Shizuku not running")
            }
        } catch (e: Exception) {
            Log.i(TAG, "Shizuku not available")
        }
    }
}
