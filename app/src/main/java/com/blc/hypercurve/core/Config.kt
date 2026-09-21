package com.blc.hypercurve.core

/**
 * 模块 UI 与 system_server hook 共用的配置定义。
 *
 * 传输通道：libxposed 的 remote preferences（框架侧只读，模块 UI 通过 XposedService 写入），
 * 因此不需要 root、也不需要 Settings.Global 自定义 key。
 */
object Config {

    /** remote preferences 分组名，两侧必须一致。 */
    const val PREFS_GROUP = "hypercurve"

    const val KEY_ENABLED = "enabled"
    const val KEY_MODE = "mode"
    const val KEY_POINTS = "points"
    const val KEY_CAP = "cap_code"
    const val KEY_MANUAL_MAX = "manual_max_code"
    const val KEY_VERBOSE = "verbose"
    const val KEY_SPLINE = "spline"

    /** 配置版本号：UI 每次下推都会更新它，钩子侧靠它跳过无变化的重复解析。 */
    const val KEY_REV = "rev"

    /**
     * 抬高手动最大上限（背光码值，0 = 不动、跟随系统）。
     *
     * 与 [KEY_CAP] 的区别：cap 是「把请求夹住」（限高）；本项是「把系统天花板抬起来」（解锁），
     * 两者可以同时用：先把系统上限抬到 X，再用 cap 把请求夹到 ≤X。
     */
    const val DEFAULT_MANUAL_MAX = 0

    /** 本机系统默认手动上限：普通 0.3165（≈5185）※阳光模式 0.411977（≈6750）。 */
    const val MANUAL_MAX_HINT = "系统默认：普通≈5185 / 阳光模式≈6750（本机实测）"

    /** 0：跟随系统上下限 —— 把结果压进系统当时的 [min, max] 区间，阳光模式/热控上限仍然生效。 */
    const val MODE_CURVE = 0

    /** 1：滑条百分比严格等于码值 —— 直接给出背光浮点（码值/满量程），忽略系统上下限。 */
    const val MODE_EXACT = 1

    /** 面板背光满量程（/sys/class/backlight/panel0-backlight/max_brightness）。 */
    const val MAX_CODE = 16383f

    /**
     * 默认 nit <-> backlight 样条：来自本机 `dumpsys display` 的
     * mNits / mBacklight（等同 /vendor/etc/displayconfig/display_id_*.xml）。
     * 运行时会尝试从 DisplayDeviceConfig 反射读取真值，失败才用这份。
     */
    const val DEFAULT_SPLINE = "1|0.000244171;60|0.037113905;800|0.41197655;2000|0.8240142;3500|1.0"

    /** 「还原系统」预设：满量程线性等分，等价于不改变观感但仍受本模块管理。 */
    const val PRESET_SYSTEM_LIKE = "0:4,50:2600,100:8212"

    /** 低段提亮预设（对应研究结论：50% -> 1500、100% -> 5000）。 */
    const val PRESET_LOW_BOOST = "0:4,10:120,25:560,50:1500,75:3300,100:5000"

    const val DEFAULT_POINTS = PRESET_LOW_BOOST
}
