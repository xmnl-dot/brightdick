package com.blc.hypercurve.ui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.blc.hypercurve.core.Config
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 UI 与 LSPosed 框架的桥：远程偏好写入、作用域、已注入进程查询。
 *
 * 远程偏好两侧共享：保存后钩子侧最多 800ms 自行读到新配置。
 */
object LspBridge {

    private val main = Handler(Looper.getMainLooper())

    /** 框架 service 连接状态。listener 会在框架重启/解绑时再次回调。 */
    fun listen(onBind: (XposedService) -> Unit, onDied: (XposedService) -> Unit) {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                main.post { onBind(service) }
            }

            override fun onServiceDied(service: XposedService) {
                main.post { onDied(service) }
            }
        })
    }

    fun remotePrefs(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(Config.PREFS_GROUP) }.getOrNull()

    /**
     * 把曲线配置下推给钩子侧（框架侧只读，钩子每次调用节流重读）。
     *
     * 不含 `spline`：nit 样条只供界面「换算预览」使用。
     */
    fun pushConfig(
        service: XposedService?,
        enabled: Boolean,
        mode: Int,
        points: String,
        capCode: Int,
        manualMaxCode: Int,
        verbose: Boolean,
    ): Boolean {
        val prefs = remotePrefs(service) ?: return false
        return runCatching {
            prefs.edit()
                .putBoolean(Config.KEY_ENABLED, enabled)
                .putInt(Config.KEY_MODE, mode)
                .putString(Config.KEY_POINTS, points)
                .putInt(Config.KEY_CAP, capCode)
                .putInt(Config.KEY_MANUAL_MAX, manualMaxCode)
                .putBoolean(Config.KEY_VERBOSE, verbose)
                .putLong(Config.KEY_REV, System.currentTimeMillis())
                .apply()
            true
        }.getOrDefault(false)
    }

    fun targets(service: XposedService?): List<HookedTarget> =
        runCatching { service?.runningTargets.orEmpty() }.getOrDefault(emptyList())

    fun scopeOf(service: XposedService?): List<String> =
        runCatching { service?.scope.orEmpty() }.getOrDefault(emptyList())

    /** 请求把 `system` 与 `com.android.systemui` 加入模块作用域（需要用户在 LSPosed 弹窗里确认）。 */
    fun requestSystemScope(service: XposedService?, onMessage: (String, Boolean) -> Unit) {
        val impl = service ?: return
        runCatching {
            impl.requestScope(listOf("system", "com.android.systemui"), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    main.post { onMessage("已加入作用域：${approved.joinToString()}", true) }
                }

                override fun onScopeRequestFailed(message: String) {
                    main.post { onMessage(message, false) }
                }
            })
        }.onFailure { main.post { onMessage(it.message ?: "请求作用域失败", false) } }
    }
}
