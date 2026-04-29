// RootMicBridge.kt — 方案A: Root (Magisk) 虚拟麦克风
// ============================================================
// 通过 Magisk root 权限实现低延迟音频环回。
// 不修改系统分区文件（避免 AVB/dm-verity 触发），
// 而是利用 root 权限提高进程优先级、固定 CPU 核心、
// 以及使用原生 AudioTrack 环回输出。
//
// 原理：
// 1. AudioRecord 捕获系统麦克风输入
// 2. 通过 NativeAudioProcessor 引擎处理
// 3. AudioTrack 环回输出到系统音频管道
// 4. Root 权限用于：提线程优先级、固定大核、禁用 SELinux 域限制
//
// 需要：Magisk 已授权 root 权限

package aoeck.dwyai.com.bridge.root

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import aoeck.dwyai.com.NativeAudioProcessor
import java.io.BufferedReader
import java.io.InputStreamReader

class RootMicBridge(private val context: Context) {

    companion object {
        private const val TAG = "RootMicBridge"
    }

    private var isRunning = false
    private var hasRoot = false
    private var magiskVersion = ""
    private var audioTrack: AudioTrack? = null
    private var processingThread: Thread? = null

    // ============================================================
    // root 权限检查
    // ============================================================

    /** 检查 Magisk root 并获取版本 */
    fun checkRoot(): RootStatus {
        return try {
            // 先试 su
            val proc = Runtime.getRuntime().exec("su -c 'echo SU_OK && id -u'")
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val firstLine = reader.readLine() ?: ""
            val uid = reader.readLine() ?: ""

            if (firstLine.contains("SU_OK") && uid.trim() == "0") {
                hasRoot = true
                // 检查 Magisk 版本
                try {
                    val magiskProc = Runtime.getRuntime().exec("su -c 'magisk -v'")
                    magiskVersion = BufferedReader(InputStreamReader(magiskProc.inputStream)).readLine() ?: "unknown"
                } catch (_: Exception) {
                    magiskVersion = "unknown (su only)"
                }
                RootStatus(true, magiskVersion, "Root OK")
            } else {
                hasRoot = false
                RootStatus(false, "", "No root access")
            }
        } catch (e: Exception) {
            hasRoot = false
            RootStatus(false, "", "Root check failed: ${e.message}")
        }
    }

    data class RootStatus(
        val granted: Boolean,
        val magiskVer: String,
        val message: String
    )

    // ============================================================
    // 启动 / 停止 (使用 AudioTrack 环回，与 Shizuku 类似但带 root 优化)
    // ============================================================

    fun start(config: AudioConfig = AudioConfig()) {
        if (!hasRoot) {
            Log.e(TAG, "No root permission, cannot start")
            return
        }
        if (isRunning) return
        isRunning = true

        // 用 root 提升进程优先级（绑定大核、提 RT 优先级）
        boostPerformance()

        // 创建 AudioTrack
        createAudioTrack(config)

        // 启动处理线程
        processingThread = Thread({
            audioProcessingLoop(config)
        }, "maidmic-root-processing")
        processingThread?.start()

        Log.i(TAG, "Root mic started (Magisk: $magiskVersion)")
    }

    fun stop() {
        isRunning = false
        processingThread?.join(1000)
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        // 恢复 CPU 频率
        resetPerformance()
        Log.i(TAG, "Root mic stopped")
    }

    fun destroy() {
        stop()
    }

    // ============================================================
    // Root 性能优化 — 提优先级 + 固定大核
    // ============================================================

    private fun boostPerformance() {
        try {
            val pid = android.os.Process.myPid()
            // 设置 RT 优先级 (SCHED_FIFO, 优先级 2)
            Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "chrt -f -p 2 $pid && " +
                "echo $pid > /dev/cpuset/top-app/tasks 2>/dev/null || true && " +
                "echo $pid > /dev/stune/top-app/tasks 2>/dev/null || true"
            )).waitFor()
        } catch (_: Exception) {}
    }

    private fun resetPerformance() {
        try {
            val pid = android.os.Process.myPid()
            Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "echo $pid > /dev/cpuset/foreground/tasks 2>/dev/null || true"
            )).waitFor()
        } catch (_: Exception) {}
    }

    // ============================================================
    // AudioTrack 环回输出
    // ============================================================

    private fun createAudioTrack(config: AudioConfig) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(config.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val bufferSize = AudioTrack.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(config.bufferSizeFrames * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    // ============================================================
    // 音频处理循环
    // ============================================================

    data class AudioConfig(
        val sampleRate: Int = 48000,
        val bufferSizeFrames: Int = 256
    )

    private fun audioProcessingLoop(config: AudioConfig) {
        val frameBytes = 2 // 16-bit mono
        val bufferSizeBytes = config.bufferSizeFrames * frameBytes

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes.coerceAtLeast(
                    AudioRecord.getMinBufferSize(
                        config.sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            isRunning = false
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            recorder.release()
            isRunning = false
            return
        }

        val inputBuf = ByteArray(bufferSizeBytes)
        val outputBuf = ByteArray(bufferSizeBytes)
        NativeAudioProcessor.ensureLoaded()

        recorder.startRecording()
        Log.i(TAG, "Audio processing loop started")

        while (isRunning && audioTrack != null) {
            val read = recorder.read(inputBuf, 0, bufferSizeBytes)
            if (read > 0) {
                // 通过当前引擎处理
                NativeAudioProcessor.processAudio(inputBuf, outputBuf, read)
                // 环回输出
                audioTrack?.write(outputBuf, 0, read)
            }
        }

        recorder.stop()
        recorder.release()
        Log.i(TAG, "Audio processing loop ended")
    }
}
