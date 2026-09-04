package com.tutu.myblbl.feature.player.danmaku.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ClockSynchronizer 决策回归（定稿 §14/§35/§63-64）。
 *
 * 覆盖：Sample 投影后比较 / 三档误差（Ignore/Soft Slew/Strong Slew/连续确认 Reanchor）/
 * 负误差（Clock 超前）输出负 Slew / Stale 只轻微 Slew / Invalid 不治理。
 */
class ClockSynchronizerTest {

    private val sync = ClockSynchronizer()

    /** 构造一个「采样时刻 = 目标时刻」的样本，使投影结果 == mediaPositionNs，便于精确断言。 */
    private fun sampleAt(
        mediaPositionNs: Long,
        isPlaying: Boolean = true,
        speed: Double = 1.0,
        sampledAtRealtimeNs: Long = 0L,
        sequence: Long = 1L,
    ): MediaClockSample = MediaClockSample(
        mediaPositionNs = mediaPositionNs,
        sampledAtRealtimeNs = sampledAtRealtimeNs,
        playbackSpeed = speed,
        isPlaying = isPlaying,
        playbackState = 0,
        sequence = sequence,
    )

    // ---- 三档误差 ----

    @Test
    fun errorBelow15msIsIgnored() {
        val sample = sampleAt(mediaPositionNs = 60_005_000_000L) // 落后 5ms
        val decision = sync.evaluate(sample, presentationTimeNs = 0L, danmakuMediaNs = 60_000_000_000L)
        assertTrue("5ms 误差应 Ignore，实际 $decision", decision is ClockSyncDecision.Ignore)
    }

    @Test
    fun errorInSoftBandProducesSoftSlew() {
        val sample = sampleAt(mediaPositionNs = 60_050_000_000L) // 落后 50ms
        val decision = sync.evaluate(sample, presentationTimeNs = 0L, danmakuMediaNs = 60_000_000_000L)
        assertTrue("50ms 误差应 Slew，实际 $decision", decision is ClockSyncDecision.Slew)
        decision as ClockSyncDecision.Slew
        assertTrue("soft rate 应 ≤3%", abs(decision.correctionRate) <= 0.03 + 1e-9)
        assertTrue("50ms → 期望 rate≈+2.5%，实际 ${decision.correctionRate}", abs(decision.correctionRate - 0.025) < 1e-9)
    }

    @Test
    fun errorInStrongBandUsesUpTo5Percent() {
        val sample = sampleAt(mediaPositionNs = 60_200_000_000L) // 落后 200ms
        val decision = sync.evaluate(sample, presentationTimeNs = 0L, danmakuMediaNs = 60_000_000_000L)
        decision as ClockSyncDecision.Slew
        assertTrue("strong rate 应 ≤5% 且 >3%", decision.correctionRate > 0.03 && decision.correctionRate <= 0.05 + 1e-9)
    }

    @Test
    fun clockAheadProducesNegativeSlewRate() {
        // Clock 超前 Player 60ms：error < 0 → 负 Slew 减速等 Player 追上
        val sample = sampleAt(mediaPositionNs = 59_940_000_000L)
        val decision = sync.evaluate(sample, presentationTimeNs = 0L, danmakuMediaNs = 60_000_000_000L)
        decision as ClockSyncDecision.Slew
        assertTrue("超前应输出负 rate，实际 ${decision.correctionRate}", decision.correctionRate < 0.0)
    }

    @Test
    fun largeNegativeErrorUsesStrongSlewInsteadOfBackwardReanchor() {
        val decision = sync.evaluate(
            sampleAt(mediaPositionNs = 59_500_000_000L),
            presentationTimeNs = 0L,
            danmakuMediaNs = 60_000_000_000L,
        )

        assertTrue("负向大误差不应进入冻结式 Reanchor，实际 $decision", decision is ClockSyncDecision.Slew)
        decision as ClockSyncDecision.Slew
        assertEquals(-0.05, decision.correctionRate, 1e-9)
    }

    // ---- Hard Reanchor：需连续确认 ----

