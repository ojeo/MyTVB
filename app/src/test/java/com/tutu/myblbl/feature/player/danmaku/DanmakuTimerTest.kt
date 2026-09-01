package com.tutu.myblbl.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 混合时钟策略的行为回归：
 * - raw 短暂落后时不得反向追赶，避免周期性冻结/跳变
 * - resume 后仅以前向有界速率收敛，不得出现同帧整体跳变
 * - seek 仍瞬时硬锚、暂停边界仍不回退、softSyncFactor 仍然生效、极端漂移硬锚兜底
 */
class DanmakuTimerTest {

    private val frameNs = 16_666_667L
    private val frameMs = 17L

    @Test
    fun resumeAfterBufferingConvergesWithoutSingleFrameJump() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L

        // 初始化 + 正常播放，raw 与平滑位置同步推进
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(60) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 缓冲：暂停数帧，raw 按解码位继续前进，与平滑位置拉开偏差
        repeat(8) {
            now += frameNs
            rawMs += 100
            timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
        }
        val gapAtResume = rawMs - timer.currentPositionMs()
        assertTrue("暂停期间未拉开预期偏差 gap=$gapAtResume", gapAtResume in 300L..900L)

        // 恢复播放：resume 帧不得硬跳到 raw（弹幕整体跳变的根因）
        now += frameNs
        rawMs += frameMs
        val resumePos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        assertTrue("resume 帧发生硬跳 resume=$resumePos raw=$rawMs", resumePos <= rawMs - gapAtResume + frameMs)

