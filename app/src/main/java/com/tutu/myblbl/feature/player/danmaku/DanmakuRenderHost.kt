package com.tutu.myblbl.feature.player.danmaku

import android.view.View

/**
 * 弹幕渲染宿主抽象：Canvas 引擎（[DanmakuView]）与 GPU 引擎（render.GlDanmakuView）
 * 共用同一套控制入口，供 [BlblDanmakuController] 以引擎中立方式驱动。
 *
 * 设计目标：上层（播放器/控制器）不感知渲染后端，GPU 引擎默认、Canvas 引擎可回退。
 */
interface DanmakuRenderHost {
    /** 实际 View，用于 visibility / invalidate / 布局等 View 层操作。 */
    val renderView: View

    fun setPositionProvider(provider: () -> Long)

    fun setIsPlayingProvider(provider: () -> Boolean)

    fun setPlayWhenReadyProvider(provider: () -> Boolean)

    fun setPlaybackSpeedProvider(provider: () -> Float)

    fun setConfigProvider(provider: () -> DanmakuConfig)

    fun setDanmakus(list: List<Danmaku>)

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false)

    fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>)

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long)

    fun notifySeek(positionMs: Long)

    fun updateDanmakuTimeFactor(factor: Float)

    fun currentDanmakuPositionMs(): Long

    fun syncDanmakuTimerTo(positionMs: Long)

    fun setDebugEnabled(enabled: Boolean)
}

/** 弹幕渲染后端模式：GPU（默认）/ Canvas（回退）。 */
enum class DanmakuRenderMode {
    GL,
    CANVAS,
}
