// maidmic-app/app/src/main/java/com/maidmic/bridge/shizuku/ShizukuMicBridge.kt
// MaidMic Shizuku 虚拟麦克风桥接
// ============================================================
// 方案B：通过 Shizuku API 提升权限，利用 AAudio 环回建立虚拟麦克风。
//
// Shizuku 提供了一种方式让普通 App 获得部分系统级权限（不需要 root）。
// 我们用它来：
// 1. 读取系统音频策略配置
// 2. 创建 AAudio 环回流
// 3. 将处理后的音频注入系统音频管道
//
// 注意：Shizuku 不是 root，权限受限。
// 它能做的比 root 少，但胜在不需要解锁、不需要刷机。
// 对于 Android 10+ 设备，这是最实用的方案。

package aoeck.dwyai.com.bridge.shizuku

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

// ============================================================
// Shizuku 状态
// Shizuku status
// ============================================================
// Shizuku 有三种可能状态：
// 1. 未安装（用户没装 Shizuku App）
// 2. 已安装但未授权（用户没给权限）
// 3. 已授权（正常工作）

enum class ShizukuStatus {
    NOT_INSTALLED,   // Shizuku 未安装
    NOT_GRANTED,     // 未授权
    READY,           // 已就绪
}

// ============================================================
// 音频配置（用户可自定）
// Audio configuration (user-configurable)
// ============================================================
// 所有值都可以由用户在 UI 上调整，没有硬性限制。
// All values are user-adjustable in the UI, no hard limits.

data class AudioConfig(
    val sampleRate: Int = 48000,     // 采样率：8000~192000
    val channelCount: Int = 1,       // 声道数：1（单声道）或 2（立体声）
    val bitDepth: Int = 16,          // 位深：16 或 32
    val bufferSizeFrames: Int = 256, // 缓冲区大小（帧），越小延迟越低
    val targetLatencyMs: Int = 30    // 目标延迟（毫秒）
)

// ============================================================
// Shizuku 虚拟麦克风桥接
// Shizuku Virtual Microphone Bridge
// ============================================================

class ShizukuMicBridge(private val context: Context) {
    
    companion object {
        private const val TAG = "ShizukuMicBridge"
        
        // Shizuku 的最低版本要求
        private const val MIN_SHIZUKU_VERSION = 11
    }
    
    // 当前状态
    private var status: ShizukuStatus = ShizukuStatus.NOT_INSTALLED
    private var audioConfig: AudioConfig = AudioConfig()
    
    // AudioTrack 用于播放处理后的音频（环回输出）
    // AudioTrack used to play processed audio (loopback output)
    private var audioTrack: AudioTrack? = null
    
    // JNI 桥接到 C 层的 Echio 引擎
    // JNI bridge to the C-level Echio engine
    private var nativeEnginePtr: Long = 0
    
    // 处理线程
    private var processingThread: Thread? = null
    private var isRunning = false
    
