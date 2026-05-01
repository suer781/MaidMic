// TransportType.kt — 传输类型枚举

package aoeck.dwyai.com.streaming

enum class TransportType {
    /** WiFi Direct / UDP — 低延迟，适合局域网 */
    WIFI_UDP,
    /** 蓝牙 RFCOMM/SPP — 无需WiFi，短距离稳定 */
    BLUETOOTH_RFCOMM
}
