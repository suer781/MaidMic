// ConnectionManager.kt — 双传输连接管理器
// ============================================================
// 支持两种传输方式：
//   WiFi UDP: 低延迟局域网传输，适合强WiFi环境
//   蓝牙 RFCOMM: 无需WiFi，蓝牙SPP串口协议传输
//
// 协议：
//   WiFi UDP: SOURCE监听 → RECEIVER发HELLO → ACK → 音频分片UDP包
//   蓝牙RFCOMM: SOURCE监听 → RECEIVER连接 → 音频流DataStream

package aoeck.dwyai.com.streaming

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import java.io.*
import java.net.*
import java.util.*

enum class ConnectionState {
    IDLE,
    WAITING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

data class PeerDevice(
    val name: String,
    val address: String,
    val transport: TransportType
)

class ConnectionManager(private val context: Context) {

    private val TAG = "ConnectionManager"
    private val PORT = 48106
    private val RFCOMM_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")  // SPP UUID
    private val BT_NAME = "MaidMic"

    // WiFi UDP magic bytes
    private val MAGIC_HELLO = byteArrayOf(0x4D, 0x44, 0x01)
    private val MAGIC_ACK = byteArrayOf(0x4D, 0x44, 0x02)
    private val MAGIC_AUDIO = byteArrayOf(0x4D, 0x44, 0x10)
    private val MAX_UDP_PACKET = 1472

    @Volatile
    var state: ConnectionState = ConnectionState.IDLE
        private set

    @Volatile
    var connectedDevice: PeerDevice? = null
        private set

    @Volatile
    var transportType: TransportType = TransportType.WIFI_UDP
        private set

    var onStateChange: ((ConnectionState) -> Unit)? = null

    // WiFi UDP 相关
    private var udpSocket: DatagramSocket? = null
    private var udpSendAddr: InetAddress? = null
    private var udpSendPort: Int = PORT

    // 蓝牙 RFCOMM 相关
    private var btServerSocket: BluetoothServerSocket? = null
    private var btSocket: BluetoothSocket? = null
    private var btStreamOut: DataOutputStream? = null
    private var btStreamIn: DataInputStream? = null
    private var btAdapter: BluetoothAdapter? = null

    // ========================================
    // 公开 API
    // ========================================

    /** 启动服务（SOURCE用） */
    fun startServer(type: TransportType = TransportType.WIFI_UDP): Boolean {
        transportType = type
        return when (type) {
            TransportType.WIFI_UDP -> startUdpServer()
            TransportType.BLUETOOTH_RFCOMM -> startBtServer()
        }
    }

    /** 等待对端连接（SOURCE用，阻塞） */
    fun waitForPeer(timeoutMs: Long = 30000): Boolean {
        return when (transportType) {
            TransportType.WIFI_UDP -> waitUdpPeer(timeoutMs)
            TransportType.BLUETOOTH_RFCOMM -> waitBtPeer(timeoutMs)
        }
    }

    /** 连接到SOURCE（RECEIVER用） */
    fun connectTo(hostOrAddr: String, type: TransportType = TransportType.WIFI_UDP): Boolean {
        transportType = type
        return when (type) {
            TransportType.WIFI_UDP -> connectUdp(hostOrAddr)
            TransportType.BLUETOOTH_RFCOMM -> connectBt(hostOrAddr)
        }
    }

    /** 发送音频数据 */
    fun sendAudio(data: ByteArray, size: Int): Boolean {
        return when (transportType) {
            TransportType.WIFI_UDP -> sendUdpAudio(data, size)
            TransportType.BLUETOOTH_RFCOMM -> sendBtAudio(data, size)
        }
    }

    /** 接收音频数据 */
    fun receiveAudio(buffer: ByteArray): Int {
        return when (transportType) {
            TransportType.WIFI_UDP -> receiveUdpAudio(buffer)
            TransportType.BLUETOOTH_RFCOMM -> receiveBtAudio(buffer)
        }
    }

    /** 断开连接 */
    fun disconnect() {
        closeAll()
        state = ConnectionState.DISCONNECTED
        onStateChange?.invoke(state)
        Log.i(TAG, "Disconnected")
    }

