package com.blc.hypercurve.hook

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.blc.hypercurve.core.Config
import com.blc.hypercurve.core.CurveMath
import io.github.libxposed.api.XposedInterface
import kotlin.concurrent.Volatile

/**
 * hook 侧的运行时容器：框架接口、目标 ClassLoader、配置缓存。
 *
 * 只有一份实例，由 [HyperCurveEntry] 在 onModuleLoaded / onSystemServerStarting / onPackageReady 时填充。
 */
object HookRuntime {

    const val TAG = "HyperCurve"

    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    var classLoader: ClassLoader? = null

    /** 面板背光满量程，挂载时从 sysfs 读取。 */
    @Volatile
    var maxCode: Float = Config.MAX_CODE

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 找一个能看见目标类的 ClassLoader。
     *
     * MIUI 的类（DisplayPowerControllerImpl 等）在 system_ext/framework/miui-services.jar 里，
     * 只有 system_server 自己的 ClassLoader 能加载。优先用已记住的 loader（开机时由
     * SystemServerStartingParam / PackageReadyParam 提供），为空则按候选链逐个试探，
     * 谁成功就把它记住。
     */
    fun loadTargetClass(name: String): Class<*>? {
        for (loader in candidateLoaders()) {
            val found = runCatching { loader.loadClass(name) }.getOrNull()
            if (found != null) {
                if (classLoader == null) {
                    classLoader = loader
                    log(INFO, "目标类来自 ${label(loader)}（已记住该 ClassLoader）")
                }
                return found
            }
        }
        val tried = candidateLoaders().joinToString { label(it) }
        log(WARN, "所有候选 ClassLoader 都找不到 $name，试过：$tried")
        return null
    }

