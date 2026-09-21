package com.blc.hypercurve.hook

import com.blc.hypercurve.core.Config
import com.blc.hypercurve.core.CurveMath
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * SystemUI 侧的钩子（作用域 `com.android.systemui`）。
 *
 * 为什么必须在这里改：本机（myron / HyperOS 4.0.0.30）实测，状态栏亮度条走的是 AOSP 这条路，
 * 不是 MIUI 的 refactor 链路（system_server 里 `setTemporarySliderValue` / `sliderToLogicalBrightness`
 * 一次都没被调用，logcat 里只有 `DisplayManagerService.setTemporaryBrightness`）：
 *
 * ```
 * MiuiBrightnessController.onChanged(slider, isUserSliding, ..., gammaValue, ...)
 *   → float backlight = min(BrightnessUtils.convertGammaToLinearFloat(gammaValue, mMinimumBacklight, mMaximumBacklight), 1f)
 *   → 交给后台线程 → DisplayManager.setTemporaryBrightness/setBrightness(displayId, backlight)
 * ```
 *
 * 也就是说「滑条刻度 → 背光浮点」的换算整个发生在 SystemUI，system_server 只是照抄这个浮点
 * （0.41197655 就是 800nit / 6750 码值，与 sysfs 是 1:1 关系）。因此曲线要在这里改：
 *
 * 1. 正向 [HOOK_G2L]：`convertGammaToLinearFloat(int gamma, float min, float max)`
 *    gamma → 百分比 → 折线取目标码值 → 浮点（码值 / 满量程）。
 * 2. 反向 [HOOK_L2G]：`convertLinearToGammaFloat(float brightness, float min, float max)`
 *    浮点 → 码值 → 百分比 → gamma。必须和正向互逆，否则系统回读亮度时滑条会被拽到别的位置。
 *
 * 两种模式：
 * - MODE_EXACT：忽略 min/max，直接返回 码值/满量程 —— 滑条位置严格等于目标码值。
 * - MODE_CURVE：返回 `min + (max - min) * 码值/满量程`，把系统的上下限（阳光模式、热控）保留下来。
 */
object SystemUiHooks {

    private const val CLASS_BRIGHTNESS_UTILS = "com.android.systemui.controlcenter.policy.BrightnessUtils"
    private const val HOOK_G2L = "hypercurve.sysui.gamma2linear"
    private const val HOOK_L2G = "hypercurve.sysui.linear2gamma"

    private val handles = ConcurrentHashMap<String, HookHandle>()

    /** 挂载时只存 Class 引用，**不读任何静态字段**（见 [gammaMax] 的注释）。 */
    @Volatile
    private var utilsClass: Class<*>? = null

    /**
     * 滑条 gamma 空间满量程（`BrightnessUtils.GAMMA_SPACE_MAX`），首次使用时懒读。
     *
     * 为什么不能像常规做法那样在 install() 里反射读：那个静态字段会触发 `BrightnessUtils.<clinit>`，
     * 而它的静态块第一行是 `ActivityThread.currentApplication().getResources()`——在 onPackageReady
     * 阶段应用还没创建，`currentApplication()` 为 null → clinit 抛 NPE → 这个类被标记为初始化失败，
     * 之后 SystemUI 每次碰它（例如下拉进控制中心）都 NoClassDefFoundError 崩溃，形成崩溃循环。
     * 改成钩子被调用时再读：那时类必然已初始化完成，读字段是安全的。
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
        // 只存引用；不碰 GAMMA_SPACE_MAX（会提前触发 clinit，见 gammaMax 注释）
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

    /** 目标码值 -> 要交给框架的背光浮点。[exact] 由调用方从同一次配置快照传入，避免同一次换算里混用两份快照。 */
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
     * 正向：滑条 gamma → 背光浮点。
     *
     * 性能注意：只在“需要让原逻辑跑”时才调 `chain.proceed()`。能用我们的曲线给出结果时
     * 直接返回，省掉原方法的那次计算（拖滑条时每帧都会调进来）。
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
