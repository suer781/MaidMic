// maidmic-app/app/src/main/java/com/maidmic/ui/plugins/PluginMarketPage.kt
// MaidMic 插件市场
// ============================================================
// 插件市场页面：浏览、搜索、安装插件。
// 数据源来自 GitHub index.json（P2P 模式走 DHT 发现）。
//
// 页面结构：
//   ┌─────────────────────────────────────────┐
//   │  🔍 搜索插件...                          │
//   ├─────────────────────────────────────────┤
//   │  [全部] [变声] [混响] [均衡] [工具] [免费] │  ← 分类标签
//   ├─────────────────────────────────────────┤
//   │  ┌─────────────────────────────────┐    │
//   │  │ 🔊 萝莉变声器 Pro     v2.1      │    │
//   │  │ 一键切换到萝莉音色              │    │
//   │  │ 🟢 沙箱权限 · 免费 · 12.3MB   │    │
//   │  │                     [安装]     │    │
//   │  └─────────────────────────────────┘    │
//   │  ┌─────────────────────────────────┐    │
//   │  │ 🔥 电音狂魔             v1.0    │    │
//   │  │ 魔性电音效果器                  │    │
//   │  │ 🟡 签名权限 · ¥6 · 4.1MB      │    │
//   │  │                     [购买安装]  │    │
//   │  └─────────────────────────────────┘    │
//   └─────────────────────────────────────────┘

package com.maidmic.ui.plugins

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 数据模型
// Data models
// ============================================================

/** 插件来源 */
enum class PluginSource {
    GITHUB_INDEX,    // 从 GitHub index.json 获取
    P2P_DISCOVERED,  // 通过 P2P/DHT 网络发现
    LOCAL_FILE,      // 从本地文件安装
}

/** 插件状态 */
enum class PluginStatus {
    NOT_INSTALLED,   // 未安装
    DOWNLOADING,     // 下载中
    INSTALLED,       // 已安装
    UPDATE_AVAILABLE, // 有更新
}

/** 插件市场条目（UI 用） */
data class PluginMarketItem(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val permissionLevel: Int,      // 0=🟢沙箱 1=🟡签名 2=🟠原生 3=🔴高危
    val isFree: Boolean,
    val price: String,             // 价格（免费或金额）
    val fileSize: String,          // 可读的文件大小
    val tags: List<String>,        // 标签
    val rating: Float,             // 评分 0-5
    val downloadCount: Int,        // 下载次数
    val source: PluginSource,      // 来源
    val status: PluginStatus,      // 安装状态
    val installedVersion: String?, // 已安装版本（用于检测更新）
)

/** 权限等级对应的颜色和标签 */
data class PermissionBadge(
    val label: String,
    val color: Color,
    val description: String
)

fun getPermissionBadge(level: Int): PermissionBadge = when (level) {
    0 -> PermissionBadge("沙箱", Color(0xFF4CAF50), "仅访问音频 API，安全可靠")
    1 -> PermissionBadge("签名", Color(0xFF2196F3), "可网络访问，需开发者签名")
    2 -> PermissionBadge("原生", Color(0xFFFF9800), "可加载 .so，风险自担")
    3 -> PermissionBadge("高危", Color(0xFFF44336), "可执行 Shell 命令，慎重安装")
    else -> PermissionBadge("未知", Color.Gray, "")
}

// ============================================================
// 模拟数据（开发阶段用）
// Mock data (for development phase)
// ============================================================
// TODO: 替换为从 GitHub index.json 或 P2P DHT 获取的真实数据

