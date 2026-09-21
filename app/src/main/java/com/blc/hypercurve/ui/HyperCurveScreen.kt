package com.blc.hypercurve.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blc.hypercurve.core.Config
import com.blc.hypercurve.core.CurveMath
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HyperCurveScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(CurveStore.load(context)) }
    var service by remember { mutableStateOf<XposedService?>(null) }
    var targets by remember { mutableStateOf<List<HookedTarget>>(emptyList()) }
    var inScope by remember { mutableStateOf(false) }
    var sysUiInScope by remember { mutableStateOf(false) }
    var framework by remember { mutableStateOf("未连接 LSPosed") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun toast(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }
    val parsed = remember(state.pointsText) { CurveMath.parsePoints(state.pointsText) }

    fun readState(s: XposedService?) {
        targets = LspBridge.targets(s)
        val scopePackages = LspBridge.scopeOf(s)
        val unknown = scopePackages.isEmpty()
        inScope = unknown || scopePackages.any { it == "system" || it == "android" }
        // 本机实测滑条→背光的换算在 SystemUI，缺了它曲线不会生效
        sysUiInScope = unknown || scopePackages.any { it == "com.android.systemui" }
        framework = runCatching {
            if (s == null) "未连接 LSPosed" else "${s.frameworkName} ${s.frameworkVersion}（API ${s.apiVersion}）"
        }.getOrDefault("读取框架信息失败")
    }

    DisposableEffect(Unit) {
        LspBridge.listen(
            onBind = { s -> service = s; readState(s) },
            onDied = { _ -> service = null; readState(null) }
        )
        onDispose { }
    }

    /** 刷新框架连接状态 + 已注入进程列表。 */
    fun refreshSummary() {
        readState(service)
        val list = targets
        if (list.isEmpty()) {
            toast("没有已注入的进程：请先在 LSPosed 中启用模块，并确认作用域勾上「系统框架」和「系统界面」")
        } else {
            toast("已注入：" + list.joinToString { "${it.processName}(pid ${it.pid})" })
        }
    }

    fun save() {
        val points = parsed
        if (points == null) {
            toast("折线点格式不对：需要 百分比:码值，至少 2 点且按百分比递增")
            return
        }
        val normalized = CurveMath.format(points)
        CurveStore.save(
            context,
            state.copy(pointsText = normalized)
        )
        state = state.copy(pointsText = normalized)
        val ok = LspBridge.pushConfig(
            service,
            enabled = state.enabled,
            mode = state.mode,
            points = normalized,
            capCode = state.capCode,
            manualMaxCode = state.manualMaxCode,
            verbose = state.verbose,
        )
        if (!ok) {
            toast("已存本地，但下推失败：未连接框架服务（模块未在 LSPosed 启用？）")
            return
        }
        toast("已下推；钩子侧 1 秒内自动读到新配置（换模块代码才需重启手机）")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("亮度曲线")
                        Text(
                            "HyperOS 亮度曲线 · SystemUI 滑条换算 + system_server",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionCard(
                framework = framework,
                connected = service != null,
                inScope = inScope,
                sysUiInScope = sysUiInScope,
                targets = targets,
                onRefresh = { refreshSummary() },
                onRequestScope = {
                    LspBridge.requestSystemScope(service) { msg, ok ->
                        toast(if (ok) msg else "作用域请求：$msg")
                        readState(service)
                    }
                },
            )

            CurveCard(
                state = state,
                pointsValid = parsed != null,
                onEnabledChange = { state = state.copy(enabled = it) },
                onModeChange = { state = state.copy(mode = it) },
                onPointsChange = { state = state.copy(pointsText = it) },
                onCapChange = { state = state.copy(capCode = it) },
                onManualMaxChange = { state = state.copy(manualMaxCode = it) },
                onVerboseChange = { state = state.copy(verbose = it) },
                onPreset = { state = state.copy(pointsText = it) },
                onRestoreDefaults = {
                    state = state.copy(
                        pointsText = Config.DEFAULT_POINTS,
                        capCode = 0,
                        manualMaxCode = Config.DEFAULT_MANUAL_MAX,
                    )
                    toast("已恢复默认曲线（折线/上限/手动最大）；点「保存并下推」生效")
                },
                onSave = { save() },
                saveEnabled = service != null,
            )

            PreviewCard(parsed = parsed, splineText = state.splineText, capCode = state.capCode)
        }
    }
}

