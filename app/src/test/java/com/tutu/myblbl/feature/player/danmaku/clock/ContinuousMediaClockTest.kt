package com.tutu.myblbl.feature.player.danmaku.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContinuousMediaClock 行为回归（定稿 §8 测试清单）。
 *
 * 覆盖：纯函数幂等 / Debug 读取不改状态 / 暂停冻结不吸附 raw / Resume 连续 /
 * 掉帧恢复 / 速度变化连续 / 误差 Reanchor 不倒退 / 单调不减 / 24fps cadence 解耦。
 */
class ContinuousMediaClockTest {

    private val frameNs = 16_666_667L // 60Hz

    private fun playingSample(
        mediaPositionNs: Long,
        sampledAtRealtimeNs: Long,
        speed: Double = 1.0,
    ): MediaClockSample = MediaClockSample(
        mediaPositionNs = mediaPositionNs,
        sampledAtRealtimeNs = sampledAtRealtimeNs,
        playbackSpeed = speed,
        isPlaying = true,
        playbackState = 0,
        sequence = 1L,
    )

    private fun clockAt(mediaNs: Long, realNs: Long, speed: Double = 1.0): ContinuousMediaClock =
        ContinuousMediaClock().apply {
            onPlay(playingSample(mediaNs, realNs, speed), realNs)
        }

    // ---- Invariant 2/3/4：纯函数幂等 + 读取不改状态 ----

    @Test
    fun mediaTimeAtIsPure() {
        val clock = clockAt(60_000_000_000L, 0L)
        val expected = clock.mediaTimeAt(1_000_000_000L)
        repeat(100) {
            assertEquals("mediaTimeAt 第 $it 次调用结果不一致", expected, clock.mediaTimeAt(1_000_000_000L))
        }
    }

    @Test
    fun profilerAndDebugReadsDoNotChangeClock() {
        val clock = clockAt(60_000_000_000L, 0L)
        val before = clock.mediaTimeAt(500_000_000L)
        // 模拟 profiler / debug overlay / scheduler / motion 各自读取同一帧时刻
        repeat(10) { clock.mediaTimeAt(500_000_000L) }
        clock.mediaTimeAt(500_000_000L)
        val after = clock.mediaTimeAt(500_000_000L)
        assertEquals(before, after)
    }

    // ---- 外推 ----

    @Test
    fun extrapolatesFromAnchorByElapsedAndSpeed() {
        val clock = clockAt(60_000_000_000L, 0L)
        assertEquals(60_000_000_000L, clock.mediaTimeAt(0L))
        assertEquals(60_016_666_667L, clock.mediaTimeAt(frameNs))
        assertEquals(60_100_000_000L, clock.mediaTimeAt(100_000_000L))
    }

    @Test
    fun droppedFrameRecoversToCorrectContinuousTime() {
        // 60Hz 正常走到 33ms，再跳过 2 帧直接到 83ms：
        // Anchor 模型应返回正确的连续时间，而不是「上一帧 +16ms」的增量累积。
        val clock = clockAt(0L, 0L)
        clock.mediaTimeAt(0L)
        clock.mediaTimeAt(frameNs)
        clock.mediaTimeAt(2 * frameNs) // 33.3ms
        val afterDrop = clock.mediaTimeAt(5 * frameNs) // 83.3ms
        assertEquals(5 * frameNs, afterDrop) // 0 + 83.3ms×1.0，非 33.3+16.7=50ms
    }

    // ---- Pause / Resume ----

    @Test
    fun pauseFreezesCurrentClockNotRawPlayer() {
        // Continuous Clock = 60.016s，Player Sample = 60.003s。
        // Pause 必须冻结 Clock 当前值，禁止吸附到滞后的 raw。
        val clock = clockAt(60_000_000_000L, 0L)
        val frozenAt = clock.mediaTimeAt(frameNs) // 60.016s
        assertEquals(60_016_666_667L, frozenAt)

        clock.onPause(presentationTimeNs = frameNs)
        // 之后多个 VSYNC，Clock 仍返回冻结值
        repeat(60) { i ->
            assertEquals(
                "暂停期间第 $i 帧时钟变动",
                frozenAt,
                clock.mediaTimeAt(frameNs * (i + 2L)),
            )
        }
    }