val MOCK_PLUGINS = listOf(
    PluginMarketItem(
        id = "com.maidmic.plugin.loli",
        name = "萝莉变声器 Pro",
        description = "一键切换到萝莉音色。基于 PSOLA 变调 + 共振峰偏移，支持实时微调。",
        author = "MaidMic Team",
        version = "2.1.0",
        permissionLevel = 0,
        isFree = true,
        price = "免费",
        fileSize = "12.3 MB",
        tags = listOf("变声", "萝莉", "热门"),
        rating = 4.8f,
        downloadCount = 15230,
        source = PluginSource.GITHUB_INDEX,
        status = PluginStatus.NOT_INSTALLED,
        installedVersion = null
    ),
    PluginMarketItem(
        id = "com.maidmic.plugin.electro",
        name = "电音狂魔",
        description = "魔性电音效果器。实时音高量化 + 混响，直播神器。",
        author = "AudioDev",
        version = "1.0.0",
        permissionLevel = 1,
        isFree = false,
        price = "¥6.00",
        fileSize = "4.1 MB",
        tags = listOf("变声", "电音", "直播"),
        rating = 4.5f,
        downloadCount = 3821,
        source = PluginSource.GITHUB_INDEX,
        status = PluginStatus.INSTALLED,
        installedVersion = "1.0.0"
    ),
    PluginMarketItem(
        id = "com.maidmic.plugin.reverb",
        name = "大厅混响",
        description = "模拟大厅、教堂、浴室等多种空间混响效果。可调房间大小、衰减时间。",
        author = "MaidMic Team",
        version = "1.3.0",
        permissionLevel = 0,
        isFree = true,
        price = "免费",
        fileSize = "2.8 MB",
        tags = listOf("混响", "空间", "热门"),
        rating = 4.3f,
        downloadCount = 8920,
        source = PluginSource.GITHUB_INDEX,
        status = PluginStatus.NOT_INSTALLED,
        installedVersion = null
    ),
    PluginMarketItem(
        id = "com.maidmic.plugin.eq10",
        name = "十段均衡器",
        description = "专业级 10 段图形均衡器。可拖拽曲线调整频率响应。",
        author = "CommunityUser",
        version = "0.9.0",
        permissionLevel = 0,
        isFree = true,
        price = "免费",
        fileSize = "0.5 MB",
        tags = listOf("均衡", "工具"),
        rating = 3.9f,
        downloadCount = 2340,
        source = PluginSource.GITHUB_INDEX,
        status = PluginStatus.UPDATE_AVAILABLE,
        installedVersion = "0.8.0"
    ),
    PluginMarketItem(
        id = "com.maidmic.plugin.deepvoice",
        name = "大叔声线",
        description = "让你的声音瞬间变成沉稳大叔。共振峰下移 + 低频增强。",
        author = "VoiceMagic",
        version = "2.0.0",
        permissionLevel = 2,
        isFree = false,
        price = "¥12.00",
        fileSize = "8.6 MB",
        tags = listOf("变声", "大叔", "热门"),
        rating = 4.6f,
        downloadCount = 6720,
        source = PluginSource.GITHUB_INDEX,
        status = PluginStatus.NOT_INSTALLED,
        installedVersion = null
    ),
    PluginMarketItem(
        id = "com.maidmic.plugin.distortion",
        name = "失真吉他",
        description = "吉他失真效果器。过载、法兹、金属三种失真模式。需要原生权限加载 .so。",
        author = "GuitarFX",
        version = "1.0.0",
        permissionLevel = 2,
        isFree = true,
        price = "免费",
        fileSize = "3.2 MB",
        tags = listOf("失真", "吉他", "原生"),
        rating = 4.1f,
        downloadCount = 1230,
        source = PluginSource.GITHUB_INDEX,
        status = PluginStatus.NOT_INSTALLED,
        installedVersion = null
    ),
    PluginMarketItem(
        id = "com.maidmic.plugin.highrisk",
        name = "系统音频工具",
        description = "底层音频调试工具。可以加载自定义 ALSA 配置。高危权限！",
        author = "UnknownDev",
        version = "0.1.0",
        permissionLevel = 3,
        isFree = true,
        price = "免费",
        fileSize = "0.2 MB",
        tags = listOf("工具", "调试"),
        rating = 2.5f,
        downloadCount = 430,
        source = PluginSource.P2P_DISCOVERED,
        status = PluginStatus.NOT_INSTALLED,
        installedVersion = null
    ),
)

val ALL_TAGS = listOf("全部", "变声", "混响", "均衡", "失真", "工具", "免费", "热门")

// ============================================================
// 插件市场主页面
// Plugin market main page
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketPage(
    isUgcEnabled: Boolean,          // 是否启用 UGC 插件（从开发者选项读取）
    onInstall: (PluginMarketItem) -> Unit,  // 安装回调
    onOpenDeveloperSettings: () -> Unit      // 跳转到开发者选项
) {
    // 搜索关键词
    var searchQuery by remember { mutableStateOf("") }
    // 选中的分类标签
    var selectedTag by remember { mutableStateOf("全部") }
    // 选中的插件详情（点击查看详情时使用）
    var selectedPlugin by remember { mutableStateOf<PluginMarketItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ============================================================
        // 顶部：UGC 功能提醒（如果禁用）
        // Top: UGC feature reminder (if disabled)
        // ============================================================
        if (!isUgcEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "UGC 插件功能已禁用。请在开发者选项中启用。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenDeveloperSettings) {
                        Text("去启用", fontSize = 13.sp)
                    }
                }
            }
        }

        // ============================================================
        // 搜索栏
        // Search bar
        // ============================================================
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("🔍 搜索插件...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp)
        )

        // ============================================================
        // 分类标签
        // Category tags
        // ============================================================
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ALL_TAGS) { tag ->
                FilterChip(
                    selected = selectedTag == tag,
                    onClick = { selectedTag = tag },
                    label = { Text(tag) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ============================================================
        // 插件列表
        // Plugin list
        // ============================================================
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 根据搜索词和标签筛选
            // Filter by search query and tag
            val filteredPlugins = MOCK_PLUGINS.filter { plugin ->
                val matchesSearch = searchQuery.isEmpty() ||
                    plugin.name.contains(searchQuery, ignoreCase = true) ||
                    plugin.description.contains(searchQuery, ignoreCase = true) ||
                    plugin.author.contains(searchQuery, ignoreCase = true)
                
                val matchesTag = selectedTag == "全部" ||
                    plugin.tags.contains(selectedTag) ||
                    (selectedTag == "免费" && plugin.isFree) ||
                    (selectedTag == "热门" && plugin.rating >= 4.5)
                
                matchesSearch && matchesTag
            }

            items(filteredPlugins) { plugin ->
                PluginCard(
                    plugin = plugin,
                    onClick = { selectedPlugin = plugin },
                    onInstall = { onInstall(plugin) }
                )
            }
        }
    }
    
    // ============================================================
    // 插件详情弹窗
    // Plugin detail dialog
    // ============================================================
    selectedPlugin?.let { plugin ->
        PluginDetailDialog(
            plugin = plugin,
            isUgcEnabled = isUgcEnabled,
            onDismiss = { selectedPlugin = null },
            onInstall = { onInstall(plugin); selectedPlugin = null }
        )
    }
}

