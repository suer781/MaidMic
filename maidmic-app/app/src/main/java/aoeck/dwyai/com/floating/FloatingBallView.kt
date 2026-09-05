// FloatingBallView.kt — 悬浮球自定义 View（交互 v2）
// ============================================================
// 56dp 圆形悬浮球，传统 View（非 Compose），通过 WindowManager 挂载。
//
// 光影渐变球体：RadialGradient 模拟左上高光 + 右下暗部，立体感。
// 三态色：IDLE(紫) / RECORDING(红呼吸) / READY_TO_PLAY(绿)。
//
// ===== 交互 v2（手势模型）=====
//   - 单击（立即响应，无延迟）：
//       * 绿色态（有新包未听）→ 播放最近语音包（贴合"绿球=待播放"语义）
//       * 其他态 → 展开/收起面板
//   - 长按（默认 600ms，可配置 500~5000）：按住时绘制进度弧，
//       转满一圈 → 震动×2 → 球变红呼吸 → 开始 PTT 录音 → 松手续录 0.5s 停止
//       绿色态长按 = 覆盖重录（overwrite=true）
//   - 拖动（>16dp）：移动位置，松手后吸附到最近的左右边缘（弹性动画），
//       位置持久化（重启恢复）；IDLE 态贴边后自动半透明（触摸恢复）
//   - 双击手势已移除（它是单击 300ms 延迟的根源，播放由绿色态单击承接）
//
// 手势优先级：拖动 > 长按 > 单击
//   - ACTION_MOVE 超阈值 → 立即取消长按与进度弧
//   - 长按已触发（录音中）→ 忽略后续移动
//
// 注意：本 View 不直接操作 WindowManager 生命周期，由 FloatingBallService 创建并 addView。
// View 仅持有 layoutParams / windowManager 引用，用于拖动/吸附时 updateViewLayout。
// 录音/播放的实际逻辑通过 [BallInteractionCallback] 委托给 Service 实现。

package aoeck.dwyai.com.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * 悬浮球自定义 View（交互 v2）。
 *
 * 用法（在 FloatingBallService 中）：
 * ```kotlin
 * val view = FloatingBallView(this)
 * val params = FloatingBallView.buildLayoutParams(this)
 * view.wmLayoutParams = params
 * view.windowManager = windowManager
 * view.interactionCallback = myCallback
 * windowManager.addView(view, params)
 * view.setState(FloatingBallView.BallState.IDLE)
 * ```
 */
class FloatingBallView(context: Context) : View(context) {

    /** 悬浮球状态枚举 */
    enum class BallState { IDLE, RECORDING, READY_TO_PLAY }