    @Test
    fun resumeContinuesFromFrozenWithoutJump() {
        val clock = clockAt(60_000_000_000L, 0L)
        clock.mediaTimeAt(frameNs)
        clock.onPause(frameNs)
        val pausedAt = clock.mediaTimeAt(1_000_000_000L)
        assertEquals(60_016_666_667L, pausedAt)

        // Resume：sample 位置可能滞后于冻结值，但必须从冻结值连续继续
        val resumeSample = playingSample(
            mediaPositionNs = 60_003_000_000L,
            sampledAtRealtimeNs = 1_000_000_000L,
        )
        clock.onResume(resumeSample, presentationTimeNs = 1_000_000_000L)
        assertEquals(pausedAt, clock.mediaTimeAt(1_000_000_000L)) // 无跳变
        assertEquals(pausedAt + frameNs, clock.mediaTimeAt(1_000_000_000L + frameNs)) // 正常推进
    }

    // ---- Seek ----

    @Test
    fun seekHardAnchorsImmediately() {
        val clock = clockAt(60_000_000_000L, 0L)
        clock.mediaTimeAt(frameNs)
        clock.onSeek(600_000_000_000L, presentationTimeNs = frameNs, playbackSpeed = 1.0)
        assertEquals(600_000_000_000L, clock.mediaTimeAt(frameNs))
        assertEquals(600_016_666_667L, clock.mediaTimeAt(frameNs * 2L))
    }

    // ---- Speed Change ----

    @Test
    fun speedChangeKeepsPositionContinuous() {
        val clock = clockAt(60_000_000_000L, 0L)
        // 走到 61s（1x）
        val t1 = 1_000_000_000L
        assertEquals(61_000_000_000L, clock.mediaTimeAt(t1))

        // 切到 2x：切速瞬间位置不得跳变，之后按 2x 推进
        clock.onPlaybackSpeedChanged(newSpeed = 2.0, presentationTimeNs = t1)
        assertEquals(61_000_000_000L, clock.mediaTimeAt(t1))
        assertEquals(61_200_000_000L, clock.mediaTimeAt(t1 + 100_000_000L)) // 100ms×2x
    }

    // ---- 误差治理：Slew / Reanchor ----

    @Test
    fun negativeSlewSlowsDownButNeverRewinds() {
        // Clock 超前 Player：负 Slew 减速等待 Player 追上；媒体时间仍严格单调不减。
        val clock = clockAt(60_000_000_000L, 0L)
        val slew = ClockSyncDecision.Slew(
            errorNs = -100_000_000L,   // Clock 超前 100ms
            correctionRate = -0.03,
            durationNs = 800_000_000L,
        )
        var previous = clock.mediaTimeAt(0L)
        // 前 5 帧用 0.97x，之后回到 1.0x 再跑若干帧
        var t = 0L
        repeat(5) {
            clock.applySyncDecision(slew, presentationTimeNs = t)
            t += frameNs
            val media = clock.mediaTimeAt(t)
            assertTrue("负 Slew 期间媒体时间倒退：$previous → $media", media >= previous)
            previous = media
        }
        clock.onPlaybackSpeedChanged(1.0, presentationTimeNs = t) // 回到基础速率（模拟 Player 追上）
        repeat(60) {
            t += frameNs
            val media = clock.mediaTimeAt(t)
            assertTrue("恢复基础速率后媒体时间倒退：$previous → $media", media >= previous)
            previous = media
        }
    }

    @Test
    fun ignoreEndsActiveSlewAndRestoresUserSpeedWithoutJump() {
        val clock = clockAt(60_000_000_000L, 0L)
        clock.applySyncDecision(
            ClockSyncDecision.Slew(
                errorNs = 60_000_000L,
                correctionRate = 0.03,
                durationNs = 800_000_000L,
            ),
            presentationTimeNs = 0L,
        )

        val atIgnore = clock.mediaTimeAt(200_000_000L)
        assertEquals(60_206_000_000L, atIgnore)
        clock.applySyncDecision(ClockSyncDecision.Ignore, presentationTimeNs = 200_000_000L)

        assertEquals("Ignore 时位置不得跳变", atIgnore, clock.mediaTimeAt(200_000_000L))
        assertEquals(0.0, clock.correctionRate, 0.0)
        assertEquals(
            "Ignore 后应恢复 1x，不得残留 +3%",
            atIgnore + 100_000_000L,
            clock.mediaTimeAt(300_000_000L),
        )
    }