        // 之后以前向 +10% 上限渐进收敛：单帧前进量有界，约 10s 内贴近 raw。
        var maxStep = 0L
        var prev = resumePos
        var converged = false
        repeat(900) {
            now += frameNs
            rawMs += frameMs
            val pos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
            maxStep = maxOf(maxStep, pos - prev)
            prev = pos
            if (rawMs - pos in 0L..30L) converged = true
        }
        assertTrue("有界前向追赶未收敛到 30ms 内，最终 gap=${rawMs - prev}", converged)
        assertTrue("收敛期间出现单帧大跳 $maxStep ms", maxStep in 0L..20L)
    }

    @Test
    fun seekStillHardAnchorsImmediately() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 用户 seek：seekSerial 变化，必须瞬时硬锚
        rawMs = 120_000L
        now += frameNs
        val pos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 1)
        assertEquals(120_000L, pos)
    }

    @Test
    fun pauseEdgeKeepsPositionAndNeverMovesBackward() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }
        val smoothBefore = timer.currentPositionMs()

        // 暂停瞬间 raw 回退几十毫秒（解码缓冲固有行为）：平滑位置必须保持不动
        now += frameNs
        rawMs -= 200
        val pausedPos = timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
        assertEquals(smoothBefore, pausedPos)
    }

    @Test
    fun pausedForwardGapKeepsDanmakuFrozen() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 暂停/缓冲时 raw 可能是解码器推测位置；弹幕必须保持冻结。
        now += frameNs
        rawMs += 500
        val frozenAt = timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)

        repeat(120) {
            now += frameNs
            timer.step(now, rawMs, isPlaying = false, playbackSpeed = 1f, seekSerial = 0)
        }
        assertEquals(frozenAt, timer.currentPositionMs())
    }

    @Test
    fun transientRawLagNeverSlowsOrRewindsSmoothClock() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        var previous = timer.currentPositionMs()
        // Reproduce Player.currentPosition temporarily stalling while Choreographer keeps ticking.
        repeat(24) {
            now += frameNs
            val current = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
            assertTrue("raw 落后时平滑时钟停顿/回退：${current - previous}ms", current - previous in 16L..17L)
            previous = current
        }
    }

    @Test
    fun softSyncFactorStillScalesFrameAdvance() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 60_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        timer.softSyncFactor = 1.1
        val before = timer.currentPositionMs()
        now += frameNs
        rawMs += frameMs
        val after = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        // 1 帧约 16.7ms × 1.1 ≈ 18ms（死区内不会触发渐进追赶，缩放因子直接生效）
        assertEquals(18L, after - before)
    }

    @Test
    fun extremeUnreportedDiscontinuityStillHardReanchors() {
        val timer = DanmakuTimer()
        var now = 0L
        var rawMs = 100_000L
        timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        repeat(30) {
            now += frameNs
            rawMs += frameMs
            timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        }

        // 未报告的前跳 6s（seekSerial 不变）：超过 HARD_REANCHOR_GAP_MS，退回一次性硬锚
        rawMs += 6_000
        now += frameNs
        val pos = timer.step(now, rawMs, isPlaying = true, playbackSpeed = 1f, seekSerial = 0)
        assertEquals(rawMs, pos)
    }

    @Test
    fun steadyPlaybackTracksRawPositionAtSupportedSpeedsWithoutCatchUpJumps() {
        listOf(1f, 1.5f, 2f).forEach { speed ->
            val result = simulateSteadyPlayback(speed)

            assertTrue(
                "speed=$speed drift=${result.finalDriftMs}",
                abs(result.finalDriftMs) <= DRIFT_TOLERANCE_MS,
            )
            assertTrue(
                "speed=$speed maxAdvance=${result.maxFrameAdvanceMs}",
                result.maxFrameAdvanceMs <= expectedFrameAdvanceMs(speed) + FRAME_ADVANCE_TOLERANCE_MS,
            )
        }
    }

    @Test
    fun changingPlaybackSpeedKeepsTheClockContinuousAndTracksTheNewRate() {
        val timer = DanmakuTimer()
        var nowNanos = 0L
        var rawPositionMs = 0.0
        var previousPositionMs = 0L
        var maxFrameAdvanceMs = 0L

        repeat(PLAYBACK_FRAMES) { frame ->
            val speed = if (frame < SPEED_CHANGE_FRAME) 1f else 2f
            nowNanos += FRAME_DURATION_NANOS
            rawPositionMs += FRAME_DURATION_MS * speed
            val positionMs = timer.step(
                nowNanos = nowNanos,
                rawPositionMs = rawPositionMs.toLong(),
                isPlaying = true,
                playbackSpeed = speed,
                seekSerial = 0,
            )
            if (frame > 0) {
                maxFrameAdvanceMs = maxOf(maxFrameAdvanceMs, positionMs - previousPositionMs)
            }
            previousPositionMs = positionMs
        }

        // 切速帧使用有界前向收敛而不是硬锚，允许保留约一帧的稳定偏差；
        // 关键约束是不能再积累到 2 秒并触发周期性追赶。
        assertTrue(abs(rawPositionMs.toLong() - previousPositionMs) <= SPEED_CHANGE_DRIFT_TOLERANCE_MS)
        assertTrue(maxFrameAdvanceMs <= expectedFrameAdvanceMs(2f) + FRAME_ADVANCE_TOLERANCE_MS)
    }

    private fun simulateSteadyPlayback(speed: Float): SimulationResult {
        val timer = DanmakuTimer()
        var nowNanos = 0L
        var rawPositionMs = 0.0
        var previousPositionMs = 0L
        var maxFrameAdvanceMs = 0L

        repeat(PLAYBACK_FRAMES) { frame ->
            nowNanos += FRAME_DURATION_NANOS
            rawPositionMs += FRAME_DURATION_MS * speed
            val positionMs = timer.step(
                nowNanos = nowNanos,
                rawPositionMs = rawPositionMs.toLong(),
                isPlaying = true,
                playbackSpeed = speed,
                seekSerial = 0,
            )
            if (frame > 0) {
                maxFrameAdvanceMs = maxOf(maxFrameAdvanceMs, positionMs - previousPositionMs)
            }
            previousPositionMs = positionMs
        }

        return SimulationResult(
            finalDriftMs = rawPositionMs.toLong() - previousPositionMs,
            maxFrameAdvanceMs = maxFrameAdvanceMs,
        )
    }

    private fun expectedFrameAdvanceMs(speed: Float): Long =
        (FRAME_DURATION_MS * speed).toLong()

    private data class SimulationResult(
        val finalDriftMs: Long,
        val maxFrameAdvanceMs: Long,
    )

    private companion object {
        private const val FRAME_DURATION_NANOS = 16_666_667L
        private const val FRAME_DURATION_MS = FRAME_DURATION_NANOS / 1_000_000.0
        private const val PLAYBACK_FRAMES = 600
        private const val SPEED_CHANGE_FRAME = PLAYBACK_FRAMES / 2
        private const val DRIFT_TOLERANCE_MS = 2L
        private const val SPEED_CHANGE_DRIFT_TOLERANCE_MS = 40L
        private const val FRAME_ADVANCE_TOLERANCE_MS = 2L
    }
}
