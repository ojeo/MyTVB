package com.mytvb.feature.player.danmaku.render

import android.view.View
import com.mytvb.feature.player.danmaku.Danmaku
import com.mytvb.feature.player.danmaku.DanmakuConfig
import com.mytvb.feature.player.danmaku.DanmakuRenderHost
import com.mytvb.feature.player.danmaku.DanmakuView

/**
 * 轻量化（CPU Canvas）弹幕引擎的 [DanmakuRenderHost] 实现：薄包装上游 [DanmakuView]。
 *
 * 存在的意义：让 [com.mytvb.feature.player.danmaku.BlblDanmakuController] 以引擎中立
 * 的方式驱动旧 Canvas 引擎，从而支持「设置-弹幕-弹幕渲染引擎」切换到轻量化引擎。
 *
 * 与 GPU 引擎的能力差异（故意不支持，非遗漏）：
 * - 时钟恒为 LEGACY：不覆盖 [setDanmakuClockMode]，[currentDanmakuClockMode] 返回接口默认的
 *   `DanmakuClockMode.LEGACY`，控制器据此保留自身的漂移监督器（见 BlblDanmakuController）。
 * - 无防挡蒙版共享 path 通道：`maskHostProvider` 是 GPU 专属，Canvas 视图作为普通子 View
 *   由 [com.mytvb.feature.player.view.DanmakuMaskHostLayout] 的 View 层裁剪决定。
 * - 无 EGL 失败降级概念：Canvas 渲染不依赖 EGL。
 *
 * 生命周期完全由 View 的 attach/detach 驱动（[DanmakuView] 内部 start/release），
 * 注意 detach 后引擎**永久 release**，同实例重新 attach 不会恢复——切换引擎必须新建实例。
 */
class LiteDanmakuRenderHost(
    private val view: DanmakuView,
) : DanmakuRenderHost {

    override val renderView: View get() = view

    override fun setPositionProvider(provider: () -> Long) {
        view.setPositionProvider(provider)
    }

    override fun setIsPlayingProvider(provider: () -> Boolean) {
        view.setIsPlayingProvider(provider)
    }

    override fun setPlayWhenReadyProvider(provider: () -> Boolean) {
        view.setPlayWhenReadyProvider(provider)
    }

    override fun setPlaybackSpeedProvider(provider: () -> Float) {
        view.setPlaybackSpeedProvider(provider)
    }

    override fun setConfigProvider(provider: () -> DanmakuConfig) {
        view.setConfigProvider(provider)
    }

    override fun setDanmakus(list: List<Danmaku>) {
        view.setDanmakus(list)
    }

    override fun appendDanmakus(list: List<Danmaku>, maxItems: Int, alreadySorted: Boolean) {
        view.appendDanmakus(list, maxItems, alreadySorted)
    }

    override fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>) {
        view.replaceDanmakusFrom(minTimeMs, list)
    }

    override fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        view.trimToTimeRange(minTimeMs, maxTimeMs)
    }

    override fun notifySeek(positionMs: Long) {
        view.notifySeek(positionMs)
    }

    override fun updateDanmakuTimeFactor(factor: Float) {
        view.updateDanmakuTimeFactor(factor)
    }

    override fun currentDanmakuPositionMs(): Long = view.currentDanmakuPositionMs()

    override fun syncDanmakuTimerTo(positionMs: Long) {
        view.syncDanmakuTimerTo(positionMs)
    }

    override fun setDebugEnabled(enabled: Boolean) {
        view.setDebugEnabled(enabled)
    }
}
