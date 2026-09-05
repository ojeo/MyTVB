package com.tutu.myblbl.feature.player.danmaku.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RenderClockDriver 行为回归（解耦方案 v0.2：Motion Clock 自由运行）。
 *
 * 核心契约：
 * - 播放状态边沿（onPlay / onPause / onResume）与倍速变化是**唯一**改写锚点的入口；
 * - **播放器位置抖动 / 漂移不影响运动时钟**（无周期治理、无 seek 重锚）；
 * - pause/buffering 冻结，恢复后从冻结值连续继续。
 *
 * 自亚毫秒精度改造后，[RenderClockDriver.advance] 返回纳秒（Long），
 * 断言值统一为纳秒单位。
 */
class RenderClockDriverTest {

    private val frameNs = 16_666_667L // 60Hz

    /** 构造「采样时刻 = 当前帧时刻」的播放样本：投影无增量，便于精确断言。 */
    private fun playingSample(
        mediaPositionNs: Long,
        frameTimeNs: Long,
        speed: Double = 1.0,
        sequence: Long = 1L,
    ): MediaClockSample = MediaClockSample(
        mediaPositionNs = mediaPositionNs,
        sampledAtRealtimeNs = frameTimeNs,
        playbackSpeed = speed,
        isPlaying = true,
        playbackState = 0,
        sequence = sequence,
    )

    private fun pausedSample(frameTimeNs: Long): MediaClockSample = MediaClockSample(
        mediaPositionNs = 0L,
        sampledAtRealtimeNs = frameTimeNs,
        playbackSpeed = 1.0,
        isPlaying = false,
        playbackState = 0,
        sequence = 1L,
    )

    @Test
    fun firstPlayAnchorsThenPauseFreezesThenResumeContinues() {
        val driver = RenderClockDriver()
        var t = 0L

        // IDLE：冻结于 0
        assertEquals(0L, driver.advance(t, pausedSample(t)))

        // 首播：onPlay 锚定 sample 位置
        t += frameNs
        assertEquals(60_000_000_000L, driver.advance(t, playingSample(60_000_000_000L, t)))
        t += frameNs
        assertEquals(60_016_666_667L, driver.advance(t, playingSample(60_016_000_000L, t)))

        // 暂停：冻结当前连续时间
        t += frameNs
        val frozen = driver.advance(t, pausedSample(t))
        assertEquals(60_033_333_334L, frozen)
        repeat(3) {
            t += frameNs
            assertEquals("暂停期第 $it 帧变动", frozen, driver.advance(t, pausedSample(t)))
        }

        // 恢复：从冻结值连续继续（60_033_333_334 + 16_666_667 = 60_050_000_001 ns）
        t += frameNs
        assertEquals(frozen, driver.advance(t, playingSample(frozen, t)))
        t += frameNs
        assertEquals(60_050_000_001L, driver.advance(t, playingSample(60_050_000_001L, t)))
    }

