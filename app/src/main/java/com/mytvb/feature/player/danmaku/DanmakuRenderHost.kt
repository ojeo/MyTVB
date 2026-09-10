package com.mytvb.feature.player.danmaku

import android.view.View
import com.mytvb.feature.player.danmaku.clock.DanmakuClockMode

/**
 * 弹幕渲染宿主抽象：当前仅 GPU 引擎（render.GlDanmakuView）实现。
 * 供 [BlblDanmakuController] 以引擎中立方式驱动。
 *
 * Canvas 引擎已于 1.6.16+ 移除（仅保留 GPU）。同目录下的 `DanmakuView` / `DanmakuPlayer`
 * 是上游仍在维护的旧 Canvas 引擎源码，**本项目无任何调用方、不参与运行**，保留仅为与
 * upstream 保持文件对齐（否则每次同步上游都会产生 modify/delete 冲突）。运行期唯一生效
 * 的引擎是 `render.GlDanmakuView`（见 `MyPlayerView` 中的构造点），不要再引用旧引擎。
 */
interface DanmakuRenderHost {
    /** 实际 View，用于 visibility / invalidate / 布局等 View 层操作。 */
    val renderView: View

    /** 应用弹幕开关对应的 View 可见性；GPU 后端可额外叠加视频首帧门禁。 */
    fun setRenderVisible(visible: Boolean) {
        renderView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 切换时钟模式（LEGACY / CONTINUOUS）。GPU 后端支持 CONTINUOUS。 */
    fun setDanmakuClockMode(mode: DanmakuClockMode) {
        // 默认 no-op（LEGACY）；GlDanmakuView 覆盖以支持 CONTINUOUS。
    }

    /** 当前生效的时钟模式。控制器据此决定是否退役自身漂移监督器。 */
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
