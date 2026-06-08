// MaidMicInputMethodService.kt — 方案E: 输入法挂载
// ============================================================
// 通过自定义输入法服务来实现音频处理和注入。
// 此方案可以在后台运行，利用 IME 的持久化特性。

package aoeck.dwyai.com.bridge.ime

import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.inputmethod.EditorInfo
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor

class MaidMicInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "MaidMicIME"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var processingThread: Thread? = null
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "MaidMic IME 服务已创建")
        NativeAudioProcessor.ensureLoaded()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        AppLogger.i(TAG, "IME 输入视图已启动")
        // 在这里可以启动音频处理
        startAudioProcessing()
    }

    override fun onDestroy() {
        stopAudioProcessing()
        super.onDestroy()
        AppLogger.i(TAG, "MaidMic IME 服务已销毁")
    }

    private fun startAudioProcessing() {
        if (isProcessing) return

        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
            val bufSize = minBufSize.coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                ENCODING,
                bufSize * 4
            )

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                isProcessing = true

                processingThread = Thread({ processingLoop(bufSize) }, "ime_audio_processing")
                processingThread?.start()

                AppLogger.i(TAG, "IME 音频处理已启动")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动音频处理失败", e)
        }
    }

    private fun processingLoop(bufSize: Int) {
        val inputBuffer = ByteArray(bufSize)
        val processedBuffer = ByteArray(bufSize)

        while (isProcessing && !Thread.interrupted()) {
            try {
                val bytesRead = audioRecord?.read(inputBuffer, 0, bufSize) ?: -1
                if (bytesRead > 0) {
                    NativeAudioProcessor.processAudio(inputBuffer, processedBuffer, bytesRead)
                    // 这里可以添加音频输出逻辑
                }
            } catch (e: Exception) {
                if (isProcessing) {
                    AppLogger.e(TAG, "处理循环异常", e)
                }
                break
            }
        }
    }

    private fun stopAudioProcessing() {
        isProcessing = false
        processingThread?.interrupt()
        processingThread?.join(3000)
        processingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        AppLogger.i(TAG, "IME 音频处理已停止")
    }
}
