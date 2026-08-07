// FloatingBallService.kt — 悬浮球前台服务
// ============================================================
// Task 7 + Task 8:
//  - 前台 Service，保活进程 + 通知栏显示运行状态
//  - Task 8: onCreate 创建 FloatingBallView 并通过 WindowManager 挂载到屏幕
//  - onDestroy 移除 View 并释放资源
//  - 提供 setBallState() 控制球状态（供 Task 9 手势识别 / 录制流程调用）
//  - Task 7: 持有音效播放器（SoundEffectPlayer）单例，面板收回不停播，
//    播放中状态（playingEffectId）跨面板展开/收起存活，Service 销毁时 stop + 释放
//  - foregroundServiceType = microphone 与 MaidMicKeepAliveService 模板对齐，
//    复用 FOREGROUND_SERVICE_MICROPHONE 权限（Manifest 已声明）。
//
// 默认关闭，需用户在设置页主动开启（开关逻辑见 MainActivity.SettingsPage）。

package aoeck.dwyai.com.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.MainActivity
import aoeck.dwyai.com.NativeAudioProcessor
import aoeck.dwyai.com.soundeffect.SoundEffectPlayer
import aoeck.dwyai.com.voicepack.VoicePack
import aoeck.dwyai.com.voicepack.VoicePackPlayer
import aoeck.dwyai.com.voicepack.VoicePackRecorder
import aoeck.dwyai.com.voicepack.VoicePackStore

class FloatingBallService : Service() {