    // Shizuku 权限回调
    private val permissionRequestCallback = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission granted")
            status = ShizukuStatus.READY
        } else {
            Log.w(TAG, "Shizuku permission denied")
            status = ShizukuStatus.NOT_GRANTED
        }
    }
    
    // ============================================================
    // 初始化
    // Initialization
    // ============================================================
    
    /**
     * 检查 Shizuku 状态并初始化。
     * 
     * @param config 音频配置（用户可调）
     * @return 当前状态
     */
    fun initialize(config: AudioConfig): ShizukuStatus {
        audioConfig = config
        
        // 检查 Shizuku 是否安装
        // Check if Shizuku is installed
        return try {
            if (Shizuku.pingBinder()) {
                // Shizuku 运行中，检查权限
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    status = ShizukuStatus.READY
                    Log.i(TAG, "Shizuku ready, version: ${Shizuku.getVersion()}")
                    
                    // 初始化 native 引擎
                    initNativeEngine()
                } else {
                    status = ShizukuStatus.NOT_GRANTED
                    // 请求权限
                    Shizuku.requestPermission(0)
                }
            } else {
                status = ShizukuStatus.NOT_INSTALLED
                Log.w(TAG, "Shizuku not running")
            }
            status
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku check failed", e)
            ShizukuStatus.NOT_INSTALLED
        }
    }
    
    /**
     * 初始化 Native 引擎（JNI 调用 C 层）
     * Initialize native engine (JNI call to C layer)
     * 
     * 在 C 层创建：
     * - RingBuffer
     * - Pipeline（含所有已添加的 DSP 模块）
     * - 音频 IO 桥
     */
    private fun initNativeEngine() {
        nativeEnginePtr = nativeCreateEngine(
            audioConfig.sampleRate,
            audioConfig.channelCount,
            audioConfig.bitDepth,
            audioConfig.bufferSizeFrames
        )
        Log.i(TAG, "Native engine created, ptr=$nativeEnginePtr")
    }
    
    // ============================================================
    // 启动/停止音频处理
    // Start/Stop audio processing
    // ============================================================
    
    /**
     * 启动虚拟麦克风
     * 
     * 流程：
     * 1. 通过 AudioManager 获取系统麦克风输入
     * 2. 建立 RingBuffer 写入线程
     * 3. 处理线程从 RingBuffer 读取 -> Pipeline 处理 -> AudioTrack 输出
     * 4. AudioTrack 的音频被系统视为"媒体音频"，其他 App 可以拾取
     */
    fun start() {
        if (status != ShizukuStatus.READY || !nativeEnginePtr != 0L) {
            Log.e(TAG, "Cannot start: not ready")
            return
        }
        
        isRunning = true
        
        // 创建 AudioTrack 用于输出处理后的音频
        // This creates the audio output that other apps can "see" as a microphone source
        createAudioTrack()
        
        // 启动处理线程
        processingThread = Thread({
            processAudioLoop()
        }, "maidmic-processing")
        processingThread?.start()
        
        Log.i(TAG, "Virtual mic started (Shizuku mode)")
    }
    
    /**
     * 停止虚拟麦克风
     */
    fun stop() {
        isRunning = false
        processingThread?.join(1000)
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "Virtual mic stopped")
    }
    
    /**
     * 销毁（释放所有资源）
     */
    fun destroy() {
        stop()
        if (nativeEnginePtr != 0L) {
            nativeDestroyEngine(nativeEnginePtr)
            nativeEnginePtr = 0
        }
    }
    
    // ============================================================
    // AudioTrack 环回输出
    // AudioTrack loopback output
    // ============================================================
    
    /**
     * 创建 AudioTrack。
     * 
     * 关键点：使用 USAGE_MEDIA 和性能模式 LOW_LATENCY。
     * 这样 Android 音频系统会把我们的输出混入系统音频流，
     * 其他 App（游戏、QQ、微信）通过音频焦点能"听到"我们。
     * 
     * 这不是真正的"虚拟麦克风"（我们没注册新的音频设备），
     * 但能做到让其他 App 捕获处理后的音频。
     */
    private fun createAudioTrack() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)      // 媒体用途
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        
        val format = AudioFormat.Builder()
            .setEncoding(if (audioConfig.bitDepth == 32) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(audioConfig.sampleRate)
            .setChannelMask(if (audioConfig.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
            .build()
        
        // 计算缓冲区大小
        val bufferSize = AudioTrack.getMinBufferSize(
            audioConfig.sampleRate,
            if (audioConfig.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
            if (audioConfig.bitDepth == 32) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize.coerceAtLeast(audioConfig.bufferSizeFrames * audioConfig.channelCount * (audioConfig.bitDepth / 8)),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        
        audioTrack?.play()
    }
    
    // ============================================================
    // 音频处理循环
    // Audio processing loop
    // ============================================================
    // 这个循环运行在独立的线程中：
    // 1. 从系统的麦克风读取原始 PCM
    // 2. 写入 RingBuffer
    // 3. 从 RingBuffer 读取 -> Pipeline 处理
    // 4. 写入 AudioTrack 输出
    //
    // 延迟控制：bufferSize 越小延迟越低，但 CPU 占用越高。
    // 用户可以在 UI 上调节这个平衡。
    
    private fun processAudioLoop() {
        val frameBytes = audioConfig.channelCount * (audioConfig.bitDepth / 8)
        val bufferSizeBytes = audioConfig.bufferSizeFrames * frameBytes
        
        // 输入缓冲区（原始 PCM）
        val inputBuffer = ByteArray(bufferSizeBytes)
        // 输出缓冲区（处理后 PCM）
        val outputBuffer = ByteArray(bufferSizeBytes)
        
        while (isRunning) {
            // 从 RingBuffer 读取处理后的音频
            // Read processed audio from RingBuffer (via JNI)
            val framesRead = nativeReadAudio(nativeEnginePtr, outputBuffer, audioConfig.bufferSizeFrames)
            
            if (framesRead > 0) {
                // 写入 AudioTrack 输出到系统
                // Write to AudioTrack for system output
                audioTrack?.write(outputBuffer, 0, framesRead * frameBytes)
            }
        }
    }
    
    // ============================================================
    // 配置更新
    // Configuration update
    // ============================================================
    
    /**
     * 更新音频配置（用户调整参数后调用）
     * Update audio configuration (called after user adjusts parameters)
     */
    fun updateConfig(config: AudioConfig) {
        val wasRunning = isRunning
        if (wasRunning) stop()
        
        audioConfig = config
        
        nativeUpdateConfig(
            nativeEnginePtr,
            config.sampleRate,
            config.channelCount,
            config.bitDepth,
            config.bufferSizeFrames
        )
        
        if (wasRunning) start()
    }
    
    // ============================================================
    // 状态查询
    // Status queries
    // ============================================================
    
    fun getStatus(): ShizukuStatus = status
    fun getLatencyMs(): Float = nativeGetLatencyMs(nativeEnginePtr)
    
    // ============================================================
    // Native 方法（JNI 到 C 层的 Echio 引擎）
    // Native methods (JNI to C-level Echio engine)
    // ============================================================
    // 这些方法在 maidmic-app/app/src/main/cpp/ 中有 C++ 实现
    
    private external fun nativeCreateEngine(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        bufferSize: Int
    ): Long
    
    private external fun nativeDestroyEngine(enginePtr: Long)
    
    private external fun nativeReadAudio(
        enginePtr: Long,
        buffer: ByteArray,
        frameCount: Int
    ): Int
    
    private external fun nativeUpdateConfig(
        enginePtr: Long,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        bufferSize: Int
    )
    
    private external fun nativeGetLatencyMs(enginePtr: Long): Float
}
