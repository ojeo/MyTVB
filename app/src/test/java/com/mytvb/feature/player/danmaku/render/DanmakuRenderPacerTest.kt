package com.mytvb.feature.player.danmaku.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕渲染帧率节流器的行为回归。
 *
 * 验收口径：
 * - 60fps 目标下，60Hz（16.67ms）与 59.94Hz（16.68ms）都要全量放行——不能被容差误杀；
 * - 30fps 目标下，60Hz 面板每两帧只放行一帧（提交次数减半，这是减合成带宽的核心）；
 * - 120Hz 面板 + 60fps 目标同样只放行一半；
 * - 目标帧率可运行时切换，不出现"切换后卡住不放行"。
 */
class DanmakuRenderPacerTest {

    private companion object {
        /** 60Hz 面板的 vsync 间隔。 */
        const val VSYNC_60HZ_NS = 16_666_667L

        /** 59.94Hz（NTSC）面板的 vsync 间隔。 */
        const val VSYNC_59_94HZ_NS = 16_683_333L

        /** 120Hz 面板的 vsync 间隔。 */
        const val VSYNC_120HZ_NS = 8_333_333L
    }

    /** 以固定 vsync 间隔走 [frames] 帧，返回实际提交（放行）次数。 */
    private fun countRendered(pacer: DanmakuRenderPacer, intervalNs: Long, frames: Int): Int {
        var rendered = 0
        var t = 0L
        repeat(frames) {
            if (pacer.shouldRender(t)) rendered++
            t += intervalNs
        }
        return rendered
    }

    @Test
    fun sixtyFpsTargetPassesEveryFrameOn60HzPanel() {
        val pacer = DanmakuRenderPacer()
        assertEquals(60, pacer.targetFps)
        assertEquals(60, countRendered(pacer, VSYNC_60HZ_NS, frames = 60))
    }

    @Test
    fun sixtyFpsTargetDoesNotDropFramesOn59_94HzDisplay() {
        val pacer = DanmakuRenderPacer()
        // 容差必须吸收 59.94Hz 的微小抖动，否则会周期性丢帧（弹幕"一卡一卡"）
        assertEquals(60, countRendered(pacer, VSYNC_59_94HZ_NS, frames = 60))
    }

    @Test
    fun thirtyFpsTargetHalvesSubmissionsOn60HzPanel() {
        val pacer = DanmakuRenderPacer()
        pacer.targetFps = 30
        assertEquals(30, countRendered(pacer, VSYNC_60HZ_NS, frames = 60))
    }

    @Test
    fun thirtyFpsTargetOn50HzPanelRendersAbout25Fps() {
        val pacer = DanmakuRenderPacer()
        pacer.targetFps = 30
        // 50Hz 下 20ms 间隔：0 / 40 / 80…每两帧一次 → 25fps
        val rendered = countRendered(pacer, 20_000_000L, frames = 50)
        assertEquals(25, rendered)
    }

    @Test
    fun sixtyFpsTargetHalvesSubmissionsOn120HzPanel() {
        val pacer = DanmakuRenderPacer()
        assertEquals(30, countRendered(pacer, VSYNC_120HZ_NS, frames = 60))
    }

    @Test
    fun targetFpsCanBeRaisedAtRuntime() {
        val pacer = DanmakuRenderPacer()
        pacer.targetFps = 30
        assertEquals(30, countRendered(pacer, VSYNC_60HZ_NS, frames = 60))

        // 恢复到 60fps 后必须立刻恢复满速，不能因为上一次节拍残留而卡住
        pacer.targetFps = 60
        assertEquals(60, countRendered(pacer, VSYNC_60HZ_NS, frames = 60))
    }

    @Test
    fun outOfRangeTargetFpsIsClamped() {
        val low = DanmakuRenderPacer().apply { targetFps = 0 }
        // 下限 15fps：仍必须持续放行（不能变成永不放行）
        assertTrue(countRendered(low, VSYNC_60HZ_NS, frames = 60) >= 15)

        val high = DanmakuRenderPacer().apply { targetFps = 1000 }
        // 上限 120fps：视为不节流，全量放行
        assertEquals(60, countRendered(high, VSYNC_60HZ_NS, frames = 60))
    }

    @Test
    fun resetReleasesNextFrameImmediately() {
        val pacer = DanmakuRenderPacer()
        pacer.targetFps = 30
        assertTrue(pacer.shouldRender(0L))
        // 同一时刻的近邻帧被跳掉（节流生效）
        assertEquals(false, pacer.shouldRender(1_000_000L))
        pacer.reset()
        // 重置后按新时间戳立即放行，避免长空闲后仍按陈旧节拍等待
        assertEquals(true, pacer.shouldRender(2_000_000L))
    }
}
