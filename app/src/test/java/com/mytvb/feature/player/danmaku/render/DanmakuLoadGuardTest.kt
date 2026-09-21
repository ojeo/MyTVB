package com.mytvb.feature.player.danmaku.render

import com.mytvb.feature.player.danmaku.render.DanmakuLoadLevel.NORMAL
import com.mytvb.feature.player.danmaku.render.DanmakuLoadLevel.REDUCED
import com.mytvb.feature.player.danmaku.render.DanmakuLoadLevel.SUSPENDED
import com.mytvb.feature.player.danmaku.render.DanmakuLoadLevel.THROTTLED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保播放降载判定器的行为回归：
 * - 参考帧率取前两个有效窗口的较小值（挡起播/seek 的瞬时高速刷帧）；
 * - 帧率跌破参考 88% → 立即降一档（确认窗口 1 → 3s 响应），逐档降到 SUSPENDED 后不再变化；
 * - 恢复需连续 3 个 ≥ 参考 97% 的窗口，中间为死区（防振荡）；
 * - 暂停/缓冲、无效帧率不判定；
 * - 参考帧率向上单窗口最多 +10%，向下只做极慢跟随。
 */
class DanmakuLoadGuardTest {

    private companion object {
        /** 典型 60fps 内容：参考 60，劣化到 50（0.83）触发降档。 */
        const val BASELINE = 60f
    }

    /** 建立参考帧率：前两个有效窗口不判定。 */
    private fun establish(guard: DanmakuLoadGuard, fps: Float = BASELINE) {
        assertNull(guard.onWindow(videoFps = fps, isPlaying = true))
        assertNull(guard.onWindow(videoFps = fps, isPlaying = true))
        assertEquals(fps, guard.baselineFps, 0.01f)
    }

    @Test
    fun firstTwoWindowsEstablishBaselineAndDoNotJudge() {
        val g = DanmakuLoadGuard()
        assertNull(g.onWindow(videoFps = BASELINE, isPlaying = true))
        assertEquals(0f, g.baselineFps, 0.01f)
        assertNull(g.onWindow(videoFps = BASELINE, isPlaying = true))
        assertEquals(BASELINE, g.baselineFps, 0.01f)
        assertEquals(NORMAL, g.level)
    }

    @Test
    fun baselineUsesSmallerOfFirstTwoWindowsToIgnoreSpike() {
        val g = DanmakuLoadGuard()
        // 第一个窗口是 seek/起播的瞬时高速刷帧（120fps），第二个是真实内容帧率（24fps）：
        // 参考必须取较小值，否则随后的正常帧率会被误判为"劣化到 20%"
        g.onWindow(videoFps = 120f, isPlaying = true)
        g.onWindow(videoFps = 24f, isPlaying = true)
        assertEquals(24f, g.baselineFps, 0.01f)
    }

    @Test
    fun degradedFpsDowngradesImmediately() {
        val g = DanmakuLoadGuard()
        establish(g)
        // 50/59.8 ≈ 0.836 < 0.88：降档确认窗口为 1，本窗口即降档
        assertEquals(THROTTLED, g.onWindow(videoFps = 50f, isPlaying = true))
    }

    @Test
    fun spikeAfterBaselineDoesNotCauseFalseDegrade() {
        val g = DanmakuLoadGuard()
        // 24fps 内容建立参考
        g.onWindow(videoFps = 24f, isPlaying = true)
        g.onWindow(videoFps = 24f, isPlaying = true)
        assertEquals(24f, g.baselineFps, 0.01f)

        // seek 造成一个 120fps 的虚高窗口：参考只能抬到 24×1.10，不能跳到 120
        assertNull(g.onWindow(videoFps = 120f, isPlaying = true))
        assertTrue("参考被尖峰抬高过多 baseline=${g.baselineFps}", g.baselineFps < 27f)

        // 回到正常的 24fps：不得被判定为劣化
        assertNull(g.onWindow(videoFps = 24f, isPlaying = true))
        assertEquals(NORMAL, g.level)
    }

    @Test
    fun baselineClimbsGraduallyWhenDeviceCapabilityIsHigher() {
        val g = DanmakuLoadGuard()
        // 起播偏低时建立的参考，遇到持续更高的帧率应逐步抬升（几个窗口内追平）
        g.onWindow(videoFps = 30f, isPlaying = true)
        g.onWindow(videoFps = 30f, isPlaying = true)
        repeat(4) { g.onWindow(videoFps = 60f, isPlaying = true) }
        assertTrue("参考未随持续高帧率抬升 baseline=${g.baselineFps}", g.baselineFps >= 42f)
        // 抬升过程不得触发降档
        assertEquals(NORMAL, g.level)
    }