    @Test
    fun slewExpiresAtDeclaredDurationWithoutAPlayerSpeedEvent() {
        val clock = clockAt(60_000_000_000L, 0L)
        clock.applySyncDecision(
            ClockSyncDecision.Slew(
                errorNs = 60_000_000L,
                correctionRate = 0.03,
                durationNs = 500_000_000L,
            ),
            presentationTimeNs = 0L,
        )

        clock.onFrame(500_000_000L)
        val atExpiry = clock.mediaTimeAt(500_000_000L)
        assertEquals(60_515_000_000L, atExpiry)
        assertEquals(0.0, clock.correctionRate, 0.0)
        assertEquals(
            "Slew 到期后应自动恢复 1x",
            atExpiry + 100_000_000L,
            clock.mediaTimeAt(600_000_000L),
        )
    }

    @Test
    fun reanchorForwardOnlyNeverBackwards() {
        val clock = clockAt(60_000_000_000L, 0L)
        clock.mediaTimeAt(0L)
        // 目标 < 当前：误差启发 Reanchor 被拒绝，改用有界减速，不后退也不冻结。
        clock.applySyncDecision(
            ClockSyncDecision.Reanchor(targetMediaNs = 59_900_000_000L),
            presentationTimeNs = 0L,
        )
        val afterOneSecond = clock.mediaTimeAt(1_000_000_000L)
        assertEquals(60_950_000_000L, afterOneSecond)
        assertTrue(afterOneSecond > 60_000_000_000L)

        // 目标 > 当前：向前 Reanchor 生效
        clock.applySyncDecision(
            ClockSyncDecision.Reanchor(targetMediaNs = 61_000_000_000L),
            presentationTimeNs = 1_000_000_000L,
        )
        assertEquals(61_000_000_000L, clock.mediaTimeAt(1_000_000_000L))
    }

    @Test
    fun monotonicNonDecreasingDuringSteadyPlayback() {
        // 正常播放中：媒体时间单调不减（含 Slew ± 校正场景）。
        val clock = clockAt(0L, 0L)
        var t = 0L
        var previous = 0L
        repeat(300) {
            t += frameNs
            // 周期性施加轻微正负 Slew，模拟同步器拍
            if (it % 100 == 50) {
                clock.applySyncDecision(
                    ClockSyncDecision.Slew(errorNs = 20_000_000L, correctionRate = 0.02, durationNs = 500_000_000L),
                    presentationTimeNs = t,
                )
            }
            if (it % 100 == 80) {
                clock.onPlaybackSpeedChanged(1.0, presentationTimeNs = t)
            }
            val media = clock.mediaTimeAt(t)
            assertTrue("PLAYING 期媒体时间倒退 idx=$it：$previous → $media", media >= previous)
            previous = media
        }
    }

    // ---- 24fps + 60Hz cadence 解耦 ----

    @Test
    fun sixtyHzFrameCadenceAdvancesEveryFrameIndependentlyOfRaw() {
        // 视频 24fps（raw 2:3 cadence），弹幕 60Hz：Clock 每帧推进 ≈16.67ms，
        // 即使 raw sample 在 2/3 帧处才更新，也不影响弹幕连续性。
        val clock = ContinuousMediaClock()
        // 起播：sample 位置 60s，之后 raw 每 41.67ms（24fps）才更新一次
        clock.onPlay(playingSample(60_000_000_000L, 0L), presentationTimeNs = 0L)

        var t = 0L
        var previous = clock.mediaTimeAt(0L)
        repeat(60) {
            t += frameNs
            val media = clock.mediaTimeAt(t)
            val stepMs = (media - previous) / 1_000_000.0
            assertTrue("帧推进异常 step=$stepMs ms @idx=$it", stepMs in 15.0..18.0)
            previous = media
        }
    }
}
