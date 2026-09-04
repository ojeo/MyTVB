package com.tutu.myblbl.feature.player.danmaku.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RenderClockDriver 行为回归（P1：GPU RenderThread 时钟驱动桥）。
 *
 * 覆盖：首播/暂停冻结/恢复连续、显式 Seek（播放中硬锚、非播放冻结等待）、
 * 速度变化位置连续、低频同步 Reanchor 生效、切模式重置。
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
        // 禁用低频同步，纯验证边沿事件链。
        val driver = RenderClockDriver(syncIntervalNs = Long.MAX_VALUE)
        var t = 0L

        // IDLE：冻结于 0
        assertEquals(0L, driver.advance(t, pausedSample(t), null))

        // 首播：onPlay 锚定 sample 位置
        t += frameNs
        assertEquals(60_000_000_000L, driver.advance(t, playingSample(60_000_000_000L, t), null))
        t += frameNs
        assertEquals(60_016_666_667L, driver.advance(t, playingSample(60_016_000_000L, t), null))

        // 暂停：冻结当前连续时间
        t += frameNs
        val frozen = driver.advance(t, pausedSample(t), null)
        assertEquals(60_033_333_334L, frozen)
        repeat(3) {
            t += frameNs
            assertEquals("暂停期第 $it 帧变动", frozen, driver.advance(t, pausedSample(t), null))
        }

        // 恢复：从冻结值连续继续（60_033_333_334 + 16_666_667 = 60_050_000_001 ns）
        t += frameNs
        assertEquals(frozen, driver.advance(t, playingSample(frozen, t), null))
        t += frameNs
        assertEquals(60_050_000_001L, driver.advance(t, playingSample(60_050_000_001L, t), null))
    }

    @Test
    fun seekWhilePlayingHardAnchorsImmediately() {
        val driver = RenderClockDriver(syncIntervalNs = Long.MAX_VALUE)
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)
        t += frameNs

        val out = driver.advance(
            t,
            playingSample(60_016_000_000L, t),
            pendingSeekMediaNs = 600_000_000_000L,
        )
        assertEquals(600_000_000_000L, out)
        t += frameNs
        assertEquals(600_016_666_667L, driver.advance(t, playingSample(600_016_000_000L, t), null))
    }

    @Test
    fun seekWhilePausedFreezesAtTargetUntilPlaying() {
        val driver = RenderClockDriver(syncIntervalNs = Long.MAX_VALUE)
        var t = 0L
        // 播放后暂停
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)
        t += frameNs
        driver.advance(t, pausedSample(t), null)

        // 暂停中 seek：冻结在目标
        t += frameNs
        assertEquals(
            600_000_000_000L,
            driver.advance(t, pausedSample(t), pendingSeekMediaNs = 600_000_000_000L),
        )
        t += frameNs
        assertEquals(
            "seek 后仍未播放应保持冻结在目标",
            600_000_000_000L,
            driver.advance(t, pausedSample(t), null),
        )

        // 播放恢复：从 600s 目标连续继续
        t += frameNs
        assertEquals(600_000_000_000L, driver.advance(t, playingSample(600_000_000_000L, t), null))
        t += frameNs
        assertEquals(600_016_666_667L, driver.advance(t, playingSample(600_016_000_000L, t), null))
    }

    @Test
    fun speedChangeKeepsPositionContinuous() {
        val driver = RenderClockDriver(syncIntervalNs = Long.MAX_VALUE)
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)

        // 1x 前进到 t1（约 60_983ms；锚点在起播帧，非 0）
        val t1 = 1_000_000_000L
        val beforeSwitch = driver.advance(t1, playingSample(61_000_000_000L, t1), null)
        assertTrue("1x 应已推进", beforeSwitch > 60_500_000_000L)

        // 切 2x：切速瞬间位置不得跳变
        val atSwitch = driver.advance(t1, playingSample(61_000_000_000L, t1, speed = 2.0), null)
        assertEquals("切速瞬间位置跳变", beforeSwitch, atSwitch)

        // 之后 100ms 按 2x 推进：增量约 200ms（纳秒取整允许 ±1ms）
        val t2 = t1 + 100_000_000L
        val after100ms = driver.advance(t2, playingSample(61_200_000_000L, t2, speed = 2.0), null)
        val delta = after100ms - atSwitch
        assertTrue("2x 后 100ms 增量异常 delta=$delta", delta in 199_000_000L..201_000_000L)
    }

    @Test
    fun periodicSynchronizationReanchorsOnLargeDrift() {
        // syncInterval 极小 → 每帧一拍；连续 3 拍 ≥300ms 误差后 Reanchor 到 player 投影位置。
        val driver = RenderClockDriver(syncIntervalNs = 1L)
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)
        t += frameNs
        driver.advance(t, playingSample(60_016_000_000L, t, sequence = 2L), null)

        // Player 未报告前跳 +500ms（无 seek 事件）：
        // 前两拍先有界 Slew（连续确认中），第三拍 Reanchor 到 60.5s。
        var last = 0L
        repeat(3) { i ->
            t += frameNs
            last = driver.advance(t, playingSample(60_500_000_000L, t, sequence = 3L + i), null)
            if (i < 2) {
                // Reanchor 前：时钟仍在 60s 附近推进
                assertTrue("第 ${i + 1} 拍不应跳到 60.5s，got=$last", last < 60_400_000_000L)
            }
        }
        assertEquals(60_500_000_000L, last)
    }

    @Test
    fun largeNegativeDriftSlowsDownButKeepsAdvancing() {
        val driver = RenderClockDriver(syncIntervalNs = 1L)
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)
        t += frameNs
        val beforeDrift = driver.advance(t, playingSample(60_016_000_000L, t, sequence = 2L), null)

        // Player 落后 500ms：同步器改用 -5% Slew，位置仍必须每帧前进。
        t += frameNs
        val slewStarted = driver.advance(t, playingSample(59_500_000_000L, t, sequence = 3L), null)
        t += frameNs
        val afterSlewFrame = driver.advance(t, playingSample(59_516_666_667L, t, sequence = 4L), null)
        assertTrue(slewStarted >= beforeDrift)
        assertTrue("负向大误差期间时钟不得冻结", afterSlewFrame > slewStarted)
        assertTrue("负 Slew 的帧增量应小于 1x", afterSlewFrame - slewStarted < frameNs)
    }

    @Test
    fun repeatedSampleIsNotGovernedTwice() {
        val clock = ContinuousMediaClock()
        val driver = RenderClockDriver(clock = clock, syncIntervalNs = 1L)
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)

        // 新样本触发 Slew。
        t += frameNs
        val sample = playingSample(60_066_666_667L, t, sequence = 2L)
        driver.advance(t, sample, null)
        val firstDecision = clock.lastSync

        // 下一帧仍是 sequence=2：不应重复改写 Anchor/校正决策。
        t += frameNs
        driver.advance(t, sample, null)
        assertEquals(firstDecision, clock.lastSync)
    }

    @Test
    fun resetClearsFrozenAndPlaybackEdges() {
        val driver = RenderClockDriver(syncIntervalNs = Long.MAX_VALUE)
        var t = 0L
        t += frameNs
        driver.advance(t, playingSample(60_000_000_000L, t), null)
        t += frameNs
        driver.advance(t, pausedSample(t), null)
        assertEquals(60_016_666_667L, driver.advance(t, pausedSample(t), null))

        driver.reset()
        assertEquals(0L, driver.advance(t, pausedSample(t), null))

        // 重置后重新首播，走 onPlay 而非 onResume
        t += frameNs
        assertEquals(120_000_000_000L, driver.advance(t, playingSample(120_000_000_000L, t), null))
    }
}
