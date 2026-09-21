package com.blc.hypercurve.core

import kotlin.math.min

/**
 * 纯计算，无 Android 依赖，UI 与 system_server 两侧共用。
 */
object CurveMath {

    /** 折线点：滑条百分比 -> 期望背光码值。 */
    data class CurvePoint(val percent: Float, val code: Float)

    /** 解析 "0:4,50:1500,100:5000"；点数不足或百分比非递增时返回 null。 */
    fun parsePoints(spec: String?): List<CurvePoint>? {
        if (spec.isNullOrBlank()) return null
        val points = spec.split(',', ';', '\n')
            .mapNotNull { chunk ->
                val parts = chunk.trim().split(':', '=')
                if (parts.size != 2) return@mapNotNull null
                val pct = parts[0].trim().toFloatOrNull() ?: return@mapNotNull null
                val code = parts[1].trim().toFloatOrNull() ?: return@mapNotNull null
                CurvePoint(pct.coerceIn(0f, 100f), code.coerceIn(0f, Config.MAX_CODE))
            }
            .sortedBy { it.percent }
            .distinctBy { it.percent }
        if (points.size < 2) return null
        return points
    }

    fun format(points: List<CurvePoint>): String =
        points.joinToString(",") { "${it.percent.toInt()}:${it.code.toInt()}" }

    /** 分段线性查码值。 */
    fun codeAt(points: List<CurvePoint>, percent: Float): Float {
        val p = percent.coerceIn(0f, 100f)
        if (p <= points.first().percent) return points.first().code
        if (p >= points.last().percent) return points.last().code
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (p <= b.percent) {
                val t = (p - a.percent) / (b.percent - a.percent)
                return a.code + (b.code - a.code) * t
            }
        }
        return points.last().code
    }

    /** 分段线性反查：给定码值求百分比（[codeAt] 的逆）。码值非递增的段退化为取左端百分比。 */
    fun percentAt(points: List<CurvePoint>, code: Float): Float {
        if (code <= points.first().code) return points.first().percent
        if (code >= points.last().code) return points.last().percent
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (code <= b.code) {
                if (b.code <= a.code) return a.percent
                val t = (code - a.code) / (b.code - a.code)
                return a.percent + (b.percent - a.percent) * t
            }
        }
        return points.last().percent
    }

    /** cap <= 0 表示不限。 */
    fun applyCap(code: Float, cap: Float): Float = if (cap <= 0f) code else min(code, cap)

    /** 单调递增的线性样条：存的是 (nit, backlight) 点对，[xAt] 用背光反查 nit。 */
    class Spline(val pairs: List<Pair<Float, Float>>) {

        fun xAt(y: Float): Float {
            if (pairs.isEmpty()) return Float.NaN
            if (y <= pairs.first().second) return pairs.first().first
            if (y >= pairs.last().second) return pairs.last().first
            for (i in 0 until pairs.size - 1) {
                val (x0, y0) = pairs[i]
                val (x1, y1) = pairs[i + 1]
                if (y <= y1) return x0 + (x1 - x0) * (y - y0) / (y1 - y0)
            }
            return pairs.last().first
        }

        companion object {
            /** 解析 "1|0.000244;60|0.0371;800|0.412"。 */
            fun parse(spec: String?): Spline? {
                if (spec.isNullOrBlank()) return null
                val pairs = spec.split(';', '\n').mapNotNull { chunk ->
                    val parts = chunk.trim().split('|', ',')
                    if (parts.size != 2) return@mapNotNull null
                    val x = parts[0].trim().toFloatOrNull() ?: return@mapNotNull null
                    val y = parts[1].trim().toFloatOrNull() ?: return@mapNotNull null
                    x to y
                }.sortedBy { it.first }
                if (pairs.size < 2) return null
                return Spline(pairs)
            }
        }
    }
}
