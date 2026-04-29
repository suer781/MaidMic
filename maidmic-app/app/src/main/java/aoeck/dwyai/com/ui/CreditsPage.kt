// CreditsPage.kt — 开源鸣谢 / Open Source Credits
// ============================================================
// MaidMic 使用了以下开源项目和社区资源，
// 在此表示诚挚感谢。
//
// MaidMic uses the following open-source projects and community
// resources, for which we express our sincere gratitude.

package aoeck.dwyai.com.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

// ============================================================
// 开源项目数据
// Open source project data
// ============================================================

data class CreditItem(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
    val description: String,
    val isDirectReference: Boolean = false // true = 直接引用/借鉴了代码
)

private val credits = listOf(
    CreditItem(
        name = "HubeRSoundX (HXAudio)",
        author = "HuberHaYu",
        license = "部分开源 (Partial)",
        url = "https://github.com/HuberHaYu/HubeRSoundX",
        description = "频响曲线预渲染算法（HxCore）—— 多段均衡器 + 前级频响曲线拟合",
        isDirectReference = true
    ),
    CreditItem(
        name = "Shizuku",
        author = "Rikka Apps",
        license = "Apache 2.0",
        url = "https://github.com/RikkaApps/Shizuku",
        description = "非 root 权限提升框架，用于 AAudio 音频环回"
    ),
    CreditItem(
        name = "LuaJ",
        author = "LuaJ Contributors",
        license = "MIT",
        url = "https://github.com/luaj/luaj",
        description = "Java 实现的 Lua 运行时，用于 UGC 插件沙箱"
    ),
    CreditItem(
        name = "Jetpack Compose",
        author = "Google / Android Open Source Project",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/compose",
        description = "声明式 UI 框架"
    ),
    CreditItem(
        name = "Material 3",
        author = "Google / Android Open Source Project",
        license = "Apache 2.0",
        url = "https://m3.material.io/",
        description = "Material Design 3 设计系统与组件库"
    ),
    CreditItem(
        name = "AndroidX",
        author = "Google / Android Open Source Project",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/androidx",
        description = "Android 扩展库 (Core KTX, Activity Compose, Lifecycle)"
    ),
    CreditItem(
        name = "Kotlin",
        author = "JetBrains",
        license = "Apache 2.0",
        url = "https://kotlinlang.org/",
        description = "编程语言与标准库"
    ),
    CreditItem(
        name = "Android NDK",
        author = "Google / Android Open Source Project",
        license = "Apache 2.0",
        url = "https://developer.android.com/ndk",
        description = "原生开发工具包，支持 C/C++ 音频引擎编译"
    ),
    CreditItem(
        name = "Andrej Karpathy Coding Guidelines",
        author = "Andrej Karpathy / ForrestChang",
        license = "MIT",
        url = "https://github.com/forrestchang/andrej-karpathy-skills",
        description = "AI 辅助编程行为规范（CLAUDE.md）"
    ),
)

// ============================================================
// 鸣谢页面
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "开源鸣谢",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Open Source Credits",
            fontSize = 12.sp,
            color = Color(0xFF999999)
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 特别鸣谢标注
            Surface(
                color = Color(0xFF2A1A1A),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "📌 特别鸣谢",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFBB86)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "频响曲线预渲染算法（HxCore）借鉴自 HubeRSoundX(HXAudio) 项目，\n" +
                                "感谢 HuberHaYu 的开源贡献。",
                        fontSize = 12.sp,
                        color = Color(0xFFCCCCCC),
                        lineHeight = 18.sp
                    )
                }
            }

            // 协议说明
            Text(
                "MaidMic 使用了以下开源项目（按贡献相关性排序）：",
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )

            // 项目列表
            credits.forEach { item ->
                CreditCard(
                    item = item,
                    onUrlClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // 版权声明
            Surface(
                color = Color(0xFF1A1A2E),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "MaidMic 版权所有 © 2026 suer781\n" +
                            "Licensed under Apache License 2.0\n\n" +
                            "本页面列出的开源项目归其各自所有者所有。",
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

// ============================================================
// 项目卡片
// ============================================================

@Composable
private fun CreditCard(item: CreditItem, onUrlClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.isDirectReference) Color(0xFF1E1E2E) else Color(0xFF1E1E1E)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        if (item.isDirectReference) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFFFFBB86).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "借鉴",
                                    fontSize = 10.sp,
                                    color = Color(0xFFFFBB86),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        item.author,
                        fontSize = 11.sp,
                        color = Color(0xFF888888)
                    )
                }

                // 许可证标签 + 链接
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFF333333),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            item.license,
                            fontSize = 10.sp,
                            color = Color(0xFFAAAAAA),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = "打开链接",
                        tint = Color(0xFFBB86FC),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onUrlClick() }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                item.description,
                fontSize = 12.sp,
                color = Color(0xFFAAAAAA),
                lineHeight = 17.sp
            )
        }
    }
}
