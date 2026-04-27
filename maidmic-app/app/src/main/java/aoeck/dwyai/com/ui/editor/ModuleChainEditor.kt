// maidmic-app/app/src/main/java/com/maidmic/ui/editor/ModuleChainEditor.kt
// MaidMic 模块链编辑器
// ============================================================
// 用户在这里拖拽排列 DSP 模块、调整参数。
// 支持两种模式（在开发者选项中切换）：
//
// 1. SIMPLE / 简易（线性）模式：
//    模块排成一列，上下拖拽调整顺序，每个模块有展开/折叠查看参数。
//
// 2. DAG 模式：
//    可视化 DAG 图，节点可拖拽连接、创建分流/并联/混音。
//
// 两种模式下，用户双击任意参数值可以直接输入数字——不限制范围。
// 滑块有建议范围，但"自定义输入"框没有限制。

package aoeck.dwyai.com.ui.editor

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ============================================================
// 数据模型
// Data models
// ============================================================
// 这些模型映射到 C 层的 maidmic_dag_node_t 和 maidmic_param_t
// These models map to C-level maidmic_dag_node_t and maidmic_param_t

/** 可用 DSP 模块列表（引擎注册的模块） */
data class DspModuleInfo(
    val id: Int,
    val name: String,
    val description: String,
    val icon: String  // emoji 图标，简单粗暴，不需要额外资源
)

/** 管线中的模块节点实例 */
data class PipelineNode(
    val nodeId: Int,
    val module: DspModuleInfo,
    var bypass: Boolean = false,
    var params: MutableList<DspParam> = mutableListOf()
)

/** DSP 参数（映射 C 层 maidmic_param_t） */
data class DspParam(
    val key: String,
    val label: String,
    val type: ParamType,
    var value: Float,
    val min: Float,
    val max: Float,
    val unit: String
)

enum class ParamType { FLOAT, INT, BOOL, STRING }

/** 内置 DSP 模块列表 */
val BUILTIN_MODULES = listOf(
    DspModuleInfo(1,  "Gain",       "Adjust volume level. No limits.", "🔊"),
    DspModuleInfo(2,  "EQ",         "Equalizer — shape your frequency response.", "🎛"),
    DspModuleInfo(3,  "Compressor",  "Dynamic range compression.", "📉"),
    DspModuleInfo(4,  "Pitch Shift", "Change pitch without changing speed (PSOLA).", "🎵"),
    DspModuleInfo(5,  "Reverb",     "Add spatial ambience.", "🌊"),
    DspModuleInfo(6,  "Chorus",     "Thicken your voice with chorus effect.", "🎤"),
    DspModuleInfo(7,  "Distortion", "Add grit and warmth.", "🔥"),
    DspModuleInfo(8,  "Delay",      "Echo effect with feedback control.", "⏳"),
    DspModuleInfo(9,  "Noise Gate",  "Silence when you're not speaking.", "🚪"),
    DspModuleInfo(10, "Limiter",    "Brick-wall protection for your ears.", "🛡"),
)

// 每个模块的默认参数（供新建时使用）
fun getDefaultParams(moduleId: Int): MutableList<DspParam> = when (moduleId) {
    1 -> mutableListOf(   // Gain
        DspParam("gain_db", "Gain", ParamType.FLOAT, 0f, -60f, 60f, "dB"),
        DspParam("bypass", "Bypass", ParamType.BOOL, 0f, 0f, 1f, ""),
    )
    4 -> mutableListOf(   // Pitch Shift
        DspParam("semitones", "Semitones", ParamType.FLOAT, 0f, -24f, 24f, "st"),
        DspParam("formant", "Formant Shift", ParamType.FLOAT, 0f, -12f, 12f, "st"),
        DspParam("bypass", "Bypass", ParamType.BOOL, 0f, 0f, 1f, ""),
    )
    5 -> mutableListOf(   // Reverb
        DspParam("room_size", "Room Size", ParamType.FLOAT, 0.5f, 0f, 1f, "%"),
        DspParam("decay", "Decay", ParamType.FLOAT, 0.3f, 0f, 10f, "s"),
        DspParam("wet", "Wet Mix", ParamType.FLOAT, 0.3f, 0f, 1f, "%"),
        DspParam("bypass", "Bypass", ParamType.BOOL, 0f, 0f, 1f, ""),
    )
    else -> mutableListOf(
        DspParam("bypass", "Bypass", ParamType.BOOL, 0f, 0f, 1f, ""),
    )
}

