package com.mytvb.feature.player.danmaku.clock

/**
 * 连续媒体时钟实现：MediaClockAnchor + 纯函数外推（定稿 §10）。
 *
 * 结构：
 * - Anchor(mediaTimeNs, realtimeNs, playbackSpeed)；
 * - mediaTimeAt(T) = anchor.mediaTimeNs + (T - anchor.realtimeNs) × speed —— 无积分状态、纯函数；
 * - Slew 校正通过「以当前连续时间为新锚、重设走速」实现，而不是在 getter 内消耗 correction
 *   （废弃 v0.1 的 releaseCorrection 模型，定稿 §7）；
 * - 冻结（PAUSED/BUFFERING/SEEKING）输出固定值，绝不吸附 raw sample（定稿 §21）。
 *
 * 状态设计：
 * - [state] 驱动冻结语义：PLAYING 外推；其余状态冻结。
 * - 冻结值是「冻结时刻的 mediaTimeAt(T)」，与播放器暂停后不推进的媒体位置天然一致。
 *
 * 线程：本类不持有锁。接入时由单一渲染线程（RenderThread / Main onDraw）消费，
 * 状态变更（事件/Sync）与 mediaTimeAt 须经同一线程串行化（见定稿 §35）。
 */
internal class ContinuousMediaClock : DanmakuClock {

    private var anchor = MediaClockAnchor(mediaTimeNs = 0L, realtimeNs = 0L, playbackSpeed = 1.0)

    private var state: DanmakuPlaybackState = DanmakuPlaybackState.IDLE

    /** 冻结时刻的连续媒体时间（仅在非 PLAYING 状态有意义）。 */
    private var frozenMediaNs: Long = 0L

    /** 用户播放倍速（不含 Slew 校正），由 onPlaybackSpeedChanged/事件写入。 */
    private var userSpeed: Double = 1.0

    /** 当前生效 Slew 校正率（±，仅诊断用；真正走速在 [anchor]。 */
    private var currentCorrectionRate: Double = 0.0

    /** 当前 Slew 的结束显示时刻；无 Slew 时为 [Long.MIN_VALUE]。 */
    private var slewEndPresentationNs: Long = Long.MIN_VALUE

    /** 最近一次 Slew/Reanchor 记录（诊断/日志，不参与计算）。 */
    var lastSync: String? = null
        private set

    override fun onFrame(presentationTimeNs: Long) {
        if (
            state == DanmakuPlaybackState.PLAYING &&
            currentCorrectionRate != 0.0 &&
            presentationTimeNs >= slewEndPresentationNs
        ) {
            restoreUserSpeed(presentationTimeNs, "slew-expired")
        }
    }

    override fun mediaTimeAt(presentationTimeNs: Long): Long {
        if (state != DanmakuPlaybackState.PLAYING) return frozenMediaNs
        val elapsedNs = (presentationTimeNs - anchor.realtimeNs).coerceAtLeast(0L)
        return anchor.mediaTimeNs + (elapsedNs * anchor.playbackSpeed).toLong()
    }

    override fun onPlay(sample: MediaClockSample, presentationTimeNs: Long) {
        // 起播/首帧：以最新样本建立锚点。投影到当前显示帧时刻，避免样本陈旧偏差。
        userSpeed = normalizeSpeed(sample.playbackSpeed)
        val projected = projectSample(sample, presentationTimeNs)
        clearSlew()
        setPlaying(projected, presentationTimeNs)
        lastSync = "onPlay anchor=${projected / 1e6}ms"
    }

    override fun onPause(presentationTimeNs: Long) {
        // 冻结当前连续时钟输出，而不是吸附 raw player position（定稿 §21）：
        // 连续时钟可能比播放器采样超前（10016 vs 10003），吸附会造成 13ms 回退。
        frozenMediaNs = if (state == DanmakuPlaybackState.PLAYING) {
            mediaTimeAt(presentationTimeNs)
        } else {
            frozenMediaNs
        }
        state = DanmakuPlaybackState.PAUSED
        clearSlew()
        lastSync = "onPause frozen=${frozenMediaNs / 1e6}ms"
    }

