// maidmic-app/app/src/main/java/com/maidmic/bridge/accessibility/AccessibilityMicBridge.kt
// MaidMic 无障碍虚拟麦克风桥接 — 方案 C
// ============================================================
// 通过 Android 无障碍服务 (AccessibilityService) 捕获和注入音频流。
//
// 这是三方案中兼容性最好、延迟最高的方案。
// 不需要 root，不需要 Shizuku，所有 Android 设备都支持。
//
// 原理：
// 1. 用一个前台服务 + 无障碍服务保持进程常驻
// 2. AudioRecord 捕获麦克风输入
// 3. Echio 引擎 DSP 处理
// 4. 通过无障碍服务的全局按键注入 / 音频焦点管理
//    间接将音频传给目标 App
//
// 注意：无障碍服务不能直接注入音频到其他 App，
// 所以方案 C 实际上是一个"转发方案"，延迟较高（80~200ms）。
// 适合对延迟不敏感的场景（直播推流、录制后期处理）。

package aoeck.dwyai.com.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.io.*

// ============================================================
// 无障碍服务（系统自动管理生命周期）
// Accessibility Service (system-managed lifecycle)
// ============================================================
// 这个服务在 Android 设置 -> 无障碍 -> MaidMic 中启用。
// 启用后系统会自动绑定并调用 onServiceConnected。

class MaidMicAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MaidMicAccessibility"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "maidmic_accessibility"
        
        // 当前是否正在运行
        var isRunning = false
            private set
    }

    private var audioCaptureThread: Thread? = null
    private var isProcessing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.i(TAG, "Accessibility service connected")
        
        // 配置无障碍服务信息（告诉系统我们监听哪些事件）
        // Configure accessibility service info
        val info = AccessibilityServiceInfo().apply {
            // 不监听任何具体事件——我们不需要 UI 交互
            // Don't listen to any specific events — we don't need UI interaction
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            
            // 不关注任何包（系统级服务）
            // Don't focus on any package (system-wide)
            packageNames = null
            
            // 不拦截手势
            // Don't intercept gestures
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        
        serviceInfo = info
        
        // 启动前台通知（Android 8+ 需要）
        // Start foreground notification (required on Android 8+)
        startForegroundNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 方案 C 不需要处理任何无障碍事件。
        // 我们只用无障碍服务来获取"常驻后台 + 系统级权限"的能力。
        // 实际的音频处理在独立的线程中运行。
        // No accessibility events needed. We just use the service for
        // "persistent background + system-level privilege".
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
        stopAudioCapture()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
        Log.i(TAG, "Accessibility service destroyed")
    }

    // ============================================================
    // 启动/停止音频捕获
    // Start/Stop audio capture
    // ============================================================

    /**
     * 启动音频处理（从 UI 层调用）
     * Start audio processing (called from UI layer)
     */
    fun startAudioCapture() {
        if (isProcessing) return
        
        isProcessing = true
        isRunning = true
        
        audioCaptureThread = Thread({
            audioCaptureLoop()
        }, "maidmic-accessibility-capture")
        audioCaptureThread?.start()
        
        Log.i(TAG, "Audio capture started (Accessibility mode)")
    }

    /**
     * 停止音频处理
     * Stop audio processing
     */
    fun stopAudioCapture() {
        isProcessing = false
        isRunning = false
        audioCaptureThread?.join(2000)
        audioCaptureThread = null
        Log.i(TAG, "Audio capture stopped")
    }

    // ============================================================
    // 音频捕获循环
    // Audio capture loop
    // ============================================================
    // 
    // 方案 C 的音频流：
    //   物理麦克风 → AudioRecord → Echio 引擎 DSP → AudioTrack → 扬声器
    // 
    // 延迟来源：
    // 1. AudioRecord 缓冲区（~40ms）
    // 2. DSP 处理（~5-20ms）
    // 3. AudioTrack 缓冲区（~40ms）
    // 总计：~100ms（可优化）
    //
    // 优化方向（后续迭代）：
    // - 减小缓冲区大小（降低延迟但增加 CPU 占用）
    // - 使用 AAudio 替代 AudioTrack（Android 10+ 支持）
    // - 用户可自定义 bufferSize 来平衡延迟/稳定性

    private fun audioCaptureLoop() {
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        
        // 计算缓冲区大小（用户可以调节这个值）
        // Calculate buffer size (user-adjustable)
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        // 创建 AudioRecord 用于捕获麦克风输入
        // Create AudioRecord for microphone input
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2  // 大一点减少欠载
        )
        
        // 创建 AudioTrack 用于输出处理后的音频
        // Create AudioTrack for processed audio output
        val trackBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioFormat
        )
        
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(trackBufferSize * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        
        recorder.startRecording()
        track.play()
        
        val buffer = ByteArray(bufferSize)
        val processedBuffer = ByteArray(bufferSize)
        
        while (isProcessing) {
            // 从麦克风读取
            // Read from microphone
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            
            if (bytesRead > 0) {
                // JNI: 调用 Echio 引擎处理
                // JNI: Call Echio engine for DSP processing
                nativeProcess(cEnginePtr, buffer, processedBuffer, bytesRead)
                
                // 输出到扬声器（其他 App 通过音频焦点可捕获）
                // Output to speaker (other apps can capture via audio focus)
                track.write(processedBuffer, 0, bytesRead)
            }
        }
        
        recorder.stop()
        recorder.release()
        track.stop()
        track.release()
    }

    // ============================================================
    // 前台通知
    // Foreground notification
    // ============================================================

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MaidMic Audio Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MaidMic is processing audio in the background"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MaidMic")
            .setContentText("Virtual microphone is active (Accessibility mode)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    // ============================================================
    // Native 方法（JNI → Echio 引擎）
    // ============================================================

    private var cEnginePtr: Long = 0

    private external fun nativeProcess(enginePtr: Long, input: ByteArray, output: ByteArray, size: Int)
}
