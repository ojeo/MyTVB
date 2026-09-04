package com.tutu.myblbl.feature.player.danmaku.clock

import kotlin.math.abs

/**
 * RenderThread 时钟驱动桥：把「轮询式播放状态快照 + 显式事件」翻译为
 * `ContinuousMediaClock` 的事件，并维持「高频 Motion / 低频 Governance」分离
 * （定稿 §34-35）。
 *
 * 职责：
 * - 高频（每渲染帧）：`clock.mediaTimeAt(presentationTimeNs)` → 返回媒体位置 ms，
 *   供 engine.stepTime / Motion 消费；
 * - 低频（每 [syncIntervalNs] 一拍）：仅消费新的 Player Sample，
 *   `ClockSynchronizer.evaluate` 输出 Decision，交回 Clock 执行（唯一治理者）；
 * - 播放状态边沿：isPlaying false→true（首播 onPlay / 恢复 onResume）、
 *   true→false（onPause 冻结当前连续时间，不吸附 raw）；
 * - 显式 Seek：经 [advance] 的 [pendingSeekMediaNs] 注入，硬锚；若播放器尚未推进
 *   （seek/buffer 间隙）则冻结在目标，等 isPlaying 边沿恢复（定稿约束 4）。
 *
 * 线程：本类仅由渲染线程调用（与 `ContinuousMediaClock` 同线程串行）；重置由外部
 * 切模式 / release 时经 renderHandler.post 触发。
 */
