// MediaProjectionBridge.kt — MediaProjection 方案桥接类
// ============================================================
// 管理 MediaProjection 权限请求和服务启动的桥接类。

package aoeck.dwyai.com.bridge.mediaprojection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import aoeck.dwyai.com.AppLogger

class MediaProjectionBridge(private val context: Context) {

    companion object {
        private const val TAG = "MediaProjectionBridge"
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private var resultCode: Int = -1
    private var resultData: Intent? = null

    fun requestMediaProjectionPermission(activity: Activity) {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK) {
            this.resultCode = resultCode
            this.resultData = data
            startMediaProjectionService()
        }
    }

    private fun startMediaProjectionService() {
        if (resultCode == -1 || resultData == null) {
            AppLogger.e(TAG, "MediaProjection 权限未授予")
            return
        }

        val serviceIntent = Intent(context, MediaProjectionAudioService::class.java).apply {
            putExtra(MediaProjectionAudioService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionAudioService.EXTRA_RESULT_DATA, resultData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        AppLogger.i(TAG, "MediaProjection 服务已启动")
    }

    fun stopMediaProjectionService() {
        val serviceIntent = Intent(context, MediaProjectionAudioService::class.java)
        context.stopService(serviceIntent)
        AppLogger.i(TAG, "MediaProjection 服务已停止")
    }
}