// ============================================================
// 模块链编辑器主 Composable
// Module Chain Editor Main Composable
// ============================================================

/**
 * 模块链编辑器
 * 
 * @param isDagMode 当前是否为 DAG 模式（在开发者选项中切换）
 * @param nodes 当前管线中的模块节点列表
 * @param onAddModule 用户点击"添加模块"按钮
 * @param onRemoveModule 用户移除一个模块
 * @param onReorderModule 用户重新排序模块
 * @param onToggleBypass 用户切换旁路开关
 * @param onParamChange 用户改变了某个参数
 */
@Composable
fun ModuleChainEditor(
    isDagMode: Boolean,
    nodes: List<PipelineNode>,
    onAddModule: () -> Unit,
    onRemoveModule: (Int) -> Unit,
    onReorderModule: (Int, Int) -> Unit,  // fromIndex, toIndex
    onToggleBypass: (Int) -> Unit,
    onParamChange: (Int, String, Float) -> Unit  // nodeId, key, value
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isDagMode) {
            DagEditor(
                nodes = nodes,
                onAddModule = onAddModule,
                onRemoveModule = onRemoveModule,
                onToggleBypass = onToggleBypass,
                onParamChange = onParamChange
            )
        } else {
            SimpleEditor(
                nodes = nodes,
                onAddModule = onAddModule,
                onRemoveModule = onRemoveModule,
                onReorderModule = onReorderModule,
                onToggleBypass = onToggleBypass,
                onParamChange = onParamChange
            )
        }
    }
}

// ============================================================
// 简易模式（线性列表）
// Simple Mode (Linear List)
// ============================================================

