package com.blc.hypercurve.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import kotlin.concurrent.Volatile

/**
 * 模块入口（libxposed API 102，类名登记在 META-INF/xposed/java_init.list）。
 *
 * 作用域：`system`（system_server）与 `com.android.systemui`（滑条换算在那一侧）。
 *
 * 不用热重载（module.prop 里也没开 `autoHotReload`）：本机实测热重载拿不到可用结果，
 * 而且它会让 system_server 重启（等于软重启）。配置改动走远程偏好，钩子每次调用都会读，
 * 保存后一秒内自动生效；只有**代码**更新才需要重启手机。
 */
@Suppress("unused")
class HyperCurveEntry : XposedModule() {

    private companion object {
        /** SystemUI 侧钩子所在进程（本机实测：滑条→背光的换算在这里）。 */
        const val PKG_SYSTEMUI = "com.android.systemui"
    }

    @Volatile
    private var hooksInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookRuntime.attach(this, HookRuntime.classLoader)
        HookRuntime.log(
            HookRuntime.INFO,
            "模块加载：process=${param.processName} systemServer=${param.isSystemServer} api=${apiVersion}"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (hooksInstalled) return
        synchronized(this) {
            if (hooksInstalled) return
            HookRuntime.classLoader = param.classLoader
            HookRuntime.attach(this, param.classLoader)
            hooksInstalled = BrightnessHooks.install(this) > 0
        }
    }

    /**
     * 作用域里有 `system`（system_server）与 `com.android.systemui` 两个进程：
     * 本机实测滑条→背光的换算在 SystemUI 侧完成，所以那一侧要单独挂钩子；
     * 其余进程直接忽略。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = runCatching { param.packageName }.getOrNull()
        if (pkg != PKG_SYSTEMUI) {
            HookRuntime.verbose { "忽略包 $pkg（本模块只处理 system_server 与 SystemUI）" }
            return
        }
        HookRuntime.classLoader = param.classLoader
        HookRuntime.attach(this, param.classLoader)
        val ok = SystemUiHooks.install(this)
        HookRuntime.log(HookRuntime.INFO, "SystemUI 侧钩子 $ok/2")
    }
}
