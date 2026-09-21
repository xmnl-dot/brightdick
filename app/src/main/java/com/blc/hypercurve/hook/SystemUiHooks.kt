package com.blc.hypercurve.hook

import com.blc.hypercurve.core.Config
import com.blc.hypercurve.core.CurveMath
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * SystemUI 侧的钩子（作用域 `com.android.systemui`）：把「滑条刻度 → 背光浮点」的换算换成自定义曲线。
 *
 * ```
 * MiuiBrightnessController.onChanged(...)
 *   → BrightnessUtils.convertGammaToLinearFloat(gamma, min, max)
 *   → 后台线程 → DisplayManager.setTemporaryBrightness / setBrightness(displayId, backlight)
 * ```
 *
 * 1. 正向 [HOOK_G2L]：`convertGammaToLinearFloat(int gamma, float min, float max)`
 *    gamma → 百分比 → 折线取目标码值 → 浮点（码值 / 满量程）。
 * 2. 反向 [HOOK_L2G]：`convertLinearToGammaFloat(float brightness, float min, float max)`
 *    浮点 → 码值 → 百分比 → gamma；与正向互逆，系统回读时滑条位置才不会跳。
 *
 * 两种模式：
 * - MODE_EXACT：忽略 min/max，直接返回 码值/满量程 —— 滑条位置严格等于目标码值。
 * - MODE_CURVE：返回 `min + (max - min) * 码值/满量程`，保留系统上下限（阳光模式、热控）。
 */
object SystemUiHooks {

    private const val CLASS_BRIGHTNESS_UTILS = "com.android.systemui.controlcenter.policy.BrightnessUtils"
    private const val HOOK_G2L = "hypercurve.sysui.gamma2linear"
    private const val HOOK_L2G = "hypercurve.sysui.linear2gamma"

    private val handles = ConcurrentHashMap<String, HookHandle>()

    /** 挂载时只存 Class 引用，不读任何静态字段（见 [gammaMax]）。 */
    @Volatile
    private var utilsClass: Class<*>? = null

    /**
     * 滑条 gamma 空间满量程（`BrightnessUtils.GAMMA_SPACE_MAX`），首次使用时懒读：
     * 在 install() 阶段读会提前触发该类的 `<clinit>`（其静态块依赖已创建的 Application）。
     */
    @Volatile
    private var gammaSpaceMax = 0

    private fun gammaMax(): Int {
        val cached = gammaSpaceMax
        if (cached > 1) return cached
        val value = runCatching {
            utilsClass?.fieldOrNull("GAMMA_SPACE_MAX")?.getInt(null)
        }.getOrNull()?.takeIf { it > 1 } ?: 16383
        gammaSpaceMax = value
        HookRuntime.log(HookRuntime.INFO, "GAMMA_SPACE_MAX=$value（懒读）")
        return value
    }

    fun install(api: XposedInterface): Int {
        if (handles.isNotEmpty()) {
            HookRuntime.log(HookRuntime.INFO, "SystemUI 已挂过 ${handles.size} 个钩子，跳过重复安装")
            return handles.size
        }
        HookRuntime.ensureApi(api)
        val clazz = HookRuntime.loadTargetClass(CLASS_BRIGHTNESS_UTILS)
        if (clazz == null) {
            HookRuntime.log(HookRuntime.WARN, "SystemUI 里没找到 BrightnessUtils，放弃挂载")
            return 0
        }
        // 只存引用；GAMMA_SPACE_MAX 留到 gammaMax() 里懒读
        utilsClass = clazz

        var count = 0
        count += tryHook("convertGammaToLinearFloat", HOOK_G2L) {
            val method = clazz.methodOrNull(
                "convertGammaToLinearFloat",
                Integer.TYPE, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            if (method == null) null else api.hook(method).hookerWith(HOOK_G2L, ::gammaToLinear)
        }
        count += tryHook("convertLinearToGammaFloat", HOOK_L2G) {
            val method = clazz.methodOrNull(
                "convertLinearToGammaFloat",
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            if (method == null) null else api.hook(method).hookerWith(HOOK_L2G, ::linearToGamma)
        }
        HookRuntime.log(HookRuntime.INFO, "SystemUI 挂载完成 $count/2（gamma 满量程首次使用时懒读）")
        return count
    }

    // ---------------- 换算 ----------------

    /** 滑条 gamma 值 -> 0..100 百分比。 */
    private fun percentOf(gamma: Int): Float =
        (gamma.toFloat() / gammaMax() * 100f).coerceIn(0f, 100f)

    /** 目标码值 -> 交给框架的背光浮点；[exact] 为 true 时忽略 min/max，直接返回码值比例。 */
    private fun backlightOf(targetCode: Float, min: Float, max: Float, exact: Boolean): Float {
        val fraction = (targetCode / HookRuntime.maxCode).coerceIn(0f, 1f)
        return if (exact) fraction else min + (max - min) * fraction
    }

    /** 框架的背光浮点 -> 目标码值（[backlightOf] 的逆）。 */
    private fun codeOf(backlight: Float, min: Float, max: Float, exact: Boolean): Float {
        val fraction = if (exact) {
            backlight
        } else {
            if (max > min) (backlight - min) / (max - min) else backlight
        }
        return (fraction * HookRuntime.maxCode).coerceIn(0f, HookRuntime.maxCode)
    }

    // ---------------- 钩子实现 ----------------

    /**
     * 正向：滑条 gamma → 背光浮点。能用曲线给出结果时直接返回，不再调用原方法。
     */
    private fun gammaToLinear(chain: XposedInterface.Chain): Any? {
        HookRuntime.refresh()
        val cfg = HookRuntime.config
        if (!cfg.usable) return chain.proceed()

        val gamma = (chain.getArg(0) as? Number)?.toInt() ?: return chain.proceed()
        val min = (chain.getArg(1) as? Number)?.toFloat() ?: 0f
        val max = (chain.getArg(2) as? Number)?.toFloat() ?: 1f

        val percent = percentOf(gamma)
        val target = CurveMath.applyCap(CurveMath.codeAt(cfg.points, percent), cfg.cap)
        val out = backlightOf(target, min, max, cfg.mode == Config.MODE_EXACT)
        HookRuntime.hit {
            "sysui 滑条 gamma=$gamma ($percent%) 目标码=$target -> backlight=$out " +
                "(模式=${if (cfg.mode == Config.MODE_EXACT) "精确" else "曲线"}, min=$min max=$max)"
        }
        return out
    }

    /** 反向：背光浮点 → 滑条 gamma（与正向互逆，保证回读时滑条位置不跳）。 */
    private fun linearToGamma(chain: XposedInterface.Chain): Any? {
        HookRuntime.refresh()
        val cfg = HookRuntime.config
        if (!cfg.usable) return chain.proceed()

        val backlight = (chain.getArg(0) as? Number)?.toFloat() ?: return chain.proceed()
        val min = (chain.getArg(1) as? Number)?.toFloat() ?: 0f
        val max = (chain.getArg(2) as? Number)?.toFloat() ?: 1f

        val code = CurveMath.applyCap(codeOf(backlight, min, max, cfg.mode == Config.MODE_EXACT), cfg.cap)
        val percent = CurveMath.percentAt(cfg.points, code)
        val gamma = Math.round(percent / 100f * gammaMax())
        HookRuntime.verbose { "sysui 回读 backlight=$backlight -> 码=$code -> gamma=$gamma" }
        return gamma
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
