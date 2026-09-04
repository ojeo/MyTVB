package com.tutu.myblbl.feature.player.danmaku

import com.tutu.myblbl.core.common.log.AppLog
import kotlin.math.abs
import kotlin.math.min

/**
 * AkDanmaku-style timer（LEGACY 兼容层，P8 标记废弃）:
 * - Uses System.nanoTime() for smooth advancement.
 * - Prioritizes a monotonic local clock while playing for stable motion.
 * - Re-anchors hard only on explicit playback events (seek).
 * - Ordinary lag in Player.currentPosition never pulls the local clock backwards.
 * - Only a forward media-clock gap is corrected, with a bounded extra rate.
 *
 * 已被 `clock/ContinuousMediaClock`（Anchor + 外推纯函数）+ `ClockSynchronizer`
 * （唯一治理者）取代。当前仅作为 `DanmakuClockMode.LEGACY` 的兼容/回退实现保留，
 * 生产默认已切 `CONTINUOUS`（见 docs/弹幕双时钟与连续媒体时钟实现方案.md §0.7/P8）。
 * 真机验证（P5/P6）通过后删除本类与其调用分支。
 */
@Deprecated("LEGACY 兼容层：由 ContinuousMediaClock + ClockSynchronizer 取代，验证后退役")
internal class DanmakuTimer {
    @Volatile
    private var lastFrameNanos: Long = 0L

    @Volatile
    private var smoothPositionMs: Double = 0.0

    @Volatile
    private var lastSeekSerial: Int = 0

    @Volatile
    private var lastPlaying: Boolean = false

    @Volatile
    private var lastPlaybackSpeed: Double = 1.0

