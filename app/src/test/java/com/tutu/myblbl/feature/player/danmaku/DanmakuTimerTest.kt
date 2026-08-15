package com.tutu.myblbl.feature.player.danmaku

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DanmakuTimerTest {

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

        // 切速帧使用指数收敛而不是硬锚，允许保留约一帧的稳定偏差；
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
