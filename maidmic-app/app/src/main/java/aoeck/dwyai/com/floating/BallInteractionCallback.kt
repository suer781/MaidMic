// BallInteractionCallback.kt — 悬浮球交互回调接口
// ============================================================
// Task 9.5: 定义手势触发时的回调契约。
//
// 实际的录音 / 播放 / 面板逻辑分别由 Task 5（AudioRecord + pipeline）
// 和 Task 10（展开面板）实现，本接口仅定义"手势 → 动作"的委托边界。
//
// FloatingBallView 在识别到对应手势后调用相应方法，
// FloatingBallService 注入具体实现（Task 5 接线时替换为真实逻辑）。

package aoeck.dwyai.com.floating

/**
 * 悬浮球交互回调（交互 v2）。
 *
 * 手势 → 回调映射：
 *   - 单击（立即响应，无延迟）
 *       * 紫色态（IDLE）    → [onExpandPanel] / [onCollapsePanel]（面板切换）
 *       * 绿色态（有新包）  → [onPlayLatest]（播放最近语音包，贴合状态语义）
 *   - 长按（默认 600ms，按住有进度弧反馈）
 *       * 紫色态           → [onStartRecording]（overwrite=false，新增语音包）
 *       * 绿色态           → [onStartRecording]（overwrite=true，覆盖重录）
 *   - 长按松手             → [onStopRecording]（delayMs=500，松手后继续录 0.5s）
 *   - 拖动                → 移动位置（不经回调，View 内部处理；松手吸附边缘）
 *
 * 双击手势已移除：它是单击 300ms 延迟的根源，播放由绿色态单击承接。
 */
interface BallInteractionCallback {

    /** 展开面板（Task 10 实现） */
    fun onExpandPanel()

    /** 收起面板 */
    fun onCollapsePanel()

    /**
     * 开始 PTT 录音。
     * @param overwrite true 表示覆盖最近一条语音包（绿色态长按），
     *                  false 表示新增语音包（默认态长按）
     */
    fun onStartRecording(overwrite: Boolean)

    /**
     * 停止录音并处理。
     * @param delayMs 松手后延迟停止的毫秒数（500 = 松手后继续录 0.5s 再停止）
     */
    fun onStopRecording(delayMs: Long)

    /** 播放最近一条语音包 */
    fun onPlayLatest()
}