    @Test
    fun largeErrorRequiresConsecutiveConfirmationsBeforeReanchor() {
        val sync = ClockSynchronizer()
        // 落后 500ms：连续 3 拍内前两拍仍不 Reanchor
        repeat(2) { i ->
            val d = sync.evaluate(
                sampleAt(mediaPositionNs = 60_500_000_000L),
                presentationTimeNs = 0L,
                danmakuMediaNs = 60_000_000_000L,
            )
            assertTrue("第 ${i + 1} 拍不应 Reanchor，实际 $d", d !is ClockSyncDecision.Reanchor)
        }
        val d3 = sync.evaluate(
            sampleAt(mediaPositionNs = 60_500_000_000L),
            presentationTimeNs = 0L,
            danmakuMediaNs = 60_000_000_000L,
        )
        assertTrue("第 3 拍应 Reanchor，实际 $d3", d3 is ClockSyncDecision.Reanchor)
        assertEquals(60_500_000_000L, (d3 as ClockSyncDecision.Reanchor).targetMediaNs)
    }

    @Test
    fun driftRecoveryResetsConfirmationStreak() {
        val sync = ClockSynchronizer()
        // 第一拍大误差
        sync.evaluate(sampleAt(60_500_000_000L), 0L, 60_000_000_000L)
        // 第二拍误差回到死区 → 计数清零
        sync.evaluate(sampleAt(60_003_000_000L), 0L, 60_000_000_000L)
        // 第三拍虽大误差但只算第 1 次连续 → 仍不 Reanchor
        val d = sync.evaluate(sampleAt(60_500_000_000L), 0L, 60_000_000_000L)
        assertTrue("死区拍应清空连续计数", d !is ClockSyncDecision.Reanchor)
    }

    @Test
    fun explicitEventResetClearsStreak() {
        val sync = ClockSynchronizer()
        sync.evaluate(sampleAt(60_500_000_000L), 0L, 60_000_000_000L)
        sync.evaluate(sampleAt(60_500_000_000L), 0L, 60_000_000_000L)
        sync.reset()
        val d = sync.evaluate(sampleAt(60_500_000_000L), 0L, 60_000_000_000L)
        assertTrue("reset 后重新计数，不应立即 Reanchor", d !is ClockSyncDecision.Reanchor)
    }

    // ---- 样本投影与陈旧性 ----

    @Test
    fun staleSampleOnlyAllowsMildSlew() {
        // age = 800ms：STALE，误差 200ms 也只允许 ±3% 轻微 Slew，绝不 Reanchor
        val sample = sampleAt(
            mediaPositionNs = 60_200_000_000L - 800_000_000L, // 投影回 target=60.2s
            sampledAtRealtimeNs = 0L,
        )
        val decision = sync.evaluate(
            sample,
            presentationTimeNs = 800_000_000L,
            danmakuMediaNs = 60_000_000_000L,
        )
        assertTrue("STALE 大误差不应 Reanchor，实际 $decision", decision !is ClockSyncDecision.Reanchor)
        assertTrue("STALE 应 Slew，实际 $decision", decision is ClockSyncDecision.Slew)
        (decision as ClockSyncDecision.Slew).let {
            assertTrue("STALE 轻微 Slew ≤3%", abs(it.correctionRate) <= 0.03 + 1e-9)
        }
    }

    @Test
    fun invalidSampleIsNotGovernedAtAll() {
        // age = 2s：INVALID，即使 500ms 误差也不治理
        val sample = sampleAt(
            mediaPositionNs = 60_500_000_000L - 2_000_000_000L,
            sampledAtRealtimeNs = 0L,
        )
        val decision = sync.evaluate(
            sample,
            presentationTimeNs = 2_000_000_000L,
            danmakuMediaNs = 60_000_000_000L,
        )
        assertTrue("INVALID 样本应 Ignore，实际 $decision", decision is ClockSyncDecision.Ignore)
    }

    @Test
    fun projectionIsAppliedBeforeComparison() {
        // sample=60.0s@T0，当前帧时刻 T0+100ms（speed=1.0）。
        // 若不投影会认为 error=0（Ignore）；投影后 error=+100ms → Strong Slew。
        val sample = sampleAt(
            mediaPositionNs = 60_000_000_000L,
            sampledAtRealtimeNs = 0L,
            speed = 1.0,
        )
        val decision = sync.evaluate(
            sample,
            presentationTimeNs = 100_000_000L,
            danmakuMediaNs = 60_000_000_000L,
        )
        assertTrue("投影后 100ms 误差应 Slew，实际 $decision", decision is ClockSyncDecision.Slew)
        val rate = (decision as ClockSyncDecision.Slew).correctionRate
        assertTrue(
            "raw=100/2000=5% 应被 SOFT 上限 clamp 到 3%，实际 $rate",
            abs(rate - 0.03) < 1e-9,
        )
    }
}
