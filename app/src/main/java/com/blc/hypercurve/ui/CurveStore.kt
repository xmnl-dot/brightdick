package com.blc.hypercurve.ui

import android.content.Context
import com.blc.hypercurve.core.Config

/** 编辑器状态，和远程偏好一一对应。 */
data class EditorState(
    val enabled: Boolean,
    val mode: Int,
    val pointsText: String,
    val capCode: Int,
    /** 抬高手动最大上限（码值，0 = 不动，跟随系统）。 */
    val manualMaxCode: Int,
    val verbose: Boolean,
    val splineText: String,
)

/**
 * 本地镜像存储：编辑中的值先存本地，点「保存并下推」才写远程偏好。
 * 这样即使 LSPosed service 未连接（模块没启用），界面也不会丢编辑内容。
 */
object CurveStore {

    private const val FILE = "hypercurve_local"

    fun load(context: Context): EditorState {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return EditorState(
            enabled = p.getBoolean(Config.KEY_ENABLED, false),
            mode = p.getInt(Config.KEY_MODE, Config.MODE_EXACT),
            pointsText = p.getString(Config.KEY_POINTS, Config.DEFAULT_POINTS) ?: Config.DEFAULT_POINTS,
            capCode = p.getInt(Config.KEY_CAP, 0),
            manualMaxCode = p.getInt(Config.KEY_MANUAL_MAX, Config.DEFAULT_MANUAL_MAX),
            verbose = p.getBoolean(Config.KEY_VERBOSE, false),
            splineText = p.getString(Config.KEY_SPLINE, Config.DEFAULT_SPLINE) ?: Config.DEFAULT_SPLINE,
        )
    }

    fun save(context: Context, state: EditorState) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(Config.KEY_ENABLED, state.enabled)
            .putInt(Config.KEY_MODE, state.mode)
            .putString(Config.KEY_POINTS, state.pointsText)
            .putInt(Config.KEY_CAP, state.capCode)
            .putInt(Config.KEY_MANUAL_MAX, state.manualMaxCode)
            .putBoolean(Config.KEY_VERBOSE, state.verbose)
            .putString(Config.KEY_SPLINE, state.splineText)
            .apply()
    }
}