    override fun onResume(sample: MediaClockSample, presentationTimeNs: Long) {
        // Resume 从冻结值继续：以 frozen 为 media 锚、当前帧时刻为 realtime 锚、
        // 用户倍速为速率，保证 pause→resume 完全连续（定稿 §22）。
        userSpeed = normalizeSpeed(sample.playbackSpeed)
        val resumeFromNs = if (state == DanmakuPlaybackState.PLAYING) {
            projectSample(sample, presentationTimeNs)
        } else {
            frozenMediaNs
        }
        clearSlew()
        setPlaying(resumeFromNs, presentationTimeNs)
        lastSync = "onResume anchor=${resumeFromNs / 1e6}ms"
    }

    override fun onSeek(mediaPositionNs: Long, presentationTimeNs: Long, playbackSpeed: Double) {
        // 显式 Seek：唯一允许媒体时间瞬时向后跳变的入口（定稿约束 4）。
        // Scene Rebuild 由上层同步执行；此处仅重锚。
        userSpeed = normalizeSpeed(playbackSpeed)
        clearSlew()
        setPlaying(mediaPositionNs, presentationTimeNs)
        lastSync = "onSeek anchor=${mediaPositionNs / 1e6}ms"
    }

    override fun onPlaybackSpeedChanged(newSpeed: Double, presentationTimeNs: Long) {
        // 速度变化：先取当前连续时间作为新锚 media，再重设速率（定稿 §25），
        // 保证位置连续、仅导数变化。Slew 校正因用户改速而清除。
        userSpeed = normalizeSpeed(newSpeed)
        if (state == DanmakuPlaybackState.PLAYING) {
            val mediaNow = mediaTimeAt(presentationTimeNs)
            clearSlew()
            setPlaying(mediaNow, presentationTimeNs)
        } else {
            // 非播放状态：冻结值不变，速率在下一次 setPlaying 时生效。
            clearSlew()
        }
        lastSync = "speed=$userSpeed anchor=${frozenMediaNs / 1e6}ms"
    }

