package com.tutu.myblbl.feature.player.danmaku.clock

import kotlin.math.abs

/**
 * Motion Clock 驱动桥（解耦方案 v0.2）。
 *
 * 职责收敛为**事件驱动**两件事：
 * - 播放状态边沿：isPlaying false→true（首播 onPlay / 恢复 onResume）、
 *   true→false（onPause / BUFFERING 冻结）——pause/buffering 感知的唯一来源；
 * - 播放倍速变化：onPlaybackSpeedChanged 重设走速，位置连续、仅导数变化。
 *
 * **已退役**（解耦方案 v0.2）：
 * - 周期漂移治理（ClockSynchronizer / Slew / 启发式 Reanchor）——已入场弹幕只依赖
 *   `ΔT = motionNowNs − motionStartNs`，Motion Clock 的绝对值无意义，漂移不产生任何观感后果；
 * - seek 重锚——seek 时 engine 会清空 Active 并重建，运动起点由新条目重新记录，
 *   Motion Clock 无需对齐，继续累计即可。
 *
 * 保留冻结看门狗：播放器报告在播但媒体时间长时间不前进时，作为未知状态异常的最后兜底。
 *
 * 线程：本类仅由渲染线程调用（与 `ContinuousMediaClock` 同线程串行）；重置由外部
 * 切模式 / release 时经 renderHandler.post 触发。
 */
internal class RenderClockDriver(
    private val clock: DanmakuClock = ContinuousMediaClock(),
    private val frozenWatchdogNs: Long = FROZEN_WATCHDOG_NS,
) {
    private var lastPlaying = false
    private var lastSpeed = 1.0
    private var everStarted = false

    // ---- 低成本诊断：热路径只写原语，字符串快照仅由 3s 日志读取 ----
    private var lastSampleAgeNs = 0L
    private var lastDecision = "idle"
    private var reanchorCount = 0

    // ---- 冻结看门狗（真实 Pause/Resume 状态异常的最后兜底）----
    private var lastMediaNs = Long.MIN_VALUE
    private var frozenSinceNs: Long? = null

    /**
     * 每渲染帧调用一次（RenderThread）。
     *
     * @param frameTimeNs 本帧 presentationTimeNs（Choreographer frameTimeNs，与渲染同源）。
     * @param sample 最近一次 Player 采样（position 与采样时刻成对）。
     * @return 运动域播放期累计时长（ns），供 engine.stepTime 的 motionNowNs。
     */
    fun advance(
        frameTimeNs: Long,
        sample: MediaClockSample,
    ): Long {
        clock.onFrame(frameTimeNs)
        val playing = sample.isPlaying
        if (playing != lastPlaying) {
            when {
                playing && !everStarted -> {
                    clock.onPlay(sample, frameTimeNs)
                    lastDecision = "play"
                }
                playing -> {
                    clock.onResume(sample, frameTimeNs)
                    lastDecision = "resume"
                }
                else -> {
                    clock.onPause(frameTimeNs)
                    lastDecision = "pause"
                }
            }
            everStarted = true
            lastPlaying = playing
        }
        if (playing && abs(sample.playbackSpeed - lastSpeed) > SPEED_EPSILON) {
            clock.onPlaybackSpeedChanged(sample.playbackSpeed, frameTimeNs)
            lastSpeed = sample.playbackSpeed
            lastDecision = "speed"
        }
        // 运动域自由运行：无周期治理、无 seek 重锚。
        // 返回纳秒：整数 ms 截断会量化 60Hz 每帧 16.67ms 为 16/17ms 交替，
        // 使滚动位移周期性摆动（约 ±6% 速度波动，视觉"一顿一顿"）。
        val mediaNs = clock.mediaTimeAt(frameTimeNs)
        // 看门狗可能重锚，必须取它解析后的媒体时间，否则本帧仍返回重锚前的旧值。
        val resolvedMediaNs = runFrozenWatchdog(frameTimeNs, sample, mediaNs)
        lastSampleAgeNs = (frameTimeNs - sample.sampledAtRealtimeNs).coerceAtLeast(0L)
        return resolvedMediaNs
    }

    /**
     * 冻结看门狗：播放器报告在播、但运动时钟长时间不前进时，强制重锚自愈。
     *
     * 这是**唯一**会重锚 Motion Clock 的路径（未知状态异常的最后兜底）：
     * - 仅当 [MediaClockSample.isPlaying] 为真（暂停/缓冲期的正常冻结不干预）；
     * - 仅向前重锚：Player 尚未追上时保持冻结等待，绝不后退重滚。
     */
    private fun runFrozenWatchdog(
        frameTimeNs: Long,
        sample: MediaClockSample,
        mediaNs: Long,
    ): Long {
        if (!sample.isPlaying || mediaNs != lastMediaNs) {
            lastMediaNs = mediaNs
            frozenSinceNs = null
            return mediaNs
        }
        lastMediaNs = mediaNs
        // 计时必须用 wall clock：看门狗的价值恰恰在于 presentationTime 停更时仍能兜底，
        // 若用 frameTimeNs 计时，最需要它的场景里计时会一起停摆。
        val nowNs = System.nanoTime()
        val since = frozenSinceNs
        if (since == null) {
            frozenSinceNs = nowNs
            return mediaNs
        }
        if (nowNs - since < frozenWatchdogNs) return mediaNs
        val projectedNs = projectSample(sample, frameTimeNs)
        // Player 还没走到当前冻结值：继续等，绝不向后重滚。
        if (projectedNs <= mediaNs) return mediaNs
        frozenSinceNs = nowNs
        clock.onSeek(projectedNs, frameTimeNs, sample.playbackSpeed)
        lastSpeed = sample.playbackSpeed
        reanchorCount++
        lastDecision = "watchdog-reanchor"
        return clock.mediaTimeAt(frameTimeNs)
    }

    fun diagnostics(): RenderClockDiagnostics {
        val continuous = clock as? ContinuousMediaClock
        return RenderClockDiagnostics(
            state = continuous?.playbackState?.name ?: "UNKNOWN",
            decision = lastDecision,
            sampleAgeMs = lastSampleAgeNs / 1_000_000.0,
            effectiveSpeed = continuous?.effectiveSpeed ?: lastSpeed,
            reanchorCount = reanchorCount,
        )
    }

    /** 切模式 / release / 新播放会话时重置。 */
    fun reset() {
        clock.reset()
        lastPlaying = false
        lastSpeed = 1.0
        everStarted = false
        lastSampleAgeNs = 0L
        lastDecision = "idle"
        reanchorCount = 0
        lastMediaNs = Long.MIN_VALUE
        frozenSinceNs = null
    }

    private companion object {
        const val SPEED_EPSILON = 1e-6

        /** 冻结看门狗阈值：播放中运动时钟停滞超过该时长即强制重锚。 */
        const val FROZEN_WATCHDOG_NS = 2_000_000_000L
    }
}

internal data class RenderClockDiagnostics(
    val state: String,
    val decision: String,
    val sampleAgeMs: Double,
    val effectiveSpeed: Double,
    val reanchorCount: Int,
)
