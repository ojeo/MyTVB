package com.mytvb.feature.player.danmaku.common

import com.mytvb.feature.player.DanmakuFilterContext
import com.mytvb.model.dm.DmModel
import java.io.Serializable
import kotlin.math.roundToInt

internal interface DanmakuController {
    fun setData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext,
        startupTraceId: String,
        startupTraceStartElapsedMs: Long,
    )

    fun appendData(data: List<DmModel>, filterContext: DanmakuFilterContext)
    fun applySettings(snapshot: DanmakuSettingsSnapshot)
    fun updatePlaybackSpeed(speed: Float)
    fun notifyPlaybackStateChanged(playbackState: Int, playWhenReady: Boolean)
    fun notifyIsPlayingChanged(isPlaying: Boolean)
    fun notifyPlaybackFirstFrame()
    fun setEnabled(enabled: Boolean)
    fun pause()
    fun resume()
    fun resetForPlaybackStart(positionMs: Long)
    fun stop()
    fun syncPosition(positionMs: Long, forceSeek: Boolean)
    fun release()
}

internal interface LiveDanmakuController {
    fun startLive()
    fun addLiveDanmaku(dm: DmModel)

    /**
     * 批量注入直播弹幕（洪峰路径）。默认逐条转发，GPU 控制器覆写为"整批只做一次
     * 限流/密度预算"，把主线程上的细碎调用次数按批聚合比例降下来。
     */
    fun addLiveDanmakus(items: List<DmModel>) {
        for (item in items) addLiveDanmaku(item)
    }
}

/** Protocol modes understood by both engines and by engine-neutral filtering. */
internal object DanmakuProtocolMode {
    const val ROLLING = 1
    const val CENTER_BOTTOM = 4
    const val CENTER_TOP = 5
    const val ADVANCED = 7
    const val SCRIPT = 9
}

/** Engine-neutral settings consumed by both danmaku implementations. */
data class DanmakuSettingsSnapshot(
    val enabled: Boolean,
    val alpha: Float,
    val textSize: Int,
    val speed: Int,
    val screenArea: Int,
    val allowTop: Boolean,
    val allowBottom: Boolean,
    val smartFilterLevel: Int,
    val mergeDuplicate: Boolean,
    val trackSpacing: String = "standard",
)

/** VIP gradient resources shared by both renderers. */
data class DanmakuVipGradientStyle(
    val fillTextureUrl: String = "",
    val strokeTextureUrl: String = "",
) : Serializable {
    val hasTexture: Boolean
        get() = fillTextureUrl.isNotBlank() || strokeTextureUrl.isNotBlank()

    val textureKey: String
        get() = "$fillTextureUrl#$strokeTextureUrl"

    companion object {
        val NONE = DanmakuVipGradientStyle()
    }
}

internal fun nextDanmakuPreparationGeneration(current: Long, replace: Boolean): Long =
    if (replace) current + 1L else current

/**
 * 设置项 screenArea → 弹幕占用屏幕纵向比例（0-1）。
 * 映射沿用已移除功能向引擎的 `toDanmakuScreenPart`，现由引擎 area 与 GPU 直播 band 共用，
 * 避免两处各写一份 `when` 后失配（失配会导致 band 高度与引擎可用区域不一致，
 * 表现为弹幕超出/挤在 band 内一部分）。
 */
internal fun danmakuAreaFraction(screenArea: Int): Float = when (screenArea) {
    -1 -> 1f / 8f
    0 -> 0.16f
    1 -> 1f / 4f
    3 -> 1f / 2f
    7 -> 3f / 4f
    else -> 1f
}

/** band 模式允许的最小占屏高比例（再小就不足以容纳一行弹幕）。 */
internal const val MIN_DANMAKU_BAND_FRACTION = 1f / 8f

/**
 * GPU 直播 band 模式下的 View / Surface 高度（px）。
 *
 * [fraction] ≥ 1 表示关闭 band 模式，返回整屏高度（行为与改造前一致）。
 */
internal fun danmakuBandHeightPx(availableHeightPx: Int, fraction: Float): Int {
    val clamped = fraction.coerceIn(MIN_DANMAKU_BAND_FRACTION, 1f)
    if (clamped >= 1f) return availableHeightPx.coerceAtLeast(0)
    return (availableHeightPx * clamped).roundToInt().coerceAtLeast(1)
}