    override fun applySyncDecision(decision: ClockSyncDecision, presentationTimeNs: Long) {
        when (decision) {
            ClockSyncDecision.Ignore -> {
                // Ignore 表示当前误差已进入死区：上一次临时 Slew 必须结束。
                // 只重锚速率、不重锚位置，因此不会造成弹幕跳变。
                if (state == DanmakuPlaybackState.PLAYING && currentCorrectionRate != 0.0) {
                    restoreUserSpeed(presentationTimeNs, "ignore")
                } else {
                    clearSlew()
                    lastSync = "ignore"
                }
            }

            is ClockSyncDecision.Slew -> {
                // 冻结（暂停/缓冲/向后重锚等待）期间不做走速校正。
                if (state != DanmakuPlaybackState.PLAYING) return
                // 走速校正：取当前连续时间 → 新 anchor，rate = userSpeed × (1+correctionRate)。
                // 不叠加多个 Slew：每拍由 ClockSynchronizer 重新计算唯一 rate。
                val mediaNow = mediaTimeAt(presentationTimeNs)
                val rate = (userSpeed * (1.0 + decision.correctionRate))
                    .coerceIn(MIN_EFFECTIVE_RATE, MAX_EFFECTIVE_RATE)
                currentCorrectionRate = decision.correctionRate
                slewEndPresentationNs = saturatingAdd(
                    presentationTimeNs,
                    decision.durationNs.coerceAtLeast(0L),
                )
                setPlaying(mediaNow, presentationTimeNs, rate)
                lastSync = "slew media=${mediaNow / 1e6}ms rate=" +
                    String.format(java.util.Locale.US, "%.1f", decision.correctionRate * 100) + "%"
            }

            is ClockSyncDecision.Reanchor -> {
                // 误差启发 Reanchor 仅允许向前；向后跳变只有 onSeek() 能做。
                // 对防御性收到的负向 Reanchor，用有界减速代替冻结。
                val current = if (state == DanmakuPlaybackState.PLAYING) {
                    mediaTimeAt(presentationTimeNs)
                } else {
                    frozenMediaNs
                }
                if (decision.targetMediaNs < current) {
                    // 播放器仍在播放时不能把 Clock 伪装成 PAUSED：RenderClockDriver 看不到
                    // isPlaying 边沿，旧实现会直到 2s 看门狗才解冻。改为有界减速，
                    // 保持位置单调且持续运动；正常同步器不会再下发负向 Reanchor。
                    if (state == DanmakuPlaybackState.PLAYING) {
                        currentCorrectionRate = -REANCHOR_BLOCKED_MAX_RATE
                        slewEndPresentationNs = saturatingAdd(
                            presentationTimeNs,
                            REANCHOR_BLOCKED_SLEW_NS,
                        )
                        setPlaying(
                            current,
                            presentationTimeNs,
                            userSpeed * (1.0 - REANCHOR_BLOCKED_MAX_RATE),
                        )
                        lastSync = "reanchor-behind target=${decision.targetMediaNs / 1e6}ms current=${
                            current / 1e6
                        }ms → slew"
                    }
                    return
                }
                // 目标 ≥ 当前（含从冻结态向前解冻）：重锚到投影目标。
                clearSlew()
                setPlaying(decision.targetMediaNs, presentationTimeNs, userSpeed)
                lastSync = "reanchor target=${decision.targetMediaNs / 1e6}ms"
            }
        }
    }

    override fun reset() {
        state = DanmakuPlaybackState.IDLE
        frozenMediaNs = 0L
        userSpeed = 1.0
        clearSlew()
        anchor = MediaClockAnchor(0L, 0L, 1.0)
        lastSync = null
    }

    // ---- internal diagnostics ----

    val playbackState: DanmakuPlaybackState
        get() = state

    val currentUserSpeed: Double
        get() = userSpeed

    val correctionRate: Double
        get() = currentCorrectionRate

    val effectiveSpeed: Double
        get() = if (state == DanmakuPlaybackState.PLAYING) anchor.playbackSpeed else 0.0

    /**
     * 将冻结位置向前推进到 [targetMs]（用于 seek 后立即回到 PLAYING）。
     * 仅供引擎重建场景后同步锚点用；非 PLAYING 时忽略。
     */
    fun reanchorForSeek(mediaPositionNs: Long, presentationTimeNs: Long) {
        setPlaying(mediaPositionNs, presentationTimeNs, userSpeed)
        clearSlew()
        lastSync = "reanchorForSeek ${mediaPositionNs / 1e6}ms"
    }

    private fun setPlaying(
        mediaNs: Long,
        realtimeNs: Long,
        rate: Double = userSpeed,
    ) {
        state = DanmakuPlaybackState.PLAYING
        frozenMediaNs = mediaNs
        anchor = MediaClockAnchor(
            mediaTimeNs = mediaNs,
            realtimeNs = realtimeNs,
            playbackSpeed = rate,
        )
    }

    private fun normalizeSpeed(speed: Double): Double =
        if (speed.isFinite() && speed > 0.0) speed else 1.0

    private fun restoreUserSpeed(presentationTimeNs: Long, reason: String) {
        if (state != DanmakuPlaybackState.PLAYING) {
            clearSlew()
            return
        }
        val mediaNow = mediaTimeAt(presentationTimeNs)
        clearSlew()
        setPlaying(mediaNow, presentationTimeNs, userSpeed)
        lastSync = "$reason baseSpeed=$userSpeed"
    }

    private fun clearSlew() {
        currentCorrectionRate = 0.0
        slewEndPresentationNs = Long.MIN_VALUE
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val MIN_EFFECTIVE_RATE = 0.5
        const val MAX_EFFECTIVE_RATE = 2.5
        const val REANCHOR_BLOCKED_MAX_RATE = 0.05
        const val REANCHOR_BLOCKED_SLEW_NS = 800_000_000L
    }
}
