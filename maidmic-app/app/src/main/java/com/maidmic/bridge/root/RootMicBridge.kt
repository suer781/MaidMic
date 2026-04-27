// maidmic-app/app/src/main/java/com/maidmic/bridge/root/RootMicBridge.kt
// MaidMic Root 虚拟麦克风桥接 — 方案 A
// ============================================================
// 通过 root 权限直接操作 AudioFlinger，在系统音频 HAL 层注册
// 一个虚拟的音频输入设备。这是最接近"物理声卡"的方案。
//
// 原理：
// 1. Android 音频策略由 /vendor/etc/audio_policy_configuration.xml
//    和 /system/etc/audio_policy_configuration.xml 决定
// 2. 我们 root 后修改这些配置文件，添加一个虚拟音频模块
// 3. 使用 AudioFlinger 的 AudioPatch/DeviceDescriptor API
//    注入经过 DSP 处理后的音频流
// 4. 其他 App（游戏、QQ、微信）看到的这个虚拟设备就是一个"麦克风"
//
// 需要：已 root 设备 + Superuser 权限
// 兼容性：Android 10+（不同厂商的 audio HAL 实现有差异）

package com.maidmic.bridge.root

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.*

/**
 * Root 虚拟麦克风桥接
 * 
 * 通过 root shell 修改系统音频策略并控制 AudioFlinger。
 * 这是三方案中延迟最低的一个（< 15ms 可行），
 * 但需要 root 权限，对设备兼容性要求高。
 */
class RootMicBridge(private val context: Context) {

    companion object {
        private const val TAG = "RootMicBridge"
        
        // 虚拟音频设备名称（会在其他 App 的麦克风列表中显示）
        // Virtual audio device name (appears in other apps' mic list)
        private const val VIRTUAL_DEVICE_NAME = "MaidMic Virtual Input"
        
        // 备份路径（修改配置文件前备份）
        // Backup path (backup before modifying config files)
        private const val BACKUP_DIR = "/data/local/tmp/maidmic_backup/"
        
        // 音频策略配置文件路径（不同厂商位置不同）
        // Audio policy config file paths (vendor-specific)
        private val AUDIO_POLICY_PATHS = arrayOf(
            "/vendor/etc/audio_policy_configuration.xml",
            "/system/etc/audio_policy_configuration.xml",
            "/system/vendor/etc/audio_policy_configuration.xml",
            "/odm/etc/audio_policy_configuration.xml"
        )
    }

    private var isRunning = false
    private var hasRoot = false
    private var recoveryScript: String = ""  // 恢复脚本（退出时还原修改）

    // ============================================================
    // root 权限检查
    // Root permission check
    // ============================================================