    /**
     * 漂移监督器的软同步因子（1 ± 5%）：只缩放每帧推进量，不参与
     * speed 变化判定（真实倍速变化才允许重锚到 raw），否则每次微调都会
     * 触发 anchor kind=speed 的硬重锚，软同步就退化成硬跳变。
     */
    @Volatile
    var softSyncFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(0.9, 1.1)
        }

    /** 渐进追赶进行中（未吸合）：用于日志只记首尾、不逐帧刷屏。 */
    @Volatile
    private var correcting: Boolean = false

    /**
     * 轻量时钟校准（硬同步）：只移动平滑位置，不动 lastFrameNanos，
     * dt 连续性保持。与 reset() 的区别是不打 anchor 日志、不算 seek。
     * 回退方向的校准由引擎 stepTime 的单调钳制吸收（在屏弹幕冻结等 raw 追上，
     * 而不是位置倒跳重滚一遍）。
     */
    fun syncTo(positionMs: Long) {
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        correcting = false
    }

    fun reset(
        positionMs: Long,
        nowNanos: Long,
        seekSerial: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ) {
        lastFrameNanos = nowNanos
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        lastSeekSerial = seekSerial
        lastPlaying = isPlaying
        lastPlaybackSpeed = normalizeSpeed(playbackSpeed)
        correcting = false
    }

    fun currentPositionMs(): Long = smoothPositionMs.toLong()

    fun step(
        nowNanos: Long,
        rawPositionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        seekSerial: Int,
    ): Long {
        val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
        val speed = normalizeSpeed(playbackSpeed)
        val lastNanos = lastFrameNanos

        if (lastNanos == 0L || seekSerial != lastSeekSerial) {
            val firstInit = lastNanos == 0L
            val before = smoothPositionMs
            reset(
                positionMs = rawPositionMs,
                nowNanos = nowNanos,
                seekSerial = seekSerial,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
            )
            AppLog.i(
                DIAG_TAG,
                "anchor kind=${if (firstInit) "init" else "seekSerial"} " +
                    "before=${before.toLong()}ms after=${smoothPositionMs.toLong()}ms " +
                    "delta=${deltaLabel(smoothPositionMs - before)} play=$isPlaying"
            )
            return smoothPositionMs.toLong()
        }

        val dtNanos = (nowNanos - lastNanos).coerceAtLeast(0L)
        val dtMs = dtNanos.toDouble() / 1_000_000.0
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        if (!isPlaying) {
            // 暂停/恢复瞬间 ExoPlayer 的 raw position 常会回退几十~上百毫秒
            // （解码器缓冲固有行为）。暂停瞬间保留当前平滑位置不动，绝不回退。
            if (lastPlaying) {
                // 刚从播放切到暂停：保持弹幕停在当前平滑位置，绝不回退。
                // 记录暂停边界的 raw 差值：恢复瞬间若偏差偏大，这里是上游证据。
                AppLog.i(
                    DIAG_TAG,
                    "pause-edge smooth=${smoothPositionMs.toLong()}ms raw=${raw.toLong()}ms " +
                        "rawDelta=${deltaLabel(raw - smoothPositionMs)}"
                )
            }
            // Paused/buffering means frozen danmaku. Do not move toward a possibly speculative
            // decoder position while there is no presented playback progress.
            correcting = false
            lastPlaying = false
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        // 恢复播放/倍速变化：不再一次性硬锚到 raw（吸收 PR #53 的收敛思路）——
        // 缓冲期间弹幕停在原地而 raw 已前进时，硬锚会让屏上所有弹幕同帧整体前移
        // （"统一大跳"）。这里只记录偏差，交由下方连续渐进追赶无感吸收。
        if (!lastPlaying || abs(speed - lastPlaybackSpeed) >= SPEED_CHANGE_EPSILON) {
            val kind = if (!lastPlaying) "resume" else "speed"
            AppLog.i(
                DIAG_TAG,
                "anchor kind=$kind smooth=${smoothPositionMs.toLong()}ms raw=${raw.toLong()}ms " +
                    "gap=${deltaLabel(raw - smoothPositionMs)} speed=$speed"
            )
            lastPlaying = true
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        if (dtNanos > 0L) {
            smoothPositionMs += dtMs * speed * softSyncFactor
        }

        // Clamp for safety.
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) {
            smoothPositionMs = raw
            correcting = false
        }
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0

        // Player.currentPosition is a sampled media clock and can temporarily stall by 250ms even
        // while playback is healthy. Pulling the smooth clock backwards in response produced the
        // observed periodic freeze/jump. Correct only when the media clock is genuinely ahead.
        val gap = raw - smoothPositionMs
        if (!correcting && gap >= CATCH_UP_ENGAGE_GAP_MS) {
            correcting = true
            AppLog.i(
                DIAG_TAG,
                "anchor kind=catchup-forward-begin gap=${deltaLabel(gap)} " +
                    "smooth=${smoothPositionMs.toLong()}ms raw=${raw.toLong()}ms"
            )
        }
        if (correcting) {
            runForwardCatchUp(raw, dtMs, speed)
        }

        lastPlaying = true
        lastPlaybackSpeed = speed
        return smoothPositionMs.toLong()
    }

    /** Forward-only bounded correction; never freezes or rewinds on a stale raw sample. */
    private fun runForwardCatchUp(raw: Double, deltaMs: Double, speed: Double) {
        val gap = raw - smoothPositionMs
        if (gap >= HARD_REANCHOR_GAP_MS) {
            val before = smoothPositionMs
            smoothPositionMs = raw
            correcting = false
            AppLog.w(
                DIAG_TAG,
                "anchor kind=catchup-hard before=${before.toLong()}ms " +
                    "after=${smoothPositionMs.toLong()}ms delta=${deltaLabel(smoothPositionMs - before)}"
            )
            return
        }
        if (gap <= CATCH_UP_TOLERANCE_MS) {
            correcting = false
            AppLog.i(
                DIAG_TAG,
                "anchor kind=catchup-forward-end gap=${deltaLabel(gap)} " +
                    "smooth=${smoothPositionMs.toLong()}ms"
            )
            return
        }
        val maxCorrectionMs = deltaMs.coerceAtLeast(0.0) * speed * MAX_FORWARD_CORRECTION_RATE
        smoothPositionMs += min(gap - CATCH_UP_TOLERANCE_MS, maxCorrectionMs)
    }

    private fun deltaLabel(delta: Double): String =
        (if (delta >= 0) "+" else "") + delta.toLong() + "ms"

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        playbackSpeed
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 1.0

    private companion object {
        private const val DIAG_TAG = "BlblDmDiag"

        private const val SPEED_CHANGE_EPSILON = 0.0001

        // Only a forward media-clock lead can start correction. A negative gap is commonly a
        // transient stale Player.currentPosition sample and must never slow/freeze danmaku.
        private const val CATCH_UP_ENGAGE_GAP_MS = 250.0
        private const val CATCH_UP_TOLERANCE_MS = 30.0
        // Cap correction to +10% motion. Unlike exponential catch-up, the first corrected frame
        // cannot jump by a large fraction of the accumulated gap.
        private const val MAX_FORWARD_CORRECTION_RATE = 0.10
        // 超过该偏差退回一次性硬锚（监督器 >2s 的 seek 重建通常已介入，此处为兜底）。
        private const val HARD_REANCHOR_GAP_MS = 5_000.0
    }
}
