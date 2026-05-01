// MicMode.kt — 双设备模式枚举

package aoeck.dwyai.com.streaming

enum class MicMode {
    /** 本地模式：使用现有桥接（Shizuku/Root/无障碍） */
    LOCAL,
    /** 麦克风端：本地采集→DSP→发送到接收端 */
    SOURCE,
    /** 接收端：从麦克风端接收音频→注入系统 */
    RECEIVER
}
