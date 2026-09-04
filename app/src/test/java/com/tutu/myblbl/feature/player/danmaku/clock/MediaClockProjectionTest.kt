package com.tutu.myblbl.feature.player.danmaku.clock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MediaClockSample 投影回归（定稿约束 2 / 文档 §48）。
 *
 * 关键语义：Player Sample 必须先投影到当前显示帧时刻，再与 Continuous Clock 比较。
 */
class MediaClockProjectionTest {

    private fun sample(
        mediaPositionNs: Long,
        sampledAtRealtimeNs: Long,
        isPlaying: Boolean = true,
        speed: Double = 1.0,
        sequence: Long = 1L,
    ): MediaClockSample = MediaClockSample(
        mediaPositionNs = mediaPositionNs,
        sampledAtRealtimeNs = sampledAtRealtimeNs,
        playbackSpeed = speed,
        isPlaying = isPlaying,
        playbackState = 0,
        sequence = sequence,
    )

    @Test
    fun playingSampleProjectsForwardByElapsedTimesSpeed() {
        val s = sample(
            mediaPositionNs = 10_000_000_000L,          // 10s
            sampledAtRealtimeNs = 0L,                   // T0
            speed = 1.0,
        )
        val projected = projectSample(s, targetRealtimeNs = 100_000_000L) // T0+100ms
        assertEquals(10_100_000_000L, projected)        // ≈10.1s，而非 10.0s
    }

    @Test
    fun pausedSampleIsNotProjected() {
        val s = sample(
            mediaPositionNs = 10_000_000_000L,
            sampledAtRealtimeNs = 0L,
            isPlaying = false,
        )
        val projected = projectSample(s, targetRealtimeNs = 100_000_000L)
        assertEquals(10_000_000_000L, projected)
    }

    @Test
    fun projectionScalesWithPlaybackSpeed() {
        val s = sample(
            mediaPositionNs = 10_000_000_000L,
            sampledAtRealtimeNs = 0L,
            speed = 2.0,
        )
        val projected = projectSample(s, targetRealtimeNs = 100_000_000L) // 100ms × 2x
        assertEquals(10_200_000_000L, projected)
    }

    @Test
    fun staleProjectionNeverGoesBackwards() {
        // 目标时刻早于采样时刻（异常时序）不产生负外推。
        val s = sample(
            mediaPositionNs = 10_000_000_000L,
            sampledAtRealtimeNs = 100_000_000L,
            speed = 1.0,
        )
        val projected = projectSample(s, targetRealtimeNs = 50_000_000L) // 早于采样
        assertEquals(10_000_000_000L, projected)
    }
}
