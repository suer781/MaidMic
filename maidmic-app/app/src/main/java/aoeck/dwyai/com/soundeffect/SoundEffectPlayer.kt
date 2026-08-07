// SoundEffectPlayer.kt — 音效快捷播放器
// ============================================================
// Task: 基于 Android MediaPlayer 播放本地音效文件，实现"音效快捷播放"。
//
// 设计要点：
//   - 基于 MediaPlayer，AudioAttributes 使用 USAGE_MEDIA + CONTENT_TYPE_MUSIC：
//       * 走媒体音量流，使用用户当前媒体音量，**不调系统音量**（项目铁律）。
//       * 绝不调用 AudioManager.setStreamVolume / adjustStreamVolume。
//   - 不请求音频焦点（requestAudioFocus）：播放音效不得打断/抢占其他应用的媒体播放。
//   - 单实例：同一时刻仅播放一个音效，切换曲目先释放旧 MediaPlayer 再启动新播放。
//   - 令牌机制（仿 VoicePackPlayer）：每次 play() 自增 playToken，回调/结束时仅当自己
//     仍是当前令牌才触发 onComplete，避免旧播放污染新状态。
//   - onComplete 在播放自然结束 / 出错 / 被 stop 时于主线程回调，仅触发一次；
//     文件不存在时立即回调 onComplete（主线程）。
//   - MediaPlayer 在 UI 线程创建与启动（prepare 同步阻塞，本地小文件耗时可忽略）；
//     prepare 可能抛 IOException，用 try-catch 兜底，出错时释放并走 onComplete。
//   - release 后 MediaPlayer 的 listener 不会再回调，令牌机制已兜底，不会误触发。
//   - 线程安全：playing / playToken 标记 @Volatile，供 UI 线程查询与回调线程判断。
//
// 用法：
//   val player = SoundEffectPlayer()
//   player.play(file) { /* onComplete，主线程回调 */ }
//   ...
//   player.stop()          // 主动停止
//   player.isPlaying()     // 查询状态
//   // 页面退出时记得 stop() 释放资源

package aoeck.dwyai.com.soundeffect

import aoeck.dwyai.com.AppLogger
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 音效快捷播放器。
 *
 * 基于 MediaPlayer 播放本地音频文件，走 USAGE_MEDIA 媒体音量流，**不调系统音量**（项目铁律），
 * **不请求音频焦点**，[onComplete] 在主线程回调。
 */
class SoundEffectPlayer {

    companion object {
        private const val TAG = "SoundEffectPlayer"
    }

    // 主线程 Handler：保证回调在主线程触发
    private val mainHandler = Handler(Looper.getMainLooper())

    // 播放状态（@Volatile 供 UI 线程查询）
    @Volatile private var playing = false

    // 播放令牌：每次 play() 自增。播放回调中仅当自己仍是"当前令牌"时才触发 finishPlay，
    // 避免"切换曲目时旧播放的回调把新播放也停掉"。
    @Volatile private var playToken = 0

    // 当前 MediaPlayer（同一时刻仅一个，切换曲目先释放旧的）
    private var mediaPlayer: MediaPlayer? = null

    // 当前播放的完成回调（仅触发一次，触发后置 null）
    private var onCompleteCallback: (() -> Unit)? = null

    /** 当前是否正在播放。供 UI 查询以切换播放/停止按钮态。 */
    fun isPlaying(): Boolean {
        val mp = mediaPlayer
        // playing 是令牌机制维护的权威状态；mp.isPlaying 仅对已启动的 MediaPlayer 可信，做防御
        return playing && mp != null && try {
            mp.isPlaying
        } catch (_: IllegalStateException) {
            false
        }
    }

    /**
     * 播放指定音效文件。
     *
     * - 若当前正在播放，会先停止并释放旧 MediaPlayer 再启动新播放。
     * - [onComplete] 在播放结束（自然播完 / 出错 / 被 stop）后于主线程回调，仅触发一次。
     * - 文件不存在时立即回调 onComplete（主线程）。
     *
     * @param file 本地音频文件（MP3 / WAV / OGG 等 MediaPlayer 支持的格式）。
     * @param onComplete 播放完成回调（主线程）。
     */
    fun play(file: File, onComplete: () -> Unit) {
        // 先停掉当前播放（防止重叠）。stopInternal 会自增 playToken 使旧播放失效，
        // 此处 notifyComplete=false：切换曲目场景下旧回调不触发，由新播放接管状态。
        stopInternal(notifyComplete = false)

        if (!file.exists()) {
            AppLogger.e(TAG, "play: 文件不存在 ${file.absolutePath}")
            // 兜底回调，避免 UI 卡在"播放中"状态
            mainHandler.post(onComplete)
            return
        }

        // 领取本次播放令牌
        val myToken = ++playToken
        onCompleteCallback = onComplete
        playing = true

        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            // 媒体音量流：USAGE_MEDIA + CONTENT_TYPE_MUSIC，
            // **不调系统音量**、**不请求音频焦点**（项目铁律）。
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            // 自然播完 / 出错：仅当自己仍是当前令牌时才收尾，避免旧播放污染新状态
            mp.setOnCompletionListener {
                if (myToken == playToken) finishPlay()
            }
            mp.setOnErrorListener { _, _, _ ->
                if (myToken == playToken) finishPlay()
                true // 错误已处理，不再触发 OnCompletionListener
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            AppLogger.i(TAG, "play: ${file.name} token=$myToken")
        } catch (e: Exception) {
            // prepare 可能抛 IOException 等，出错时释放本次创建的实例并走 onComplete 兜底
            AppLogger.e(TAG, "play: 启动失败 ${file.name}", e)
            try {
                mp?.release()
            } catch (_: Exception) {
            }
            if (mediaPlayer === mp) mediaPlayer = null
            if (myToken == playToken) finishPlay()
        }
    }

    /**
     * 停止播放并释放 MediaPlayer。可在任意线程调用。
     * 若当前有未触发的 onComplete 回调，会触发它（主线程）。
     */
    fun stop() {
        stopInternal(notifyComplete = true)
    }

    // ============================================================
    // 内部：停止 / 完成
    // ============================================================

    /**
     * 停止播放并清理。
     * @param notifyComplete 是否触发未完成的 onComplete 回调。
     *                        play() 内部切换曲目时传 false（避免旧回调污染新状态），
     *                        外部 stop() 传 true。
     */
    private fun stopInternal(notifyComplete: Boolean) {
        playing = false
        // 自增令牌使任何 in-flight 的播放回调失效（其不再触发 finishPlay）
        playToken++
        val mp = mediaPlayer
        mediaPlayer = null
        // release 后 MediaPlayer 的 listener 不会再回调，令牌机制已兜底
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        if (notifyComplete) {
            finishPlay()
        } else {
            // 切换曲目场景：清掉旧回调，不触发（新 play 会设置新回调）
            onCompleteCallback = null
        }
    }

    /** 触发 onComplete 回调（主线程，仅一次）。 */
    private fun finishPlay() {
        playing = false
        val cb = onCompleteCallback
        onCompleteCallback = null
        if (cb != null) {
            mainHandler.post { cb() }
        }
    }
}