    companion object {
        const val CHANNEL_ID = "maidmic_floating"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "aoeck.dwyai.com.STOP_FLOATING"

        /**
         * 控制球状态（供外部 Activity / 录制流程静态调用）。
         * 调用方式：FloatingBallService.setBallState(context, FloatingBallView.BallState.RECORDING)
         */
        fun setBallState(context: Context, state: FloatingBallView.BallState) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_SET_STATE
                putExtra(EXTRA_STATE, state.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private const val ACTION_SET_STATE = "aoeck.dwyai.com.FLOATING_SET_STATE"
        private const val EXTRA_STATE = "ball_state"
    }

    private var windowManager: WindowManager? = null
    private var ballView: FloatingBallView? = null
    private var isViewAttached = false

    // Task 10: 录音器 / 播放器 / 面板（懒初始化，跨面板展开/收起周期复用）
    private var recorder: VoicePackRecorder? = null
    private var player: VoicePackPlayer? = null
    private var panel: FloatingPanel? = null

    // Task 7: 音效快捷播放器（Service 级单例，跨面板展开/收起复用，Service 销毁时 stop + 释放）
    private var effectPlayer: SoundEffectPlayer? = null
    // Task 7: 当前播放中的音效 id（Service 级 Compose state，面板收回再展开仍显示播放中并可停止）
    private val effectPlayingId = mutableStateOf<String?>(null)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLogger.i("FloatingBall", "FloatingBallService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 收到停止指令 → 移除 View 并停止自身
        if (intent?.action == ACTION_STOP) {
            AppLogger.i("FloatingBall", "收到停止指令，移除悬浮球")
            removeView()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 收到状态切换指令 → 更新球状态
        if (intent?.action == ACTION_SET_STATE) {
            val stateName = intent.getStringExtra(EXTRA_STATE)
            val state = runCatching {
                FloatingBallView.BallState.valueOf(stateName ?: "")
            }.getOrNull()
            // 确保前台通知存在（防止首次即收到状态切换的边界情况）
            if (!isForegroundStarted) {
                startForegroundWithNotification()
            }
            // 若 View 尚未挂载（异常路径），先补挂载
            if (!isViewAttached && OverlayPermissionHelper.canDrawOverlays(this)) {
                attachBallView()
            }
            if (state != null) {
                ballView?.setState(state)
                AppLogger.i("FloatingBall", "球状态切换 -> $state")
            }
            return START_STICKY
        }

        // 正常启动：挂载 View + 启动前台通知
        val notification = buildNotification()
        // targetSdk 34：必须显式传入 foregroundServiceType
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        isForegroundStarted = true

        // 挂载悬浮球 View（仅当权限已授予时）
        if (OverlayPermissionHelper.canDrawOverlays(this)) {
            attachBallView()
        } else {
            AppLogger.e("FloatingBall", "无悬浮窗权限，无法挂载 View", null)
        }

        return START_STICKY
    }

    private var isForegroundStarted = false

    /** 兜底：状态切换时若尚未 startForeground，补一次 */
    private fun startForegroundWithNotification() {
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            isForegroundStarted = true
        } catch (e: Exception) {
            AppLogger.e("FloatingBall", "startForeground 失败", e)
        }
    }

    /** 创建悬浮球 View 并通过 WindowManager 添加到屏幕 */
    private fun attachBallView() {
        if (isViewAttached || ballView != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val view = FloatingBallView(this)
            val params = FloatingBallView.buildLayoutParams(this)
            // 注入引用，供 View 拖动时 updateViewLayout
            view.wmLayoutParams = params
            view.windowManager = wm

            // 设置初始状态为 IDLE
            view.setState(FloatingBallView.BallState.IDLE)

            // Task 9 + Task 10: 注入交互回调（手势 → 动作委托）
            // 五个回调全部接线：展开/收起面板 + PTT 录音/停止 + 播放最近语音包
            view.interactionCallback = object : BallInteractionCallback {
                override fun onExpandPanel() {
                    AppLogger.i("FloatingBall", "手势：单击 → 展开面板")
                    expandPanel()
                }

                override fun onCollapsePanel() {
                    AppLogger.i("FloatingBall", "手势：单击 → 收起面板")
                    collapsePanel()
                }

                override fun onStartRecording(overwrite: Boolean) {
                    AppLogger.i("FloatingBall", "手势：长按 → 开始 PTT 录音 overwrite=$overwrite")
                    startRecordingInternal(overwrite)
                }

                override fun onStopRecording(delayMs: Long) {
                    AppLogger.i("FloatingBall", "手势：松手 → 停止录音 delay=${delayMs}ms")
                    recorder?.stopRecording(delayMs)
                }

                override fun onPlayLatest() {
                    AppLogger.i("FloatingBall", "手势：双击 → 播放最近语音包")
                    playLatestInternal()
                }
            }

            // 拖动位置日志（保留，供调试）
            view.onDrag = { x, y ->
                AppLogger.d("FloatingBall", "悬浮球拖动到 ($x, $y)")
            }

            wm.addView(view, params)
            ballView = view
            isViewAttached = true
            AppLogger.i("FloatingBall", "悬浮球 View 已挂载")
        } catch (e: Exception) {
            AppLogger.e("FloatingBall", "挂载悬浮球 View 失败", e)
        }
    }

    // ============================================================
    // Task 10: 面板展开/收起 + 录音/播放接线
    // ============================================================

    /** 懒初始化录音器（跨面板周期复用，Service 销毁时释放） */
    private fun getRecorder(): VoicePackRecorder {
        return recorder ?: VoicePackRecorder(this).also { recorder = it }
    }

    /** 懒初始化播放器（跨面板周期复用，Service 销毁时释放） */
    private fun getPlayer(): VoicePackPlayer {
        return player ?: VoicePackPlayer().also { player = it }
    }

    /** 懒初始化音效播放器（跨面板周期复用，Service 销毁时释放） */
    private fun getEffectPlayer(): SoundEffectPlayer {
        return effectPlayer ?: SoundEffectPlayer().also { effectPlayer = it }
    }

    /**
     * 展开面板：创建 FloatingPanel ComposeView 并添加到 WindowManager。
     * 位置基于悬浮球当前位置（球上方优先，空间不足则下方）。
     */
    private fun expandPanel() {
        if (panel?.isShowing() == true) return
        val wm = windowManager ?: return
        val ball = ballView ?: return
        val params = ball.wmLayoutParams ?: return

        // 球的屏幕坐标（gravity TOP|START，x/y 即左上角）
        val ballX = params.x
        val ballY = params.y
        val ballSize = ball.width.coerceAtLeast(dp(56f).toInt())

        // 创建面板作用域（注入 recorder/player provider + 状态回调）
        val scope = FloatingPanelScope(
            context = this,
            recorderProvider = { getRecorder() },
            playerProvider = { getPlayer() },
            effectPlayerProvider = { getEffectPlayer() },
            effectPlayingId = effectPlayingId,
            onStartManualRecording = { startRecordingInternal(overwrite = false) },
            onStopManualRecording = { getRecorder().stopRecording(delayMs = 0) },
            onPlayCompleted = {
                // 播放完成 → 球恢复 IDLE（用 ballView? 避免捕获已释放的旧引用）
                ballView?.setState(FloatingBallView.BallState.IDLE)
                panel?.notifyExternalStateChange()
                AppLogger.i("FloatingBall", "播放完成 → 球状态 IDLE")
            },
        )

        val p = FloatingPanel(
            context = this,
            windowManager = wm,
            scope = scope,
            onCollapse = { collapsePanel() },
        )
        panel = p
        p.show(ballX, ballY, ballSize)
    }

    /** 收起面板：从 WindowManager 移除面板 View + 同步球的 isPanelExpanded 标志 */
    private fun collapsePanel() {
        panel?.dismiss()
        panel = null
        // 同步球的内部面板状态标志（防止外部收起后球仍认为面板展开）
        ballView?.syncPanelExpanded(false)
    }

    /**
     * 构造录音器回调（PTT 长按 / 手动提前录音共用）。
     * - onRecordingStart: 球变红呼吸
     * - onRecordingStop: 有包 → 球变绿（READY_TO_PLAY）；无包 → 球恢复 IDLE
     * - onError: Toast 提示 + 球恢复 IDLE
     */
    private fun makeRecorderCallback(): VoicePackRecorder.Callback =
        object : VoicePackRecorder.Callback {
            override fun onRecordingStart() {
                ballView?.setState(FloatingBallView.BallState.RECORDING)
                panel?.notifyExternalStateChange()
                AppLogger.i("FloatingBall", "录音开始 → 球状态 RECORDING")
            }

            override fun onRecordingStop(voicePack: VoicePack?) {
                if (voicePack != null) {
                    ballView?.setState(FloatingBallView.BallState.READY_TO_PLAY)
                    AppLogger.i("FloatingBall", "录音完成 → 球状态 READY_TO_PLAY (id=${voicePack.id})")
                } else {
                    ballView?.setState(FloatingBallView.BallState.IDLE)
                    AppLogger.w("FloatingBall", "录音完成但无语音包 → 球状态 IDLE")
                }
                panel?.notifyExternalStateChange()
            }

            override fun onError(message: String) {
                Toast.makeText(this@FloatingBallService, message, Toast.LENGTH_SHORT).show()
                ballView?.setState(FloatingBallView.BallState.IDLE)
                panel?.notifyExternalStateChange()
                AppLogger.e("FloatingBall", "录音错误: $message", null)
            }
        }

    /**
     * 开始录音（PTT 长按 / 手动提前录音共用入口）。
     * 确保引擎已加载，然后调用 VoicePackRecorder.startRecording。
     */
    private fun startRecordingInternal(overwrite: Boolean) {
        NativeAudioProcessor.ensureLoaded()
        val r = getRecorder()
        if (r.isRecording()) {
            AppLogger.w("FloatingBall", "startRecordingInternal: 已在录音中，忽略")
            return
        }
        r.startRecording(makeRecorderCallback(), overwrite)
    }

    /**
     * 播放最近一条语音包（双击球触发）。
     * 无语音包时 Toast 提示；播放完成后球恢复 IDLE。
     */
    private fun playLatestInternal() {
        val latest = VoicePackStore.getLatest(this)
        if (latest == null) {
            Toast.makeText(this, "暂无语音包，请先录音", Toast.LENGTH_SHORT).show()
            AppLogger.w("FloatingBall", "playLatestInternal: 无语音包")
            return
        }
        val p = getPlayer()
        p.play(this, latest) {
            ballView?.setState(FloatingBallView.BallState.IDLE)
            panel?.notifyExternalStateChange()
            AppLogger.i("FloatingBall", "播放完成 → 球状态 IDLE")
        }
        AppLogger.i("FloatingBall", "开始播放最近语音包: ${latest.id}")
    }

    /** 从 WindowManager 移除悬浮球 View */
    private fun removeView() {
        // Task 10: 移除球前先收起面板 + 停止录音/播放，避免悬空引用
        collapsePanel()
        try { recorder?.stopRecording(0) } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        // Task 7: 停止音效播放（Service 销毁时释放，避免残留音频）+ 清空播放中状态
        try { effectPlayer?.stop() } catch (_: Exception) {}

        val view = ballView ?: return
        val wm = windowManager ?: return
        try {
            wm.removeView(view)
            AppLogger.i("FloatingBall", "悬浮球 View 已移除")
        } catch (e: Exception) {
            AppLogger.e("FloatingBall", "移除悬浮球 View 失败", e)
        } finally {
            view.release()
            ballView = null
            windowManager = null
            isViewAttached = false
            recorder = null
            player = null
            effectPlayer = null
            effectPlayingId.value = null
            panel = null
        }
    }

    /** dp → px 转换（用于面板定位时获取球尺寸兜底值） */
    private fun dp(value: Float): Float =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )

    override fun onDestroy() {
        super.onDestroy()
        removeView()
        AppLogger.i("FloatingBall", "FloatingBallService onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MaidMic 悬浮球",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "悬浮球运行状态通知"
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
        val stopIntent = Intent(this, FloatingBallService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MaidMic 悬浮球")
            .setContentText("悬浮球运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
