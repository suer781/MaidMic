// ShizukuMicService.kt — 方案B 后台服务
// ============================================================
// 通过 Shizuku 提权 + AAudio 环回实现虚拟麦克风。
// 作为前台服务运行，保活。

package aoeck.dwyai.com.bridge.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ShizukuMicService : Service() {

    companion object {
        const val CHANNEL_ID = "maidmic_shizuku"
        const val NOTIFICATION_ID = 1002
    }

    private val bridge by lazy { ShizukuMicBridge(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bridge.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bridge.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MaidMic Shizuku",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MaidMic")
            .setContentText("Virtual mic (Shizuku mode)")
            .setSmallIcon(android.R.drawable.ic_menu_sound)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
