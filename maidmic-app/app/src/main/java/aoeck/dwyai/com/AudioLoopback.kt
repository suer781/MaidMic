// AudioLoopback.kt — 麦克风 → DSP → 扬声器环回
// ============================================================
// 方案C 实现：AudioRecord 捕获 → JNI 处理 → AudioTrack 播放

package aoeck.dwyai.com

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

object AudioLoopback {

    private const val TAG = "AudioLoopback"
    private const val SAMPLE_RATE = 48000
    private const val CHANNELS = 1           // 单声道
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val BUFFER_FRAMES = 256    // 每帧样本数

    private var isRunning = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    /** 是否正在运行 */
    fun isActive(): Boolean = isRunning

    /** 启动音频处理 */
    fun start() {
        if (isRunning) return

        NativeAudioProcessor.ensureLoaded()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
            .coerceAtLeast(BUFFER_FRAMES * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNELS,
            ENCODING,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            audioRecord?.release()
            audioRecord = null
            return
        }

        val trackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, ENCODING).coerceAtLeast(bufferSize)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        isRunning = true
        audioRecord?.startRecording()
        audioTrack?.play()

        recordThread = Thread({ processingLoop() }, "maidmic-loopback")
        recordThread?.start()

        Log.i(TAG, "Loopback started")
    }

    /** 停止音频处理 */
    fun stop() {
        isRunning = false
        recordThread?.join(1000)
        recordThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null

        Log.i(TAG, "Loopback stopped")
    }

    private fun processingLoop() {
        val bufSize = BUFFER_FRAMES * 2 // 16-bit = 2 bytes per frame
        val inputBuf = ByteArray(bufSize)
        val outputBuf = ByteArray(bufSize)

        while (isRunning) {
            val read = audioRecord?.read(inputBuf, 0, bufSize) ?: -1
            if (read <= 0) {
                Thread.sleep(1)
                continue
            }

            // JNI 处理
            NativeAudioProcessor.processAudio(inputBuf, outputBuf, read)

            // 播放处理后的音频
            audioTrack?.write(outputBuf, 0, read)
        }
    }
}
