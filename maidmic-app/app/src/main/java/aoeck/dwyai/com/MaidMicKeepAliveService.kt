// MaidMicKeepAliveService.kt — 前台保活服务
// ============================================================
// 在后台保持 MaidMic 进程存活，防止系统回收。
// 当退出 App 时，通知依然保留，表明服务在运行。

package aoeck.dwyai.com

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MaidMicKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "maidmic_keepalive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "aoeck.dwyai.com.STOP_KEEPALIVE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果收到停止指令，自己停掉
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MaidMic 后台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 MaidMic 在后台运行"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 点击通知打开主界面
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 停止服务按钮
        val stopIntent = Intent(this, MaidMicKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MaidMic")
            .setContentText("虚拟麦克风运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
