// MaidMicApp.kt — Application 入口
// ============================================================
// 应用启动时初始化 Shizuku、日志系统、Echio 引擎加载

package aoeck.dwyai.com

import android.app.Application
import android.content.Intent
import android.os.Build
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
        
        // 初始化 Shizuku 状态
        ShizukuStatus.refresh()
        
        // 启动前台保活服务
        startKeepAliveService()
        
        // 加载 Echio 引擎 native 库
        initNativeEngine()
        
        // 检查 Shizuku 状态
        checkShizuku()
    }

    private fun startKeepAliveService() {
        try {
            val intent = Intent(this, MaidMicKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "Keep-alive service started")
        } catch (e: Exception) {
            Log.w(TAG, "Keep-alive service failed: ${e.message}")
        }
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
