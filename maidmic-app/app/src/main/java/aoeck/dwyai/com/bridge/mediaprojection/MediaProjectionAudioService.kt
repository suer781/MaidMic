// MediaProjectionAudioService.kt — 方案D: MediaProjection 屏幕共享音频捕获
// ============================================================
// 通过 MediaProjection API 捕获系统音频，结合麦克风输入进行变声处理。
// 此方案适合 Android 10+ 设备。

package aoeck.dwyai.com.bridge.mediaprojection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor

class MediaProjectionAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "maidmic_mediaprojection"
        const val NOTIFICATION_ID = 1004
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        private const val TAG = "MediaProjectionService"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        NativeAudioProcessor.ensureLoaded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        
        if (resultCode != -1 && resultData != null) {
            startMediaProjection(resultCode, resultData)
        }
        
        return START_STICKY
    }

    private fun startMediaProjection(resultCode: Int, resultData: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AppLogger.w(TAG, "MediaProjection 已停止")
                stopCapture()
            }
        }, null)

        startAudioCapture()
    }

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLogger.e(TAG, "系统音频捕获需要 Android 10+")
            return
        }

        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
            val bufSize = minBufSize.coerceAtLeast(4096)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord?.startRecording()
            isRunning = true

            captureThread = Thread({ captureLoop(bufSize) }, "mediaprojection_audio")
            captureThread?.start()

            AppLogger.i(TAG, "MediaProjection 音频捕获已启动")
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动音频捕获失败", e)
        }
    }

    private fun captureLoop(bufSize: Int) {
        val inputBuffer = ByteArray(bufSize)
        val processedBuffer = ByteArray(bufSize)

        while (isRunning && !Thread.interrupted()) {
            try {
                val bytesRead = audioRecord?.read(inputBuffer, 0, bufSize) ?: -1
                if (bytesRead > 0) {
                    NativeAudioProcessor.processAudio(inputBuffer, processedBuffer, bytesRead)
                    // 这里可以添加音频输出逻辑
                }
            } catch (e: Exception) {
                if (isRunning) {
                    AppLogger.e(TAG, "捕获循环异常", e)
                }
                break
            }
        }
    }

    private fun stopCapture() {
        isRunning = false
        captureThread?.interrupt()
        captureThread?.join(3000)
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        AppLogger.i(TAG, "MediaProjection 音频捕获已停止")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MaidMediaProjection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MaidMic")
            .setContentText("MediaProjection 模式")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
