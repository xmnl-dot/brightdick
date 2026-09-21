package com.blc.hypercurve.hook

import com.blc.hypercurve.core.Config
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * system_server 侧的钩子：抬高手动最大上限。
 *
 * 两个挂载点都是 DisplayPowerControllerImpl 上的天花板函数，返回值由 binder 报给 SystemUI
 * 作为滑条上限（阳光模式也读它们）：
 *
 * - `getMaxManualBrightness(float nit)`：普通手动最大背光（随环境光档位变）；
 * - `getMaxManualBrightnessBoost()`：阳光模式/boost 上限（= `config_max_manual_brt_boost`，800 nit）。
 */
object BrightnessHooks {

    private const val CLASS_DPC = "com.android.server.display.DisplayPowerControllerImpl"

    private const val HOOK_MAX_MANUAL = "hypercurve.maxmanual"
    private const val HOOK_MAX_BOOST = "hypercurve.maxboost"

    private val handles = ConcurrentHashMap<String, HookHandle>()

    /** `DisplayPowerControllerImpl.mMaxManualBoostBrightness`：阳光模式有一条分支直接读它，需同步抬高。 */
    private var boostField: java.lang.reflect.Field? = null

    /** 挂载全部钩子，返回成功的数量（同一进程重复调用直接返回上次结果）。 */
    fun install(api: XposedInterface): Int {
        if (handles.isNotEmpty()) {
            HookRuntime.log(HookRuntime.INFO, "system_server 已挂过 ${handles.size} 个钩子，跳过重复安装")
            return handles.size
        }
        HookRuntime.ensureApi(api)
        HookRuntime.maxCode = readPanelMaxCode()

        val dpc = HookRuntime.loadTargetClass(CLASS_DPC)
        if (dpc == null) {
            HookRuntime.log(HookRuntime.WARN, "未找到 DisplayPowerControllerImpl，放弃挂载")
            return 0
        }
        boostField = dpc.fieldOrNull("mMaxManualBoostBrightness")

        var count = 0
        // 抬高天花板：滑条上限 / 阳光模式上限（设置里「手动最大上限」> 0 时才生效）
        count += tryHook("getMaxManualBrightness(抬高手动最大)", HOOK_MAX_MANUAL) {
            val method = dpc.methodOrNull("getMaxManualBrightness", Float::class.javaPrimitiveType)
            if (method == null) null
            else api.hook(method).hookerWith(HOOK_MAX_MANUAL) { chain -> onCeiling(chain, "手动最大") }
        }
        count += tryHook("getMaxManualBrightnessBoost(抬高阳光上限)", HOOK_MAX_BOOST) {
            val method = dpc.methodOrNull("getMaxManualBrightnessBoost")
            if (method == null) null
            else api.hook(method).hookerWith(HOOK_MAX_BOOST) { chain -> onCeiling(chain, "阳光模式上限") }
        }
        HookRuntime.log(
            HookRuntime.INFO,
            "system_server 挂载完成 $count/2（maxCode=${HookRuntime.maxCode} 手动最大=${HookRuntime.config.manualMax}）"
        )
        return count
    }

    // ---------------- 钩子实现 ----------------

    /**
     * 把上限抬到「手动最大上限」对应的背光浮点。
     *
     * 原则：只抬不降；原值是 -1（不适用）或 ≤0 时不动，避免把不该开的路径打开。
     */
    private fun onCeiling(chain: XposedInterface.Chain, label: String): Any? {
        val proceeded = chain.proceed()
        val original = (proceeded as? Number)?.toFloat() ?: return proceeded   // 只 proceed 一次
        HookRuntime.refresh()
        val cfg = HookRuntime.config
        if (!cfg.usable || cfg.manualMax <= 0f) return original
        if (original <= 0f) return original
        val target = (cfg.manualMax / HookRuntime.maxCode).coerceIn(0f, 1f)
        if (target <= original) return original
        HookRuntime.hit { "抬高$label $original -> $target（目标码值 ${cfg.manualMax}）" }
        syncBoostField(chain.getThisObject(), target)
        return target
    }

    /** 把 `mMaxManualBoostBrightness` 字段同步抬高（阳光模式有一条分支绕过了 getter）。 */
    private fun syncBoostField(instance: Any?, target: Float) {
        val f = boostField ?: return
        runCatching {
            if (instance != null && f.getFloat(instance) < target) f.setFloat(instance, target)
        }.onFailure { HookRuntime.verbose { "同步 mMaxManualBoostBrightness 失败：${it.message}" } }
    }

    /** 读面板真实满量程，失败则用配置里的 16383。 */
    private fun readPanelMaxCode(): Float {
        return runCatching {
            File("/sys/class/backlight").listFiles()?.firstOrNull()?.let { dir ->
                File(dir, "max_brightness").readText().trim().toFloat()
            }
        }.getOrNull()?.takeIf { it > 1f } ?: Config.MAX_CODE
    }

    private inline fun tryHook(what: String, id: String, block: () -> HookHandle?): Int {
        return runCatching { block() }
            .getOrElse {
                HookRuntime.log(HookRuntime.ERROR, "挂载 $what 失败: ${it.message}", it)
                null
            }
            .let { handle ->
                if (handle == null) {
                    HookRuntime.log(HookRuntime.WARN, "方法不存在或挂载失败，跳过 $what")
                    0
                } else {
                    handles[id] = handle
                    1
                }
            }
    }
}