    @Test
    fun keepsSteppingDownUntilSuspendedThenHolds() {
        val g = DanmakuLoadGuard()
        establish(g)
        assertEquals(THROTTLED, g.onWindow(videoFps = 50f, isPlaying = true))
        assertEquals(REDUCED, g.onWindow(videoFps = 50f, isPlaying = true))
        assertEquals(SUSPENDED, g.onWindow(videoFps = 50f, isPlaying = true))
        // 已到最后一档：不再产生变化
        assertNull(g.onWindow(videoFps = 50f, isPlaying = true))
        assertEquals(SUSPENDED, g.level)
    }

    @Test
    fun requiresThreeHealthyWindowsToRecover() {
        val g = DanmakuLoadGuard()
        establish(g)
        assertEquals(THROTTLED, g.onWindow(videoFps = 50f, isPlaying = true))
        // 恢复线 0.97：连续 3 个健康窗口才还一档
        assertNull(g.onWindow(videoFps = BASELINE, isPlaying = true))
        assertNull(g.onWindow(videoFps = BASELINE, isPlaying = true))
        assertEquals(NORMAL, g.onWindow(videoFps = BASELINE, isPlaying = true))
    }

    @Test
    fun deadBandBetweenRatiosHoldsLevel() {
        val g = DanmakuLoadGuard()
        establish(g)
        assertEquals(THROTTLED, g.onWindow(videoFps = 50f, isPlaying = true))
        // 0.95 倍参考：既不触发劣化（<0.88）也不满足恢复（≥0.97）→ 死区保持
        repeat(4) { assertNull(g.onWindow(videoFps = 57f, isPlaying = true)) }
        assertEquals(THROTTLED, g.level)
    }

    @Test
    fun pausedWindowsDoNotJudge() {
        val g = DanmakuLoadGuard()
        establish(g)
        // 缓冲/暂停时视频本来就不出帧，不能据此判定"被弹幕拖累"
        repeat(4) { assertNull(g.onWindow(videoFps = 20f, isPlaying = false)) }
        assertEquals(NORMAL, g.level)
    }

    @Test
    fun invalidFpsDoesNotJudge() {
        val g = DanmakuLoadGuard()
        establish(g)
        assertNull(g.onWindow(videoFps = 0f, isPlaying = true))
        assertNull(g.onWindow(videoFps = 2f, isPlaying = true))
        assertNull(g.onWindow(videoFps = Float.NaN, isPlaying = true))
        assertNull(g.onWindow(videoFps = Float.POSITIVE_INFINITY, isPlaying = true))
        assertEquals(NORMAL, g.level)
    }

    @Test
    fun resetRestoresFullSpeedAndClearsBaseline() {
        val g = DanmakuLoadGuard()
        establish(g)
        assertEquals(THROTTLED, g.onWindow(videoFps = 50f, isPlaying = true))

        g.reset()
        assertEquals(NORMAL, g.level)
        assertEquals(0f, g.baselineFps, 0.001f)
        // 重置后重新走"两个窗口建立参考"的流程，期间不判定
        assertNull(g.onWindow(videoFps = 45f, isPlaying = true))
        assertNull(g.onWindow(videoFps = 45f, isPlaying = true))
        assertEquals(45f, g.baselineFps, 0.01f)
    }

    @Test
    fun loadLevelsAreMonotonicInLoad() {
        // 档位语义：越靠后负载越低，最后一档整层停渲染
        assertEquals(60, NORMAL.renderFps)
        assertEquals(1.0f, NORMAL.onScreenScale, 0.001f)
        assertEquals(1.0f, NORMAL.renderScaleFactor, 0.001f)
        assertFalse(NORMAL.suspendLayer)
        assertFalse(NORMAL.lowPriorityCache)

        assertEquals(30, THROTTLED.renderFps)
        assertEquals(30, REDUCED.renderFps)
        assertEquals(30, SUSPENDED.renderFps)

        assertTrue(THROTTLED.onScreenScale > REDUCED.onScreenScale)
        assertTrue(REDUCED.onScreenScale > SUSPENDED.onScreenScale)
        assertTrue(REDUCED.renderScaleFactor < NORMAL.renderScaleFactor)
        assertTrue(SUSPENDED.renderScaleFactor <= REDUCED.renderScaleFactor)
        assertTrue(SUSPENDED.suspendLayer)

        // 建图线程退让只出现在重档位：轻档位保持默认优先级（BACKGROUND 会让建图饥饿）
        assertFalse(THROTTLED.lowPriorityCache)
        assertTrue(REDUCED.lowPriorityCache)
        assertTrue(SUSPENDED.lowPriorityCache)
    }
}