    @Test
    fun speedChangeKeepsPositionContinuous() {
        val driver = RenderClockDriver()
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t))

        // 1x 前进到 t1（约 60_983ms；锚点在起播帧，非 0）
        val t1 = 1_000_000_000L
        val beforeSwitch = driver.advance(t1, playingSample(61_000_000_000L, t1))
        assertTrue("1x 应已推进", beforeSwitch > 60_500_000_000L)

        // 切 2x：切速瞬间位置不得跳变（倍速策略 A：位置连续、仅导数变化）
        val atSwitch = driver.advance(t1, playingSample(61_000_000_000L, t1, speed = 2.0))
        assertEquals("切速瞬间位置跳变", beforeSwitch, atSwitch)

        // 之后 100ms 按 2x 推进：增量约 200ms（纳秒取整允许 ±1ms）
        val t2 = t1 + 100_000_000L
        val after100ms = driver.advance(t2, playingSample(61_200_000_000L, t2, speed = 2.0))
        val delta = after100ms - atSwitch
        assertTrue("2x 后 100ms 增量异常 delta=$delta", delta in 199_000_000L..201_000_000L)
    }

    @Test
    fun playerPositionJitterDoesNotAffectMotionClock() {
        // 解耦核心：已入场弹幕只依赖 ΔT，播放器上报位置怎么跳都与运动无关。
        val driver = RenderClockDriver()
        var t = 0L
        t += frameNs
        // 首播锚定 60s
        assertEquals(60_000_000_000L, driver.advance(t, playingSample(60_000_000_000L, t)))

        // 之后播放器位置大幅乱跳（±500ms 抖动），运动时钟仍须严格每帧 +16_666_667ns
        val jittered = listOf(
            60_500_000_000L,
            59_500_000_000L,
            60_300_000_000L,
            61_000_000_000L,
            60_000_000_000L,
        )
        jittered.forEachIndexed { i, pos ->
            t += frameNs
            val expected = 60_000_000_000L + (i + 1) * frameNs
            val actual = driver.advance(t, playingSample(pos, t, sequence = 2L + i))
            assertEquals("播放器抖动泄漏到了运动时钟（第 $i 帧）", expected, actual)
        }
    }

    @Test
    fun largePlayerDriftDoesNotReanchorMotionClock() {
        // 解耦核心：正常播放期间无周期治理，播放器长期漂移不得触发 Reanchor。
        val driver = RenderClockDriver()
        var t = 0L
        t += frameNs
        assertEquals(60_000_000_000L, driver.advance(t, playingSample(60_000_000_000L, t)))

        // 播放器持续上报 +500ms 前跳（无 seek 事件）：旧方案会在 3 拍后 Reanchor，
        // 新方案必须完全忽略，运动时钟保持 1x 匀速。
        repeat(5) { i ->
            t += frameNs
            val expected = 60_000_000_000L + (i + 1) * frameNs
            val actual = driver.advance(t, playingSample(60_500_000_000L, t, sequence = 2L + i))
            assertEquals("漂移治理未退役（第 $i 帧）", expected, actual)
        }
        assertEquals("不应有任何 Reanchor", 0, driver.diagnostics().reanchorCount)
    }

    @Test
    fun repeatedSampleCausesNoStateChange() {
        val driver = RenderClockDriver()
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t))

        // 同一份样本连续喂多帧（主线程采样停滞）：运动时钟仍须匀速推进。
        // 锚点在首播帧（t=F），第 k 次推进后 = 60s + k*frameNs。
        val stale = playingSample(60_016_000_000L, frameNs, sequence = 2L)
        repeat(3) { i ->
            t += frameNs
            val expected = 60_000_000_000L + (i + 1) * frameNs
            assertEquals(expected, driver.advance(t, stale))
        }
    }

    @Test
    fun bufferingDuringPlaybackFreezesMotionClock() {
        val driver = RenderClockDriver()
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t))
        t += frameNs
        val before = driver.advance(t, playingSample(60_016_000_000L, t))

        // 模拟 BUFFERING↔READY 振荡：isPlaying 反复翻转，运动位置必须连续无跳变。
        var expected = before
        repeat(3) { round ->
            t += frameNs
            driver.advance(t, pausedSample(t)) // 冻结
            t += frameNs
            expected += frameNs
            assertEquals(
                "buffering 振荡产生位置跳变（第 $round 轮）",
                expected,
                driver.advance(t, playingSample(expected, t)),
            )
        }
    }

    @Test
    fun resetClearsFrozenAndPlaybackEdges() {
        val driver = RenderClockDriver()
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t))
        t += frameNs
        driver.advance(t, pausedSample(t))
        assertEquals(60_016_666_667L, driver.advance(t, pausedSample(t)))

        driver.reset()
        assertEquals(0L, driver.advance(t, pausedSample(t)))

        // 重置后重新首播，走 onPlay 而非 onResume
        t += frameNs
        assertEquals(120_000_000_000L, driver.advance(t, playingSample(120_000_000_000L, t)))
    }
}
