package com.tutu.myblbl.feature.player.danmaku

import android.view.View
import com.tutu.myblbl.feature.player.danmaku.clock.DanmakuClockMode

/**
 * 弹幕渲染宿主抽象：Canvas 引擎（[DanmakuView]）与 GPU 引擎（render.GlDanmakuView）
 * 共用同一套控制入口，供 [BlblDanmakuController] 以引擎中立方式驱动。
 *
 * 设计目标：上层（播放器/控制器）不感知渲染后端，GPU 引擎默认、Canvas 引擎可回退。
 */
interface DanmakuRenderHost {
    /** 实际 View，用于 visibility / invalidate / 布局等 View 层操作。 */
    val renderView: View

    /** 应用弹幕开关对应的 View 可见性；GPU 后端可额外叠加视频首帧门禁。 */
    fun setRenderVisible(visible: Boolean) {
        renderView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 切换时钟模式（A/B 实验，P1）。
     * 默认空实现：Canvas 后端暂不支持 NEW_CLOCK（P7 接入），保持 LEGACY 行为；
     * GPU 后端覆盖为 LEGACY/CONTINUOUS 可切换。
     */
    fun setDanmakuClockMode(mode: DanmakuClockMode) {
        // Canvas：no-op，保持 DanmakuTimer LEGACY 路径。
    }

    /**
     * 当前生效的时钟模式。默认 [DanmakuClockMode.LEGACY]；
     * 控制器据此决定是否退役自身漂移监督器（CONTINUOUS 下治理权在 ClockSynchronizer，P4）。
     */
    fun currentDanmakuClockMode(): DanmakuClockMode = DanmakuClockMode.LEGACY

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
