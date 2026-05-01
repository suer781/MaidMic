// QRCodeUtil.kt — 二维码生成工具
// ============================================================
// 用 ZXing 生成 MaidMic 连接二维码
// 格式: maidmic://connect?transport=wifi|bt&addr=X&name=Y

package aoeck.dwyai.com.streaming

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QRCodeUtil {

    private const val QR_SIZE = 512

    /** 生成 WiFi 连接二维码 */
    fun generateWifiQr(ip: String, port: Int = 48106, name: String = "MaidMic"): Bitmap? {
        val data = "maidmic://connect?transport=wifi&addr=$ip:$port&name=$name"
        return generate(data)
    }

    /** 生成蓝牙连接二维码 */
    fun generateBtQr(btAddress: String, name: String = "MaidMic"): Bitmap? {
        val data = "maidmic://connect?transport=bt&addr=$btAddress&name=$name"
        return generate(data)
    }

    /** 解析二维码数据，返回连接参数或 null */
    fun parseQrData(data: String): ConnectInfo? {
        if (!data.startsWith("maidmic://connect?")) return null
        val params = data.removePrefix("maidmic://connect?").split("&").associate {
            val kv = it.split("=", limit = 2)
            kv[0] to kv.getOrElse(1) { "" }
        }
        val transport = params["transport"] ?: return null
        val addr = params["addr"] ?: return null
        val name = params["name"] ?: "MaidMic"
        return when (transport) {
            "wifi" -> {
                val parts = addr.split(":")
                ConnectInfo(TransportType.WIFI_UDP, parts[0], parts.getOrElse(1) { "48106" }.toIntOrNull() ?: 48106, name)
            }
            "bt" -> ConnectInfo(TransportType.BLUETOOTH_RFCOMM, addr, 0, name)
            else -> null
        }
    }

    data class ConnectInfo(
        val transport: TransportType,
        val address: String,
        val port: Int,
        val deviceName: String
    )

    private fun generate(data: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
            val bmp = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.RGB_565)
            for (x in 0 until QR_SIZE)
                for (y in 0 until QR_SIZE)
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