internal class RenderClockDriver(
    private val clock: DanmakuClock = ContinuousMediaClock(),
    private val synchronizer: ClockSynchronizer = ClockSynchronizer(),
    private val syncIntervalNs: Long = DEFAULT_SYNC_INTERVAL_NS,
    private val frozenWatchdogNs: Long = FROZEN_WATCHDOG_NS,
) {
    private var lastPlaying = false
    private var lastSpeed = 1.0
    private var everStarted = false
    private var lastSyncAtNs = Long.MIN_VALUE
    private var lastGovernedSampleSequence = Long.MIN_VALUE

    // ---- 低成本诊断：热路径只写原语，字符串快照仅由 2Hz 浮层/3s 日志读取 ----
    private var lastSampleAgeNs = 0L
    private var lastClockErrorNs = 0L
    private var lastDecision = "idle"
    private var reanchorCount = 0
    private var duplicateSampleSkipCount = 0L

    // ---- 冻结看门狗（真实 Pause/Resume 状态异常的最后兜底）----
    private var lastMediaNs = Long.MIN_VALUE
    private var frozenSinceNs: Long? = null

    /**
     * 每渲染帧调用一次（RenderThread）。
     *
     * @param frameTimeNs 本帧 presentationTimeNs（Choreographer frameTimeNs，与渲染同源）。
     * @param sample 最近一次 Player 采样（position 与采样时刻成对）。
     * @param pendingSeekMediaNs 非空表示本帧需执行显式 Seek 硬锚（一次性，调用后由外部置空）。
     * @return 连续媒体位置（ns），供 engine.stepTime 拆分为整数 ms 调度 + 亚毫秒渲染位置。
     */
    fun advance(
        frameTimeNs: Long,
        sample: MediaClockSample,
        pendingSeekMediaNs: Long?,
    ): Long {
        // mediaTimeAt 保持纯读；Slew 到期这类有限期状态在单一渲染线程显式推进。
        val correctionBeforeFrame = (clock as? ContinuousMediaClock)?.correctionRate ?: 0.0
        clock.onFrame(frameTimeNs)
        if (correctionBeforeFrame != 0.0 && (clock as? ContinuousMediaClock)?.correctionRate == 0.0) {
            lastDecision = "slew-expired"
        }
        if (pendingSeekMediaNs != null) {
            // 显式 Seek：硬锚到目标；播放器未推进则冻结在目标等待 isPlaying 恢复。
            clock.onSeek(pendingSeekMediaNs.coerceAtLeast(0L), frameTimeNs, sample.playbackSpeed)
            if (!sample.isPlaying) clock.onPause(frameTimeNs)
            lastPlaying = sample.isPlaying
            everStarted = true
            lastSpeed = sample.playbackSpeed
            lastSyncAtNs = frameTimeNs
            lastGovernedSampleSequence = sample.sequence
            synchronizer.reset()
            lastDecision = "seek"
        } else {
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
                lastSyncAtNs = frameTimeNs
                lastGovernedSampleSequence = sample.sequence
                synchronizer.reset()
            }
            if (playing && abs(sample.playbackSpeed - lastSpeed) > SPEED_EPSILON) {
                clock.onPlaybackSpeedChanged(sample.playbackSpeed, frameTimeNs)
                lastSpeed = sample.playbackSpeed
                lastDecision = "speed"
            }
            // 低频 Governance：400ms 一拍，唯一治理者。
            if (playing && frameTimeNs - lastSyncAtNs >= syncIntervalNs && sample.sequence > 0L) {
                if (sample.sequence != lastGovernedSampleSequence) {
                    lastSyncAtNs = frameTimeNs
                    lastGovernedSampleSequence = sample.sequence
                    val danmakuMediaNs = clock.mediaTimeAt(frameTimeNs)
                    val decision = synchronizer.evaluate(sample, frameTimeNs, danmakuMediaNs)
                    clock.applySyncDecision(decision, frameTimeNs)
                    lastDecision = when (decision) {
                        ClockSyncDecision.Ignore -> "ignore"
                        is ClockSyncDecision.Slew -> "slew"
                        is ClockSyncDecision.Reanchor -> {
                            reanchorCount++
                            "reanchor"
                        }
                    }
                } else {
                    duplicateSampleSkipCount++
                }
            }
        }
        val mediaNs = clock.mediaTimeAt(frameTimeNs)
        // 看门狗可能重锚，必须取它解析后的媒体时间，否则本帧仍返回重锚前的旧值。
        // 返回纳秒：整数 ms 截断会量化 60Hz 每帧 16.67ms 为 16/17ms 交替，
        // 使滚动位移周期性摆动（约 ±6% 速度波动，视觉"一顿一顿"）。由 engine.stepTime 再拆分。
        val resolvedMediaNs = runFrozenWatchdog(frameTimeNs, sample, mediaNs)
        lastSampleAgeNs = (frameTimeNs - sample.sampledAtRealtimeNs).coerceAtLeast(0L)
        lastClockErrorNs = projectSample(sample, frameTimeNs) - resolvedMediaNs
        return resolvedMediaNs
    }

    /**
     * 冻结看门狗：播放器报告在播、但连续媒体时间长时间不前进时，强制重锚自愈。
     *
     * 历史上负向 Reanchor 曾把 Clock 设为 PAUSED，而 Player 的 isPlaying 没有变化，
     * 导致冻结无法通过播放边沿解除。该路径已改为有界减速；本看门狗仅保留为未知
     * 状态异常的最后兜底。
     *
     * 本看门狗只认「媒体时间不前进」这一事实，不依赖节拍，作为终态兜底：
     * - 仅当 [MediaClockSample.isPlaying] 为真（暂停/缓冲期的正常冻结不干预）；
     * - 仅向前重锚：Player 尚未追上时保持冻结等待（定稿约束 3/4，绝不后退重滚）。
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
        lastSyncAtNs = frameTimeNs
        synchronizer.reset()
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
            errorMs = lastClockErrorNs / 1_000_000.0,
            correctionPercent = (continuous?.correctionRate ?: 0.0) * 100.0,
            effectiveSpeed = continuous?.effectiveSpeed ?: lastSpeed,
            reanchorCount = reanchorCount,
            duplicateSampleSkipCount = duplicateSampleSkipCount,
        )
    }

    /** 当前连续媒体位置（ms，只读，无副作用）。 */
    fun currentMediaPositionMs(frameTimeNs: Long): Long =
        clock.mediaTimeAt(frameTimeNs) / 1_000_000L

    /** 切模式 / release / 新播放会话时重置。 */
    fun reset() {
        clock.reset()
        synchronizer.reset()
        lastPlaying = false
        lastSpeed = 1.0
        everStarted = false
        lastSyncAtNs = Long.MIN_VALUE
        lastGovernedSampleSequence = Long.MIN_VALUE
        lastSampleAgeNs = 0L
        lastClockErrorNs = 0L
        lastDecision = "idle"
        reanchorCount = 0
        duplicateSampleSkipCount = 0L
        lastMediaNs = Long.MIN_VALUE
        frozenSinceNs = null
    }

    private companion object {
        const val DEFAULT_SYNC_INTERVAL_NS = 400_000_000L
        const val SPEED_EPSILON = 1e-6

        /** 冻结看门狗阈值：播放中媒体时间停滞超过该时长即强制重锚。 */
        const val FROZEN_WATCHDOG_NS = 2_000_000_000L
    }
}

internal data class RenderClockDiagnostics(
    val state: String,
    val decision: String,
    val sampleAgeMs: Double,
    val errorMs: Double,
    val correctionPercent: Double,
    val effectiveSpeed: Double,
    val reanchorCount: Int,
    val duplicateSampleSkipCount: Long,
)