    private fun candidateLoaders(): List<ClassLoader> {
        val out = LinkedHashSet<ClassLoader>()
        classLoader?.let { out += it }
        // 模块 loader 的整条 parent 链（LSPosed 注入时通常把进程 loader 挂在链上）
        var l: ClassLoader? = api?.javaClass?.classLoader
        while (l != null) {
            out += l
            l = l.parent
        }
        Thread.currentThread().contextClassLoader?.let { out += it }
        runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let { out += it }
        // 应用进程（如 SystemUI）用当前 Application 的 ClassLoader。
        // ActivityThread 是 @hide（不在公开 android.jar 里），只能字符串反射。
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val application = activityThread.getMethod("currentApplication").invoke(null)
            (application as? android.content.Context)?.classLoader
        }.getOrNull()?.let { out += it }
        runCatching { Class.forName("android.app.ActivityThread").classLoader }.getOrNull()?.let { out += it }
        return out.toList()
    }

    private fun label(loader: ClassLoader): String =
        "${loader.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(loader))}"

    @Volatile
    var config: Snapshot = Snapshot()
        private set

    private var lastReadAt = 0L

    /** 上次读到的配置版本号，用来跳过无变化的重复解析。 */
    private var lastRev = 0L

    /** 配置重读间隔：拖滑条时钩子每帧都会调进来，节流到最多 800ms 一次。 */
    private const val REFRESH_INTERVAL_MS = 800L

    /** 一次读到的配置快照，避免每次亮度调用都去 IPC 取偏好。 */
    data class Snapshot(
        val enabled: Boolean = false,
        val mode: Int = Config.MODE_EXACT,
        val points: List<CurveMath.CurvePoint> = emptyList(),
        val cap: Float = 0f,
        val manualMax: Float = 0f,
        val verbose: Boolean = false,
    ) {
        val usable: Boolean get() = enabled && points.size >= 2
    }

    fun attach(interfaceRef: XposedInterface, loader: ClassLoader?) {
        api = interfaceRef
        if (loader != null) classLoader = loader
        prefs = runCatching { interfaceRef.getRemotePreferences(Config.PREFS_GROUP) }
            .onFailure { log(WARN, "remote preferences 不可用（框架无 REMOTE 能力？）: ${it.message}") }
            .getOrNull()
        refresh(force = true)
    }

    /**
     * 只保证框架接口与偏好通道就绪，不碰已记住的 ClassLoader（install() 的兜底入口）。
     */
    fun ensureApi(interfaceRef: XposedInterface) {
        if (api == null) api = interfaceRef
        if (prefs == null) {
            prefs = runCatching { interfaceRef.getRemotePreferences(Config.PREFS_GROUP) }.getOrNull()
        }
        refresh(force = true)
    }

    const val DEBUG = Log.DEBUG
    const val INFO = Log.INFO
    const val WARN = Log.WARN
    const val ERROR = Log.ERROR

    /**
     * 模块日志：一份交给框架（写入 /data/adb/lspd/log/），一份同步打 logcat（`adb logcat -s HyperCurve`）。
     */
    fun log(priority: Int, message: String, throwable: Throwable? = null) {
        val ref = api
        if (ref != null) {
            runCatching {
                if (throwable != null) ref.log(priority, TAG, message, throwable)
                else ref.log(priority, TAG, message)
            }
        }
        runCatching {
            if (throwable != null) Log.println(priority, TAG, "$message\n${Log.getStackTraceString(throwable)}")
            else Log.println(priority, TAG, message)
        }
    }

    /**
     * 详细日志（受「详细日志」开关控制）：内联 lambda 只在真要打时才构造字符串，被丢弃时零分配。
     */
    inline fun verbose(message: () -> String) {
        if (config.verbose) log(DEBUG, message())
    }

    /** 上次命中日志的时间戳（节流用）。 */
    @PublishedApi
    @Volatile
    internal var lastHitAt = 0L

    @PublishedApi
    internal const val HIT_INTERVAL_MS = 400L

    /**
     * 命中日志（受「详细日志」开关控制）：最多每 [HIT_INTERVAL_MS] 一条。
     */
    inline fun hit(message: () -> String) {
        if (!config.verbose) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHitAt < HIT_INTERVAL_MS) return
        lastHitAt = now
        log(INFO, message())
    }

    /**
     * 配置读取节流：亮度调用频率很高（拖拽时每帧），默认 800ms 内不重复取。
     */
    fun refresh(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastReadAt < REFRESH_INTERVAL_MS) return
        lastReadAt = now
        val p = prefs ?: return
        // 先只读一个版本号：没变就不必再去读 6 个键 + 解析折线（推送时会写这个键）
        val rev = runCatching { p.getLong(Config.KEY_REV, 0L) }.getOrDefault(0L)
        if (!force && rev != 0L && rev == lastRev) return
        val read = runCatching {
            Snapshot(
                enabled = p.getBoolean(Config.KEY_ENABLED, false),
                mode = p.getInt(Config.KEY_MODE, Config.MODE_EXACT),
                points = CurveMath.parsePoints(p.getString(Config.KEY_POINTS, Config.DEFAULT_POINTS))
                    ?: emptyList(),
                cap = p.getInt(Config.KEY_CAP, 0).toFloat(),
                manualMax = p.getInt(Config.KEY_MANUAL_MAX, Config.DEFAULT_MANUAL_MAX).toFloat(),
                verbose = p.getBoolean(Config.KEY_VERBOSE, false),
            )
        }.getOrElse {
            log(WARN, "读取配置失败，沿用上一次的快照: ${it.message}")
            return
        }
        lastRev = rev
        val previous = config
        val changed = previous.enabled != read.enabled || previous.mode != read.mode ||
            previous.points != read.points || previous.cap != read.cap ||
            previous.manualMax != read.manualMax || previous.verbose != read.verbose
        config = read
        if (changed) {
            log(
                INFO,
                "配置已更新 enabled=${read.enabled} mode=${read.mode} cap=${read.cap} " +
                    "手动最大=${read.manualMax} 点=${CurveMath.format(read.points)}"
            )
        }
    }
}

/**
 * 挂载钩子的统一入口：用显式 `object : XposedInterface.Hooker`，避免 `intercept { }` 的 SAM 转换
 * 生成 invokedynamic 合成类（类名不受 keep 约束，混淆后按接口方法名调用会失败）。
 */
fun XposedInterface.HookBuilder.hookerWith(
    id: String,
    body: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = setId(id).intercept(object : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = body(chain)
})

/**
 * 反射取方法（拿不到就返回 null）。`Float::class.javaPrimitiveType` 是 `Class<Float>?`，
 * 所以参数列表允许 null，统一 filterNotNull 后再传给 getDeclaredMethod。
 */
fun Class<*>.methodOrNull(name: String, vararg types: Class<*>?): java.lang.reflect.Method? {
    val params = types.filterNotNull().toTypedArray()
    return runCatching { getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()
}

/** 反射取字段并打开可访问性。 */
fun Class<*>.fieldOrNull(name: String): java.lang.reflect.Field? =
    runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