@Composable
fun SimpleEditor(
    nodes: List<PipelineNode>,
    onAddModule: () -> Unit,
    onRemoveModule: (Int) -> Unit,
    onReorderModule: (Int, Int) -> Unit,
    onToggleBypass: (Int) -> Unit,
    onParamChange: (Int, String, Float) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 顶部：显示处理路径
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎤", fontSize = 20.sp)
                    Text(" Input → ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    // 模块路径指示：Gain → EQ → Pitch → ...
                    nodes.forEachIndexed { index, node ->
                        Text(
                            node.module.icon + " " + node.module.name,
                            fontSize = 12.sp,
                            fontWeight = if (node.bypass) FontWeight.Light else FontWeight.Medium,
                            color = if (node.bypass) Color.Gray else MaterialTheme.colorScheme.onSurface
                        )
                        if (index < nodes.size - 1) {
                            Text(" → ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    
                    Text(" → 🎧 Output", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        // 模块列表
        itemsIndexed(nodes) { index, node ->
            ModuleCard(
                node = node,
                index = index,
                totalCount = nodes.size,
                onRemove = { onRemoveModule(node.nodeId) },
                onMoveUp = {
                    if (index > 0) onReorderModule(index, index - 1)
                },
                onMoveDown = {
                    if (index < nodes.size - 1) onReorderModule(index, index + 1)
                },
                onToggleBypass = { onToggleBypass(node.nodeId) },
                onParamChange = { key, value -> onParamChange(node.nodeId, key, value) }
            )
        }
        
        // 添加模块按钮
        item {
            OutlinedButton(
                onClick = onAddModule,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Module")
            }
        }
    }
}

// ============================================================
// 单个模块卡片
// Single Module Card
// ============================================================

@Composable
fun ModuleCard(
    node: PipelineNode,
    index: Int,
    totalCount: Int,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleBypass: () -> Unit,
    onParamChange: (String, Float) -> Unit
) {
    // 是否展开显示参数
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (node.bypass)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ============================================================
            // 模块头部：图标 + 名称 + 控制按钮
            // Module header: icon + name + control buttons
            // ============================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：序号 + 图标 + 名称
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(node.module.icon, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            node.module.name,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                        Text(
                            node.module.description,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // 右侧：控制按钮
                Row {
                    // 上移
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move Up")
                    }
                    // 下移
                    IconButton(onClick = onMoveDown, enabled = index < totalCount - 1) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move Down")
                    }
                    // 展开/折叠参数
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            "Toggle Parameters"
                        )
                    }
                }
            }
            
            // ============================================================
            // 操作按钮行：旁路 + 移除
            // Action row: bypass + remove
            // ============================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // 旁路开关
                TextButton(onClick = onToggleBypass) {
                    Icon(
                        if (node.bypass) Icons.Default.NotInterested else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (node.bypass) "Bypassed" else "Active",
                        fontSize = 12.sp
                    )
                }
                
                // 移除按钮
                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Remove", fontSize = 12.sp)
                }
            }
            
            // ============================================================
            // 参数区域（展开时显示）
            // Parameter area (shown when expanded)
            // ============================================================
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    node.params.forEach { param ->
                        ParamSlider(
                            param = param,
                            onValueChange = { newVal -> onParamChange(param.key, newVal) }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 参数滑块（带手动输入）
// Parameter Slider (with manual input)
// ============================================================
// 用户可以拖拽滑块，也可以点击数值直接输入。
// 滑块的范围只是"建议值"，手动输入框没有任何限制。
// Users can drag the slider or tap the value to input directly.
// Slider range is "suggested" — manual input has no limits.

@Composable
fun ParamSlider(
    param: DspParam,
    onValueChange: (Float) -> Unit
) {
    // 是否显示手动输入框
    var isEditing by remember { mutableStateOf(false) }
    // 输入框文本
    var editText by remember { mutableStateOf(String.format(Locale.US, "%.1f", param.value)) }
    
    // 布尔参数用 Switch
    // Boolean params use Switch
    if (param.type == ParamType.BOOL) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(param.label, fontSize = 14.sp)
            Switch(
                checked = param.value > 0.5f,
                onCheckedChange = { onValueChange(if (it) 1f else 0f) }
            )
        }
        return
    }
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 参数名
            Text(param.label, fontSize = 14.sp)
            
            // 参数值（点击可编辑）
            if (isEditing) {
                // 手动输入模式
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = { Text(param.unit, fontSize = 10.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
            } else {
                // 显示模式（点击进入编辑）
                TextButton(
                    onClick = { isEditing = true }
                ) {
                    Text(
                        String.format(Locale.US, "%.1f %s", param.value, param.unit),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
        }
        
        // 滑块
        Slider(
            value = param.value,
            onValueChange = { newVal ->
                param.value = newVal  // 实时更新（给 C 引擎）
                onValueChange(newVal)
                editText = String.format(Locale.US, "%.1f", newVal)
            },
            onValueChangeFinished = { isEditing = false },
            valueRange = param.min..param.max,
            modifier = Modifier.fillMaxWidth()
        )
        
        // 最小值 / 最大值 提示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                String.format(Locale.US, "%.0f", param.min),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                String.format(Locale.US, "%.0f", param.max),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================
// DAG 模式编辑器（占位）
// DAG Mode Editor (placeholder)
// ============================================================
//  TODO: 实现 DAG 可视化编辑器
//  需要一个 Canvas 绘制节点和边，支持拖拽连接、分流混音。
//  这个 UI 复杂度相当于一个轻量级的节点编辑器（像 Blender 的 Shader Editor）。
//  
//  实现计划：
//  1. Canvas 绘制：节点框 + 连接线（贝塞尔曲线）
//  2. 拖拽交互：移动节点位置、拖出连线
//  3. 右键菜单：添加/删除节点、断开连接
//  4. 缩放/平移：双指手势
//  
//  第一阶段先做简易模式，DAG 模式在后面迭代中加入。

@Composable
fun DagEditor(
    nodes: List<PipelineNode>,
    onAddModule: () -> Unit,
    onRemoveModule: (Int) -> Unit,
    onToggleBypass: (Int) -> Unit,
    onParamChange: (Int, String, Float) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "🔀 DAG Mode",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Visual node editor coming soon.\nFor now, switch to Simple mode.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            // 兜底：DAG 模式下也显示简易列表
            // Fallback: show simple list in DAG mode for now
            SimpleEditor(
                nodes = nodes,
                onAddModule = onAddModule,
                onRemoveModule = onRemoveModule,
                onReorderModule = { _, _ -> },  // DAG 模式下不可拖拽排序
                onToggleBypass = onToggleBypass,
                onParamChange = onParamChange
            )
        }
    }
}