    /** 本机IP（WiFi用） */
    fun getLocalIpAddress(): String {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            String.format("%d.%d.%d.%d", ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
        } catch (_: Exception) { "127.0.0.1" }
    }

    /** 本机蓝牙名称 */
    fun getLocalBtName(): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.name ?: "Unknown"
        } catch (_: Exception) { "Unknown" }
    }

    /** 扫描附近蓝牙设备（回调模式） */
    fun startBtDiscovery(callback: (BluetoothDevice) -> Unit): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (adapter.isDiscovering) adapter.cancelDiscovery()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && device.name != null) {
                            callback(device)
                        }
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            adapter.startDiscovery()
            true
        } catch (_: Exception) { false }
    }

    /** 已配对蓝牙设备列表 */
    fun getBondedBtDevices(): List<BluetoothDevice> {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ========================================
    // WiFi UDP 实现
    // ========================================

    private fun startUdpServer(): Boolean {
        return try {
            udpSocket = DatagramSocket(PORT)
            udpSocket?.soTimeout = 100
            state = ConnectionState.WAITING
            onStateChange?.invoke(state)
            Log.i(TAG, "UDP server on port $PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP server failed", e)
            error()
            false
        }
    }

    private fun waitUdpPeer(timeoutMs: Long): Boolean {
        val s = udpSocket ?: return false
        val buf = ByteArray(MAX_UDP_PACKET)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                if (pkt.length >= 3 && matchMagic(buf, MAGIC_HELLO)) {
                    udpSendAddr = pkt.address; udpSendPort = pkt.port
                    s.send(DatagramPacket(MAGIC_ACK, MAGIC_ACK.size, udpSendAddr, udpSendPort))
                    connectedDevice = PeerDevice(udpSendAddr!!.hostAddress ?: "", udpSendAddr!!.hostAddress ?: "", TransportType.WIFI_UDP)
                    connected()
                    return true
                }
            } catch (_: SocketTimeoutException) { if (state != ConnectionState.WAITING) return false }
        }
        error()
        return false
    }

    private fun connectUdp(host: String): Boolean {
        return try {
            state = ConnectionState.CONNECTING; onStateChange?.invoke(state)
            val addr = InetAddress.getByName(host)
            udpSocket = DatagramSocket()
            udpSocket?.soTimeout = 100
            udpSendAddr = addr; udpSendPort = PORT
            udpSocket?.send(DatagramPacket(MAGIC_HELLO, MAGIC_HELLO.size, addr, PORT))
            val buf = ByteArray(MAX_UDP_PACKET)
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt)
                    if (pkt.length >= 3 && matchMagic(buf, MAGIC_ACK)) {
                        connectedDevice = PeerDevice(host, host, TransportType.WIFI_UDP)
                        connected()
                        return true
                    }
                } catch (_: SocketTimeoutException) {}
            }
            error(); false
        } catch (e: Exception) { Log.e(TAG, "UDP connect failed", e); error(); false }
    }

    private fun sendUdpAudio(data: ByteArray, size: Int): Boolean {
        val addr = udpSendAddr ?: return false; val s = udpSocket ?: return false
        return try {
            val totalSize = 3 + size
            val pktData = ByteArray(totalSize)
            pktData[0] = MAGIC_AUDIO[0]; pktData[1] = MAGIC_AUDIO[1]; pktData[2] = MAGIC_AUDIO[2]
            System.arraycopy(data, 0, pktData, 3, size)
            s.send(DatagramPacket(pktData, totalSize, addr, udpSendPort))
            true
        } catch (e: Exception) { Log.w(TAG, "UDP send fail", e); disconnect(); false }
    }

    private fun receiveUdpAudio(buffer: ByteArray): Int {
        val s = udpSocket ?: return -1; val buf = ByteArray(MAX_UDP_PACKET)
        return try {
            val pkt = DatagramPacket(buf, buf.size); s.receive(pkt)
            if (pkt.length >= 3 && matchMagic(buf, MAGIC_AUDIO)) {
                val len = pkt.length - 3
                if (len > buffer.size) return -1
                System.arraycopy(buf, 3, buffer, 0, len); len
            } else -1
        } catch (_: SocketTimeoutException) { -1 }
        catch (e: Exception) { Log.w(TAG, "UDP recv fail", e); disconnect(); -1 }
    }

    // ========================================
    // 蓝牙 RFCOMM 实现
    // ========================================

    private fun startBtServer(): Boolean {
        return try {
            btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (btAdapter == null) { Log.e(TAG, "No Bluetooth"); error(); return false }
            btServerSocket = btAdapter?.listenUsingRfcommWithServiceRecord(BT_NAME, RFCOMM_UUID)
            state = ConnectionState.WAITING
            onStateChange?.invoke(state)
            Log.i(TAG, "Bluetooth RFCOMM listening")
            true
        } catch (e: Exception) { Log.e(TAG, "BT server failed", e); error(); false }
    }

    private fun waitBtPeer(timeoutMs: Long): Boolean {
        return try {
            btServerSocket?.let { server ->
                btSocket = server.accept()
                btSocket?.let { sock ->
                    btStreamOut = DataOutputStream(BufferedOutputStream(sock.outputStream))
                    btStreamIn = DataInputStream(BufferedInputStream(sock.inputStream))
                    val name = sock.remoteDevice.name ?: sock.remoteDevice.address
                    connectedDevice = PeerDevice(name, sock.remoteDevice.address, TransportType.BLUETOOTH_RFCOMM)
                    connected()
                    Log.i(TAG, "BT connected: $name")
                    true
                } ?: run { error(); false }
            } ?: run { error(); false }
        } catch (e: Exception) { Log.e(TAG, "BT accept failed", e); error(); false }
    }

    private fun connectBt(address: String): Boolean {
        return try {
            state = ConnectionState.CONNECTING; onStateChange?.invoke(state)
            btAdapter = BluetoothAdapter.getDefaultAdapter()

            // 先查已配对设备
            var device: BluetoothDevice? = btAdapter?.getRemoteDevice(address)

            // 支持传入蓝牙名称查找
            if (device == null) {
                device = btAdapter?.bondedDevices?.find { it.name == address || it.address == address }
            }

            if (device == null) { error(); return false }

            btSocket = device.createRfcommSocketToServiceRecord(RFCOMM_UUID)
            btAdapter?.cancelDiscovery()
            btSocket?.connect()
            btStreamOut = DataOutputStream(BufferedOutputStream(btSocket!!.outputStream))
            btStreamIn = DataInputStream(BufferedInputStream(btSocket!!.inputStream))
            connectedDevice = PeerDevice(device.name ?: address, device.address, TransportType.BLUETOOTH_RFCOMM)
            connected()
            Log.i(TAG, "BT connected to ${device.name}")
            true
        } catch (e: Exception) { Log.e(TAG, "BT connect failed", e); error(); false }
    }

    private fun sendBtAudio(data: ByteArray, size: Int): Boolean {
        return try {
            btStreamOut?.writeInt(size)
            btStreamOut?.write(data, 0, size)
            btStreamOut?.flush()
            true
        } catch (e: Exception) { Log.w(TAG, "BT send fail", e); disconnect(); false }
    }

    private fun receiveBtAudio(buffer: ByteArray): Int {
        return try {
            val size = btStreamIn?.readInt() ?: return -1
            if (size > buffer.size) return -1
            var total = 0
            while (total < size) {
                val r = btStreamIn?.read(buffer, total, size - total) ?: return -1
                if (r == -1) return -1
                total += r
            }
            size
        } catch (e: Exception) { Log.w(TAG, "BT recv fail", e); disconnect(); -1 }
    }

    // ========================================
    // 工具
    // ========================================

    private fun matchMagic(buf: ByteArray, magic: ByteArray): Boolean {
        return buf[0] == magic[0] && buf[1] == magic[1] && buf[2] == magic[2]
    }

    private fun connected() {
        state = ConnectionState.CONNECTED
        onStateChange?.invoke(state)
    }

    private fun error() {
        state = ConnectionState.ERROR
        onStateChange?.invoke(state)
    }

    private fun closeAll() {
        try { btStreamOut?.close() } catch (_: Exception) {}
        try { btStreamIn?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        try { btServerSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        btStreamOut = null; btStreamIn = null; btSocket = null
        btServerSocket = null; udpSocket = null
        udpSendAddr = null; connectedDevice = null
    }
}
