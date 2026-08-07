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
 * 悬浮球交互回调。
 *
 * 手势 → 回调映射：
 *   - 单击        → [onExpandPanel] / [onCollapsePanel]（面板展开/收起切换）
 *   - 双击        → [onPlayLatest]（仅绿色态有效，播放最近语音包）
 *   - 长按        → [onStartRecording]（overwrite=false，新增语音包）
 *   - 绿色态长按  → [onStartRecording]（overwrite=true，覆盖最近语音包）
 *   - 长按松手    → [onStopRecording]（delayMs=500，松手后延迟 0.5s 停止）
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