@Composable
private fun ConnectionCard(
    framework: String,
    connected: Boolean,
    inScope: Boolean,
    sysUiInScope: Boolean,
    targets: List<HookedTarget>,
    onRefresh: () -> Unit,
    onRequestScope: () -> Unit,
) {
    InfoCard(title = "框架与作用域") {
        Text(framework, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
                append(if (connected) "服务已连接" else "服务未连接")
                append(" · 作用域")
                append(if (inScope) "含 system" else "不含 system")
                append(if (sysUiInScope) " + SystemUI" else "，缺 SystemUI")
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (connected && inScope && sysUiInScope)
                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        if (targets.isEmpty()) {
            Text("暂无已注入进程。", style = MaterialTheme.typography.bodySmall)
        } else {
            targets.forEach { t ->
                Text("${t.processName} · pid=${t.pid} · ${t.state}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onRefresh) { Text("刷新") }
            TextButton(onClick = onRequestScope, enabled = connected) { Text("申请作用域") }
        }
        Text(
            "配置保存后钩子侧会自动读取；改了模块代码才需要重启手机。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CurveCard(
    state: EditorState,
    pointsValid: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (Int) -> Unit,
    onPointsChange: (String) -> Unit,
    onCapChange: (Int) -> Unit,
    onManualMaxChange: (Int) -> Unit,
    onVerboseChange: (Boolean) -> Unit,
    onPreset: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    InfoCard(title = "曲线") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = state.mode == Config.MODE_EXACT, onClick = { onModeChange(Config.MODE_EXACT) })
            Text("精确码值", style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected = state.mode == Config.MODE_CURVE, onClick = { onModeChange(Config.MODE_CURVE) })
            Text("跟随系统保护", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            if (state.mode == Config.MODE_EXACT)
                "精确码值：滑条百分比严格等于下表码值（SystemUI 侧直接给出背光浮点）。"
            else
                "跟随系统保护：同样走 SystemUI 换算，但结果会被压进系统当时的 [最小,最大] 区间，阳光模式/热控上限仍然生效。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = state.pointsText,
            onValueChange = onPointsChange,
            label = { Text("折线点（滑条% : 背光码值 0-16383）") },
            supportingText = {
                Text(
                    if (pointsValid) "例：0:4,50:1500,100:5000" else "格式错误：至少 2 点、以逗号分隔、百分比递增",
                    color = if (pointsValid) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            },
            isError = !pointsValid,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("全局上限码值（夹住请求）：${state.capCode}${if (state.capCode == 0) "（不限）" else ""}")
        Slider(
            value = state.capCode.toFloat(),
            onValueChange = { onCapChange(it.toInt()) },
            valueRange = 0f..Config.MAX_CODE,
        )
        Text(
            "手动最大上限（抬高系统天花板）：${state.manualMaxCode}" +
                if (state.manualMaxCode == 0) "（不动，${Config.MANUAL_MAX_HINT}）" else ""
        )
        Slider(
            value = state.manualMaxCode.toFloat(),
            onValueChange = { onManualMaxChange(it.toInt()) },
            valueRange = 0f..Config.MAX_CODE,
        )
        Text(
            "抬高只会在该项大于系统当前上限时生效；超过 ≈6750（800nit）的部分能否真正变亮取决于系统 HBM/热控。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("详细日志", modifier = Modifier.weight(1f))
            Switch(checked = state.verbose, onCheckedChange = onVerboseChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPreset(Config.PRESET_LOW_BOOST) }) { Text("低段提亮预设") }
            TextButton(onClick = { onPreset(Config.PRESET_SYSTEM_LIKE) }) { Text("近似系统") }
            TextButton(onClick = onRestoreDefaults) { Text("恢复默认") }
        }
        Text(
            "「恢复默认」= 折线→${Config.DEFAULT_POINTS}，上限→不限，手动最大→不动。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Check, contentDescription = null)
            Text("保存并下推", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PreviewCard(parsed: List<CurveMath.CurvePoint>?, splineText: String, capCode: Int) {
    val spline = remember(splineText) { CurveMath.Spline.parse(splineText) }
    val rows = remember(parsed, spline, capCode) {
        if (parsed == null || spline == null) {
            emptyList()
        } else {
            (0..10).map { i ->
                val pct = i * 10f
                val code = CurveMath.applyCap(CurveMath.codeAt(parsed, pct), capCode.toFloat())
                Triple(pct, code, spline.xAt(code / Config.MAX_CODE))
            }
        }
    }
    InfoCard(title = "换算预览") {
        if (rows.isEmpty()) {
            Text("折线点或 nit 样条无效。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "滑条%   目标码值   需要实际nit",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            rows.forEach { (pct, code, nit) ->
                Text(
                    "%3.0f%%   %6.0f     %8.2f".format(pct, code, nit),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                "实时命中可用 adb logcat -s HyperCurve 查看。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