    companion object {
        /** 球体直径（dp） */
        const val BALL_SIZE_DP = 56f
        /** 拖动判定阈值（dp）：超过此距离才认为是拖动，否则视为点击 */
        const val DRAG_THRESHOLD_DP = 16f
        /** 录制态呼吸周期（ms） */
        const val BREATH_DURATION_MS = 400L
        /** 呼吸透明度下限 / 上限 */
        const val BREATH_ALPHA_MIN = 0.4f
        const val BREATH_ALPHA_MAX = 1.0f

        // ===== 手势参数（交互 v2）=====
        /** 长按触发默认时长（ms）：600ms ≈ 微信按住说话级响应 */
        const val HOLD_DURATION_DEFAULT_MS = 600L
        /** 长按触发最小时长（ms） */
        const val HOLD_DURATION_MIN_MS = 500L
        /** 长按触发最大时长（ms） */
        const val HOLD_DURATION_MAX_MS = 5000L
        /** 松手续录时长（ms）：用户松手后继续录 0.5s */
        const val RELEASE_CONTINUE_RECORD_MS = 500L

        /** 边缘吸附动画时长（ms） */
        private const val SNAP_DURATION_MS = 220L
        /** 吸附后与屏幕边缘的间距（px 由 dp 换算） */
        private const val EDGE_MARGIN_DP = 6f
        /** 贴边半透明透明度（IDLE 态） */
        private const val EDGE_DIM_ALPHA = 0.55f
        /** 长按进度弧线宽（dp） */
        private const val PROGRESS_STROKE_DP = 2.5f
        /** 长按按住时的放大上限（相对 1.0） */
        private const val PRESS_SCALE_MAX = 1.08f

        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "maidmic_prefs"
        /** 长按时长持久化 key */
        private const val KEY_HOLD_DURATION_MS = "hold_duration_ms"
        /** 球位置持久化 key（吸附后的 x/y） */
        private const val KEY_BALL_X = "floating_ball_x"
        private const val KEY_BALL_Y = "floating_ball_y"

        /**
         * 构造 WindowManager.LayoutParams：
         *  - TYPE_APPLICATION_OVERLAY：Android 8.0+ 悬浮窗类型
         *  - FLAG_NOT_FOCUSABLE：不抢焦点
         *  - FLAG_LAYOUT_NO_LIMITS：允许超出屏幕边界（拖动到边缘时不会被裁剪）
         *  - 格式 TRANSLUCENT：半透明，让球体边缘渐变自然
         *  - 初始位置：优先恢复上次吸附保存的位置；无记录时屏幕右侧 1/3 处
         */
        fun buildLayoutParams(context: Context): WindowManager.LayoutParams {
            val size = dpToPx(context, BALL_SIZE_DP).toInt()
            val dm = context.resources.displayMetrics
            val margin = dpToPx(context, EDGE_MARGIN_DP).toInt()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedX = prefs.getInt(KEY_BALL_X, Int.MIN_VALUE)
            val savedY = prefs.getInt(KEY_BALL_Y, Int.MIN_VALUE)

            val initialX: Int
            val initialY: Int
            if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
                // 恢复上次位置（clamp 进屏幕，防止分辨率变化后跑出屏外）
                initialX = savedX.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
                initialY = savedY.coerceIn(dpToPx(context, 24f).toInt(),
                    (dm.heightPixels - size - dpToPx(context, 24f).toInt()).coerceAtLeast(0))
            } else {
                initialX = dm.widthPixels - size - margin
                initialY = (dm.heightPixels * 0.33f).toInt()
            }

            return WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = android.graphics.PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = size
                height = size
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = initialX
                y = initialY
            }
        }

        private fun dpToPx(context: Context, dp: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
            )
    }

    // ===== 画笔 =====
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.2f)
    }
    /** 长按进度弧画笔 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(PROGRESS_STROKE_DP)
        strokeCap = Paint.Cap.ROUND
        color = 0x99FFFFFF.toInt()
    }

    /** 边缘细圈基础透明度（非录制态也保持半透明，避免硬白圈） */
    private val rimBaseAlpha = 0.66f
    /** 高光亮点基础透明度 */
    private val highlightBaseAlpha = 0.80f

    // ===== 状态相关 =====
    private var currentState = BallState.IDLE
    /** 呼吸动画当前透明度（0.4 ~ 1.0），非录制态为 1.0 */
    private var breathAlpha = BREATH_ALPHA_MAX
    private var breathAnimator: ValueAnimator? = null

    // 三态色：高光色 / 中心色 / 边缘色（暗部）
    private var highlightColor = Color.parseColor("#FFF3E5F5")
    private var centerColor = Color.parseColor("#FFCE93D8")
    private var edgeColor = Color.parseColor("#FF4A2561")

    private val ballSizePx: Int = dpToPx(BALL_SIZE_DP).toInt()
    private val dragThresholdPx: Float = dpToPx(DRAG_THRESHOLD_DP)

    // ===== WindowManager 引用（拖动时用于 updateViewLayout）=====
    var windowManager: WindowManager? = null
    /** 当前 View 在 WindowManager 中的 LayoutParams，由 Service 注入 */
    var wmLayoutParams: WindowManager.LayoutParams? = null

    // ===== 交互回调 =====
    /** 交互回调（手势 → 动作委托，由 Service 注入） */
    var interactionCallback: BallInteractionCallback? = null

    /** 拖动回调，参数为当前 rawX / rawY（保留用于位置日志） */
    var onDrag: ((x: Float, y: Float) -> Unit)? = null

    // ===== 长按时长配置 =====
    /** 长按触发时长（ms），从 SharedPreferences 读取，默认 600 */
    var holdDurationMs: Long = HOLD_DURATION_DEFAULT_MS
        private set

    /** 从 SharedPreferences 刷新长按时长（设置页修改后下次手势生效） */
    fun refreshHoldDuration() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        holdDurationMs = prefs.getInt(KEY_HOLD_DURATION_MS, HOLD_DURATION_DEFAULT_MS.toInt())
            .toLong()
            .coerceIn(HOLD_DURATION_MIN_MS, HOLD_DURATION_MAX_MS)
    }

    init {
        // 启用硬件加速，让 RadialGradient 与动画更流畅
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // 读取用户配置的长按时长
        refreshHoldDuration()
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ballSizePx, ballSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // 半径留出描边宽度，避免边缘被裁
        val radius = (minOf(width, height) / 2f) - rimPaint.strokeWidth

        // 按住时轻微放大（进度反馈的一部分）
        if (pressScale > 1.001f) {
            canvas.save()
            canvas.scale(pressScale, pressScale, cx, cy)
            drawBall(canvas, cx, cy, radius)
            canvas.restore()
        } else {
            drawBall(canvas, cx, cy, radius)
        }

        // 长按进度弧：从顶部（-90°）顺时针绘制，转满一圈触发录音
        if (pressProgress > 0.001f) {
            val arcRadius = radius - progressPaint.strokeWidth
            canvas.drawArc(
                cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius,
                -90f, 360f * pressProgress, false, progressPaint
            )
        }
    }

    /** 绘制球体本体（高光渐变 + 亮点 + 边圈），供普通/放大两分支复用 */
    private fun drawBall(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // 高光中心偏移：左上方向，模拟光源从左上方照射球体
        val highlightOffsetX = -radius * 0.35f
        val highlightOffsetY = -radius * 0.35f

        // 主球体：径向渐变（高光色 → 中心色 → 边缘暗色）
        // bodyPaint 使用 shader，paint.alpha 作为全局透明度乘子（与呼吸效果联动）
        bodyPaint.alpha = (breathAlpha * 255).toInt().coerceIn(0, 255)
        bodyPaint.shader = RadialGradient(
            cx + highlightOffsetX,
            cy + highlightOffsetY,
            radius * 1.4f,
            intArrayOf(highlightColor, centerColor, edgeColor),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, bodyPaint)

        // 顶部小高光亮点：增强球体立体感（模拟镜面反射）
        // 非 shader 绘制：Paint.setAlpha 会覆盖颜色 alpha，故每帧把
        // "基础透明度 × 呼吸系数" 烘焙进颜色，保证呼吸时高光也同步明暗
        highlightPaint.shader = null
        highlightPaint.color = argb(highlightBaseAlpha * breathAlpha, 0xFFFFFF)
        canvas.drawCircle(
            cx + highlightOffsetX * 0.8f,
            cy + highlightOffsetY * 0.8f,
            radius * 0.22f,
            highlightPaint
        )

        // 边缘细圈：让球与背景分离更清晰（同样烘焙 alpha，呼吸同步）
        rimPaint.color = argb(rimBaseAlpha * breathAlpha, 0xFFFFFF)
        canvas.drawCircle(cx, cy, radius, rimPaint)
    }

    /** 由透明度比例 [alpha01]（0~1）与 RGB 值合成 ARGB 颜色 */
    private fun argb(alpha01: Float, rgb: Int): Int {
        val a = (alpha01 * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    /**
     * 切换球状态。
     * - IDLE: 紫色光影渐变，持续显示
     * - RECORDING: 红色光影渐变 + 呼吸闪烁（透明度 0.4↔1.0，400ms 周期）
     * - READY_TO_PLAY: 绿色光影渐变，持续显示
     *
     * 状态切回 IDLE / READY_TO_PLAY 时自动结束录音周期标志，
     * 允许新的手势。
     */
    fun setState(state: BallState) {
        if (state == currentState) return
        currentState = state
        when (state) {
            BallState.IDLE -> {
                highlightColor = Color.parseColor("#FFF3E5F5")
                centerColor = Color.parseColor("#FFCE93D8")
                edgeColor = Color.parseColor("#FF4A2561")
                stopBreath()
                isRecordingActive = false
                isLongPressTriggered = false
                // IDLE 态若贴边 → 恢复半透明省视线（延迟一帧让状态切换先可见）
                if (isDockedNearEdge) {
                    postDelayed({ dimToEdge() }, 300L)
                }
            }
            BallState.RECORDING -> {
                highlightColor = Color.parseColor("#FFFFCDD2")
                centerColor = Color.parseColor("#FFEF5350")
                edgeColor = Color.parseColor("#FFB71C1C")
                startBreath()
                // 录音中始终保持全亮（状态可见性优先）
                animate().alpha(1f).setDuration(120L).start()
            }
            BallState.READY_TO_PLAY -> {
                highlightColor = Color.parseColor("#FFC8E6C9")
                centerColor = Color.parseColor("#FF66BB6A")
                edgeColor = Color.parseColor("#FF1B5E20")
                stopBreath()
                isRecordingActive = false
                isLongPressTriggered = false
            }
        }
        invalidate()
    }

    /** 当前状态（供 Service 查询） */
    fun getState(): BallState = currentState

    /**
     * 同步面板展开状态（供 Service 在面板被外部收起时调用）。
     *
     * 场景：面板因"点击面板外区域"或"拖动球"而收起时，Service 会调用此方法
     * 将 [isPanelExpanded] 置 false，使下次单击球能正确触发 onExpandPanel
     * 而非再次 onCollapsePanel（否则会连续两次收起，需多按一次才能展开）。
     */
    fun syncPanelExpanded(expanded: Boolean) {
        isPanelExpanded = expanded
    }

    /** 启动呼吸动画：透明度在 0.4 ~ 1.0 之间往复 */
    private fun startBreath() {
        stopBreath()
        breathAnimator = ValueAnimator.ofFloat(BREATH_ALPHA_MIN, BREATH_ALPHA_MAX).apply {
            duration = BREATH_DURATION_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                breathAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 停止呼吸动画，恢复满透明度 */
    private fun stopBreath() {
        breathAnimator?.cancel()
        breathAnimator = null
        breathAlpha = BREATH_ALPHA_MAX
    }

    // ============================================================
    // 手势识别（交互 v2）
    // ============================================================

    // ===== Handler（主线程，用于长按延迟检测）=====
    private val handler = Handler(Looper.getMainLooper())

    /** 长按定时器 Runnable：到达 holdDurationMs 时触发 */
    private val longPressRunnable = Runnable { triggerLongPress() }

    // ===== 手势状态 =====
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    /** 是否正在拖动 */
    private var isDragging = false
    /** 长按是否已触发（true = 正在 PTT 录音中，等待松手） */
    private var isLongPressTriggered = false
    /** 录音周期是否进行中（长按触发 → 录音+处理+存包完成前为 true，阻止新手势） */
    private var isRecordingActive = false
    /** 面板是否已展开（单击切换） */
    private var isPanelExpanded = false
    /**
     * 抑制本次点击的单击触发。
     *
     * 背景：面板挂载时带 FLAG_WATCH_OUTSIDE_TOUCH，点击球（面板外）会先让面板
     * 收到 ACTION_OUTSIDE 而收起（collapsePanel → syncPanelExpanded(false)），
     * 随后球自己的单击回调再触发时 isPanelExpanded 已被置 false，
     * 导致"想关面板却再次展开"（面板闪一下又出现）。
     *
     * 因此在按下时若面板已展开，标记本次点击为"关闭面板"动作，松手时直接忽略单击。
     */
    private var suppressClickOnUp = false
    /** 球当前是否停靠在屏幕边缘（用于贴边半透明判断） */
    private var isDockedNearEdge = false

    // ===== 长按进度反馈 =====
    /** 按住进度（0~1），驱动进度弧与按住放大 */
    private var pressProgress = 0f
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = false
                isLongPressTriggered = false

                // 触摸即恢复全亮（贴边半透明态 → 激活）
                if (alpha < 1f) animate().alpha(1f).setDuration(120L).start()

                // 录音周期进行中（松手等待处理阶段）→ 忽略新手势
                if (isRecordingActive) return true

                // 每次按下时刷新长按时长（用户可能在设置页修改过）
                refreshHoldDuration()

                // 取消进行中的吸附动画（用户要重新拿起球）
                snapAnimator?.cancel()

                // 启动长按检测定时器 + 进度弧动画
                handler.postDelayed(longPressRunnable, holdDurationMs)
                startPressFeedback()
            }
            MotionEvent.ACTION_MOVE -> {
                // 长按已触发（录音中）→ 忽略移动，不进入拖动模式
                if (isLongPressTriggered) return true

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // 超过阈值才进入拖动模式（拖动优先：取消长按和进度弧）
                if (!isDragging && hypot(dx, dy) > dragThresholdPx) {
                    isDragging = true
                    suppressClickOnUp = false
                    handler.removeCallbacks(longPressRunnable)
                    cancelPressFeedback()
                    // 拖动开始时若面板已展开，先收起面板
                    // （面板不跟随球移动，拖动时直接收起避免面板悬空）
                    if (isPanelExpanded) {
                        isPanelExpanded = false
                        interactionCallback?.onCollapsePanel()
                    }
                }
                if (isDragging) {
                    // 增量移动：本次 MOVE 相对上一次的位移，并 clamp 进屏幕
                    val deltaX = event.rawX - lastRawX
                    val deltaY = event.rawY - lastRawY
                    updatePosition(deltaX, deltaY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDrag?.invoke(event.rawX, event.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                // 取消长按定时器（无论是否已触发，都清理）
                handler.removeCallbacks(longPressRunnable)

                when {
                    // 优先级 1：长按已触发 → 松手停止录音
                    isLongPressTriggered -> {
                        // 通知停止录音（松手后继续录 0.5s 再停）
                        interactionCallback?.onStopRecording(RELEASE_CONTINUE_RECORD_MS)
                        isLongPressTriggered = false
                        isDragging = false
                        // isRecordingActive 保持 true，直到录音完成回调 setState
                    }
                    // 优先级 2：拖动结束 → 吸附到最近边缘，不触发点击
                    isDragging -> {
                        isDragging = false
                        snapToNearestEdge()
                    }
                    // 优先级 3：录音周期进行中（松手等待阶段）→ 忽略点击
                    isRecordingActive -> {
                        // 忽略
                    }
                    // 优先级 4：普通点击 → 立即响应（无双击等待延迟）
                    else -> {
                        if (suppressClickOnUp) {
                            // 面板已因"点击球"（面板外 WATCH_OUTSIDE_TOUCH）而收起，
                            // 本次点击的目的就是关闭面板 → 忽略单击，避免再次展开
                            suppressClickOnUp = false
                            isPanelExpanded = false
                        } else {
                            handleSingleClick()
                        }
                    }
                }
                cancelPressFeedback()
            }
            MotionEvent.ACTION_CANCEL -> {
                // 清理所有定时器
                handler.removeCallbacks(longPressRunnable)
                cancelPressFeedback()
                isDragging = false
                isLongPressTriggered = false
                suppressClickOnUp = false
            }
        }
        return true
    }

    // ============================================================
    // 手势处理方法
    // ============================================================

    /**
     * 长按触发（longPressRunnable 回调）。
     *
     * 条件：手指仍按住（未拖动、未松手）且达 holdDurationMs。
     * 动作：震动×2 → 球变红呼吸 → 开始 PTT 录音
     *
     * 覆盖逻辑：若当前为绿色态（READY_TO_PLAY），overwrite=true（覆盖最近语音包）；
     * 否则 overwrite=false（新增语音包）。
     */
    private fun triggerLongPress() {
        if (isDragging || isLongPressTriggered || isRecordingActive) return

        isLongPressTriggered = true
        isRecordingActive = true
        cancelPressFeedback()

        // 覆盖模式：绿色态长按 → 覆盖最近语音包
        val overwrite = (currentState == BallState.READY_TO_PLAY)

        // 震动×2 反馈
        vibrateDouble()

        // 球变红呼吸闪烁
        setState(BallState.RECORDING)

        // 开始 PTT 录音（委托给 Service 实现）
        interactionCallback?.onStartRecording(overwrite)
    }

    /**
     * 单击处理（ACTION_UP 立即触发，无双击等待延迟）。
     *
     * - 绿色态（READY_TO_PLAY，有新包未听）→ 播放最近语音包
     *   （录完 → 试听一步到位；播放开始后 Service 会把球切回紫色，
     *    播放中再单击即正常开面板停止播放）
     * - 其他态 → 切换面板展开/收起
     */
    private fun handleSingleClick() {
        if (currentState == BallState.READY_TO_PLAY) {
            interactionCallback?.onPlayLatest()
        } else if (isPanelExpanded) {
            isPanelExpanded = false
            interactionCallback?.onCollapsePanel()
        } else {
            isPanelExpanded = true
            interactionCallback?.onExpandPanel()
        }
    }

    // ============================================================
    // 长按进度反馈（进度弧 + 按住放大）
    // ============================================================

    /** 启动按住反馈动画：进度弧 0→1（时长=长按阈值），球轻微放大 */
    private fun startPressFeedback() {
        cancelPressFeedback()
        pressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = holdDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                pressProgress = t
                pressScale = 1f + (PRESS_SCALE_MAX - 1f) * t
                invalidate()
            }
            start()
        }
    }

    /** 取消按住反馈，弧与缩放复位 */
    private fun cancelPressFeedback() {
        pressAnimator?.cancel()
        pressAnimator = null
        if (pressProgress != 0f || pressScale != 1f) {
            pressProgress = 0f
            pressScale = 1f
            invalidate()
        }
    }

    // ============================================================
    // 拖动 / 边缘吸附 / 位置持久化
    // ============================================================

    /** y 方向 clamp 范围（顶部/底部各留 24dp，避免球被拖出屏幕外） */
    private fun minY(): Int = dpToPx(24f).toInt()
    private fun maxY(): Int =
        (resources.displayMetrics.heightPixels - ballSizePx - dpToPx(24f).toInt())
            .coerceAtLeast(minY())

    /** 拖动时更新 WindowManager 中 View 的位置（clamp 进屏幕） */
    private fun updatePosition(deltaX: Float, deltaY: Float) {
        val params = wmLayoutParams ?: return
        val wm = windowManager ?: return
        try {
            params.x = (params.x + deltaX.toInt())
                .coerceIn(0, (resources.displayMetrics.widthPixels - ballSizePx).coerceAtLeast(0))
            params.y = (params.y + deltaY.toInt()).coerceIn(minY(), maxY())
            isDockedNearEdge = false
            wm.updateViewLayout(this, params)
        } catch (e: Exception) {
            // View 已被移除或 WindowManager 不可用时容错
        }
    }

    /** 吸附动画引用（防止 GC 并支持取消） */
    private var snapAnimator: ValueAnimator? = null

    /**
     * 松手后吸附到最近的左右边缘（y 保持当前位置，弹性减速动画）。
     * 完成后持久化位置；IDLE 态贴边后自动半透明。
     */
    private fun snapToNearestEdge() {
        val params = wmLayoutParams ?: return
        val wm = windowManager ?: return
        val dm = resources.displayMetrics
        val margin = dpToPx(EDGE_MARGIN_DP).toInt()

        val centerX = params.x + ballSizePx / 2
        val targetX = if (centerX < dm.widthPixels / 2) {
            margin
        } else {
            (dm.widthPixels - ballSizePx - margin).coerceAtLeast(0)
        }
        val targetY = params.y.coerceIn(minY(), maxY())

        val startX = params.x
        val startY = params.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                try {
                    wm.updateViewLayout(this@FloatingBallView, params)
                } catch (_: Exception) {
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isDockedNearEdge = true
                    savePosition(params.x, params.y)
                    // IDLE 态贴边 → 半透明省视线（录音/绿球态保持全亮）
                    if (currentState == BallState.IDLE) dimToEdge()
                }
            })
            start()
        }
    }

    /** 贴边半透明（IDLE 态省视线；触摸即恢复） */
    private fun dimToEdge() {
        if (currentState != BallState.IDLE) return
        // 用户正按住球（或延迟期间刚开始交互）时不降透明度
        if (isPressed) return
        animate().alpha(EDGE_DIM_ALPHA).setDuration(200L).start()
    }

    /** 持久化球位置（吸附完成后调用，重启恢复） */
    private fun savePosition(x: Int, y: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BALL_X, x)
            .putInt(KEY_BALL_Y, y)
            .apply()
    }

    // ============================================================
    // 震动反馈
    // ============================================================

    /**
     * 震动两次：震 200ms / 停 150ms / 震 200ms，振幅满格。
     * 使用 VibrationEffect.createWaveform（API 26+）。
     */
    private fun vibrateDouble() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+：通过 VibratorManager 获取
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            // 旧 API：直接获取 Vibrator
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+：使用 VibrationEffect.createWaveform
            val timings = longArrayOf(0, 200, 150, 200) // 停0 / 震200 / 停150 / 震200
            val amplitudes = intArrayOf(0, 255, 0, 255)  // 对应振幅（0=不震，255=满格）
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) // -1 = 不重复
        } else {
            // API < 26：使用已废弃的 vibrate(long[], int)
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 200, 150, 200), -1)
        }
    }

    /** 拖动时更新位置的旧接口已并入 updatePosition（保留空实现兼容） */
    private fun releaseInternal() {
        stopBreath()
        snapAnimator?.cancel()
        pressAnimator?.cancel()
        handler.removeCallbacks(longPressRunnable)
    }

    /** 释放资源（Service onDestroy 时调用） */
    fun release() {
        releaseInternal()
        windowManager = null
        wmLayoutParams = null
    }
}