    /**
     * 检查是否具有 root 权限
     * Check for root permission
     */
    fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c 'id'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            hasRoot = output?.contains("uid=0") == true
            hasRoot
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed: ${e.message}")
            hasRoot = false
            false
        }
    }

    // ============================================================
    // 获取设备兼容性信息
    // Get device compatibility info
    // ============================================================

    /**
     * 检查设备是否支持方案 A
     * Check if device supports Solution A
     * 
     * 不同厂商的音频 HAL 实现差异很大。
     * 高通平台兼容性最好，MTK/Exynos 需要额外适配。
     */
    fun checkCompatibility(): CompatibilityInfo {
        val info = CompatibilityInfo()
        
        // 检查 root
        info.hasRoot = checkRoot()
        
        // 检查音频策略文件是否存在
        // Check if audio policy config exists
        for (path in AUDIO_POLICY_PATHS) {
            val testCmd = "su -c 'test -f $path && echo YES || echo NO'"
            val proc = Runtime.getRuntime().exec(testCmd)
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            if (reader.readLine() == "YES") {
                info.audioPolicyPath = path
                info.policyEditable = true
                break
            }
        }
        
        // 检查 su 版本
        // Check su version
        info.suAvailable = try {
            Runtime.getRuntime().exec("which su").inputStream.use {
                BufferedReader(InputStreamReader(it)).readLine() != null
            }
        } catch (_: Exception) { false }
        
        return info
    }

    data class CompatibilityInfo(
        var hasRoot: Boolean = false,
        var audioPolicyPath: String? = null,
        var policyEditable: Boolean = false,
        var suAvailable: Boolean = false
    ) {
        val isFullyCompatible get() = hasRoot && audioPolicyPath != null
    }

    // ============================================================
    // 启动虚拟麦克风
    // Start virtual microphone
    // ============================================================

    /**
     * 启动 root 模式虚拟麦克风
     * 
     * 流程：
     * 1. 备份原始 audio_policy_configuration.xml
     * 2. 注入虚拟音频设备配置
     * 3. 重启 audioserver 使配置生效
     * 4. 开始 DSP 处理 -> 写入虚拟设备
     */
    fun start() {
        if (!hasRoot) {
            Log.e(TAG, "No root permission")
            return
        }
        
        if (isRunning) return
        
        try {
            // 步骤 1: 备份原始配置
            // Step 1: Backup original config
            backupConfig()
            
            // 步骤 2: 注入虚拟设备
            // Step 2: Inject virtual device
            injectVirtualDevice()
            
            // 步骤 3: 重启音频服务
            // Step 3: Restart audio service
            restartAudioServer()
            
            isRunning = true
            Log.i(TAG, "Root virtual mic started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root mic", e)
            // 启动失败时自动恢复
            // Auto-recover on failure
            restore()
        }
    }

    /**
     * 注入虚拟音频设备到系统配置
     * 
     * 修改 audio_policy_configuration.xml，添加一个虚拟的
     * 音频输入模块（类型为 AUDIO_DEVICE_IN_REMOTE_SUBMIX）。
     * 
     * 这个设备类型是 Android 原生支持的，常用于屏幕录制和 VoIP，
     * 我们把它改用作虚拟麦克风。
     */
    private fun injectVirtualDevice() {
        val configPath = checkCompatibility().audioPolicyPath ?: return
        
        // 构造注入的 XML 片段
        // Construct the XML fragment to inject
        // 注意：具体 XML 格式因 Android 版本和厂商而异
        // 这里给的是 AOSP 标准的格式
        val virtualDeviceXml = """
            <devicePort tagName="MaidMic Virtual Input" type="AUDIO_DEVICE_IN_REMOTE_SUBMIX" role="source">
                <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
                         samplingRates="48000"
                         channelMasks="AUDIO_CHANNEL_IN_MONO,AUDIO_CHANNEL_IN_STEREO"/>
            </devicePort>
            <route type="mix" sink="MaidMic Virtual Input"
                   sources="MaidMic Virtual Input"/>
        """.trimIndent()
        
        // 使用 sed 注入配置
        // 查找 </audioPolicyConfiguration> 并在之前插入
        val escapedXml = virtualDeviceXml
            .replace("'", "'\\''")
            .replace("\n", "\\n")
        
        val cmd = buildString {
            append("su -c '")
            append("cp $configPath ${configPath}.maidmic_tmp && ")
            append("sed -i 's|</audioPolicyConfiguration>|${escapedXml}\\n</audioPolicyConfiguration>|' ${configPath}.maidmic_tmp && ")
            append("cp ${configPath}.maidmic_tmp $configPath && ")
            append("chmod 644 $configPath && ")
            append("rm ${configPath}.maidmic_tmp")
            append("'")
        }
        
        val proc = Runtime.getRuntime().exec(cmd)
        val exitCode = proc.waitFor()
        
        if (exitCode != 0) {
            throw IOException("Failed to inject virtual device, exit code: $exitCode")
        }
        
        Log.i(TAG, "Virtual device injected into audio policy")
    }

    /**
     * 重启 audioserver
     * 
     * Android 音频服务重启后，会重新加载配置，
     * 从而识别我们新添加的虚拟设备。
     */
    private fun restartAudioServer() {
        val cmds = arrayOf(
            "su -c 'setprop ctl.restart audioserver'",
            "su -c 'killall -9 android.hardware.audio.service 2>/dev/null || true'"
        )
        
        for (cmd in cmds) {
            try {
                Runtime.getRuntime().exec(cmd).waitFor()
            } catch (_: Exception) {}
        }
        
        // 等待服务重启
        // Wait for service restart
        Thread.sleep(500)
    }

    // ============================================================
    // 停止 / 恢复
    // Stop / Restore
    // ============================================================

    /**
     * 停止虚拟麦克风并恢复原始配置
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        restore()
    }

    /**
     * 恢复原始音频配置
     * Restore original audio configuration
     */
    private fun restore() {
        try {
            // 从备份恢复配置文件
            // Restore config files from backup
            val cmd = "su -c 'cp ${BACKUP_DIR}audio_policy_configuration.xml /vendor/etc/audio_policy_configuration.xml && setprop ctl.restart audioserver'"
            Runtime.getRuntime().exec(cmd).waitFor()
            Log.i(TAG, "Audio config restored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio config", e)
        }
    }

    /**
     * 备份原始系统配置
     * Backup original system configuration
     */
    private fun backupConfig() {
        val cmd = buildString {
            append("su -c '")
            append("mkdir -p $BACKUP_DIR && ")
            for (path in AUDIO_POLICY_PATHS) {
                append("test -f $path && cp $path ${BACKUP_DIR}audio_policy_configuration.xml && ")
            }
            append("echo BACKUP_OK'")
        }
        
        val proc = Runtime.getRuntime().exec(cmd)
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val result = reader.readLine()
        
        if (result != "BACKUP_OK") {
            Log.w(TAG, "Backup may have failed: $result")
        } else {
            Log.i(TAG, "Config backed up to $BACKUP_DIR")
        }
    }

    // ============================================================
    // 音频处理循环（JNI → Echio 引擎）
    // Audio processing loop (JNI → Echio engine)
    // ============================================================
    // 用 AudioRecord 捕获系统麦克风 -> DSP 处理 -> 写入虚拟设备
    // 虚拟设备实际上是一个文件描述符，通过 AudioFlinger 的
    // AudioPatch 机制注入到系统音频管道

    private fun audioProcessingLoop() {
        // 配置 AudioRecord 捕获系统输入
        // Configure AudioRecord to capture system input
        val bufferSize = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, // 从真实麦克风捕获
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        
        recorder.startRecording()
        
        // 通过 root shell 打开虚拟设备的文件描述符
        // Open virtual device file descriptor via root shell
        val virtualDeviceFd: Int? = null  // TODO: 通过 AudioFlinger API 获取
        
        val buffer = ByteArray(bufferSize)
        val outputBuffer = ByteArray(bufferSize)
        
        while (isRunning) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0 && virtualDeviceFd != null) {
                // JNI 调用 Echio 引擎处理
                // JNI call to Echio engine for DSP processing
                nativeProcess(rootEnginePtr, buffer, outputBuffer, bytesRead)
                
                // 写入虚拟设备
                // Write to virtual device
                nativeWriteToVirtualDevice(virtualDeviceFd, outputBuffer, bytesRead)
            }
        }
        
        recorder.stop()
        recorder.release()
    }

    private var rootEnginePtr: Long = 0

    // ============================================================
    // Native 方法
    // ============================================================

    private external fun nativeProcess(enginePtr: Long, input: ByteArray, output: ByteArray, size: Int)
    private external fun nativeWriteToVirtualDevice(fd: Int, data: ByteArray, size: Int): Int
}