// ============================================================
// 插件卡片
// Plugin card
// ============================================================

@Composable
fun PluginCard(
    plugin: PluginMarketItem,
    onClick: () -> Unit,
    onInstall: () -> Unit
) {
    val badge = getPermissionBadge(plugin.permissionLevel)
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：插件图标（先用 emoji 占位）
            // Left: plugin icon (emoji placeholder)
            Surface(
                color = badge.color.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            plugin.tags.contains("变声") -> "🎤"
                            plugin.tags.contains("混响") -> "🌊"
                            plugin.tags.contains("均衡") -> "🎛"
                            plugin.tags.contains("失真") -> "🔥"
                            plugin.tags.contains("工具") -> "🔧"
                            else -> "📦"
                        },
                        fontSize = 24.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 中间：插件信息
            // Center: plugin info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "v${plugin.version}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    plugin.description,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // 标签行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 权限等级徽章
                    Surface(
                        color = badge.color.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "🟢 ${badge.label}",
                            fontSize = 11.sp,
                            color = badge.color,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        if (plugin.isFree) "免费" else plugin.price,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        plugin.fileSize,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // 评分
                    Text(
                        "⭐ ${String.format("%.1f", plugin.rating)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 右侧：安装按钮
            // Right: install button
            Spacer(modifier = Modifier.width(8.dp))
            
            when (plugin.status) {
                PluginStatus.INSTALLED -> {
                    Text(
                        "已安装",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
                PluginStatus.UPDATE_AVAILABLE -> {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("更新", fontSize = 12.sp)
                    }
                }
                PluginStatus.DOWNLOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        enabled = true  // TODO: 检查 UGC 是否启用
                    ) {
                        Text("安装", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ============================================================
// 插件详情弹窗
// Plugin detail dialog
// ============================================================

@Composable
fun PluginDetailDialog(
    plugin: PluginMarketItem,
    isUgcEnabled: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    val badge = getPermissionBadge(plugin.permissionLevel)
    var showPermissionDetail by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plugin.name, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Text("v${plugin.version}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column {
                // 作者
                Text(
                    "作者: ${plugin.author}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 描述
                Text(plugin.description, fontSize = 14.sp)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 信息表格
                // Info table
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("大小", plugin.fileSize)
                    InfoRow("评分", "⭐ ${String.format("%.1f", plugin.rating)}")
                    InfoRow("下载", "${plugin.downloadCount} 次")
                    InfoRow("价格", if (plugin.isFree) "免费" else plugin.price)
                    
                    // 权限等级（可点击展开详情）
                    // Permission level (clickable for details)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPermissionDetail = !showPermissionDetail }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("权限等级", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = badge.color.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "🟢 ${badge.label}",
                                    fontSize = 12.sp,
                                    color = badge.color,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Icon(
                                if (showPermissionDetail) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    // 权限详情（展开时显示）
                    if (showPermissionDetail) {
                        Surface(
                            color = badge.color.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    badge.description,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    when (plugin.permissionLevel) {
                                        0 -> "✅ 读取引擎参数\n✅ 设置引擎参数\n✅ 加载预设\n❌ 网络访问\n❌ 文件系统\n❌ Shell 执行"
                                        1 -> "✅ 读取引擎参数\n✅ 设置引擎参数\n✅ 加载预设\n✅ HTTP 网络访问（经代理）\n❌ 文件系统\n❌ Shell 执行"
                                        2 -> "✅ 所有基础功能\n✅ 加载原生 .so\n⚠ 文件系统（受限）\n❌ Shell 执行"
                                        3 -> "✅ 所有功能\n⚠ Shell 执行（逐条确认）\n⚠ 完整文件系统访问\n⚠ 可能影响设备稳定性"
                                        else -> ""
                                    },
                                    fontSize = 12.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
                
                if (plugin.source == PluginSource.P2P_DISCOVERED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "此插件通过 P2P 网络发现，来源未经验证。请谨慎安装。",
                            fontSize = 12.sp,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isUgcEnabled) {
                        onInstall()
                    }
                },
                enabled = isUgcEnabled && plugin.status != PluginStatus.INSTALLED
            ) {
                Text(
                    when (plugin.status) {
                        PluginStatus.INSTALLED -> "已安装"
                        PluginStatus.UPDATE_AVAILABLE -> "更新"
                        else -> if (plugin.isFree) "安装" else "购买安装"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp)
    }
}
