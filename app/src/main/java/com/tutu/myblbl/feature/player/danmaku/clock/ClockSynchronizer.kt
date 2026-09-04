package com.tutu.myblbl.feature.player.danmaku.clock

import kotlin.math.abs

/**
 * 样本质量：决定对样本做高置信度校正还是暂缓（定稿 §63-64）。
 */
internal enum class SampleQuality {
    /** <500ms：可做全档位校正。 */
    FRESH,

    /** 500~1500ms：只允许轻微 Slew，禁止硬 Reanchor。 */
    STALE,

    /** >1500ms：不做漂移校正，等待新 Sample（Seek 事件除外）。 */
    INVALID,
}

/**
 * 时钟同步配置：所有 magic number 集中在此（定稿 §60）。
 *
 * 第一版保守默认（定稿 §61）：15ms / 120ms / 300ms，±3% / ±5%，硬锚需连续 3 拍确认。
 */
internal data class ClockSyncConfig(
    /** |error| < 该值 → Ignore。 */
    val ignoreThresholdMs: Double = 15.0,
    /** |error| < 该值 → SOFT SLEW（correctionRate 上限 [softMaxRate]）。 */
    val softThresholdMs: Double = 120.0,
    /** |error| < 该值 → STRONG SLEW（correctionRate 上限 [strongMaxRate]）；超过需连续确认。 */
    val hardThresholdMs: Double = 300.0,
    val softMaxRate: Double = 0.03,
    val strongMaxRate: Double = 0.05,
    val softSlewDurationNs: Long = 500_000_000L,
    val strongSlewDurationNs: Long = 800_000_000L,
    /** |error| ≥ [hardThresholdMs] 时需连续 [hardConfirmCount] 拍才 Reanchor。 */
    val hardConfirmCount: Int = 3,
)

private const val FRESH_AGE_NS = 500_000_000L
private const val INVALID_AGE_NS = 1_500_000_000L

internal fun sampleQuality(sampleAgeNs: Long): SampleQuality = when {
    sampleAgeNs < FRESH_AGE_NS -> SampleQuality.FRESH
    sampleAgeNs < INVALID_AGE_NS -> SampleQuality.STALE
    else -> SampleQuality.INVALID
}

/**
 * ClockSynchronizer：时间漂移的唯一治理者（定稿 §11）。
 *
 * 职责边界：
 * - 输入：MediaClockSample + 当前显示帧时刻 + ContinuousClock 当前媒体时间；
 * - 输出：ClockSyncDecision（Ignore / Slew / Reanchor）；
 * - **禁止**直接执行 notifySeek / rebuildScene / clearDanmaku / 改走速——
 *   Decision 由上层（Controller/View）翻译成 Clock.applySyncDecision 与 Scene 行为。
 *
 * 算法（定稿 §35-38）：
 * 1. 先把 Sample 投影到当前显示帧时刻（绝不裸用旧 position 配新帧时刻）；
 * 2. |error| < 15ms → Ignore；
 * 3. 15~120ms → SOFT SLEW，maxRate=3%，Slew 结束后由下一拍根据剩余误差重算；
 * 4. 120~300ms → STRONG SLEW，maxRate=5%；
 * 5. ≥300ms：落后方向连续 3 拍后向前 Reanchor；超前方向持续 STRONG SLEW，禁止倒退；
 * 6. 每拍只存在一个 active correction（本类重算 rate，不做叠加）。
 */
internal class ClockSynchronizer(
    private val config: ClockSyncConfig = ClockSyncConfig(),
) {
    private var largeDriftStreak = 0

    /**
     * 计算本拍治理决策。
     *
     * @param sample 最新 Player 样本（position 与采样时刻成对）。
     * @param presentationTimeNs 当前显示帧时刻（与渲染所用 frameTimeNs 同源）。
     * @param danmakuMediaNs ContinuousClock.mediaTimeAt(presentationTimeNs) 的输出。
     */
    fun evaluate(
        sample: MediaClockSample,
        presentationTimeNs: Long,
        danmakuMediaNs: Long,
    ): ClockSyncDecision {
        if (sample.sequence <= 0L) return ClockSyncDecision.Ignore

        val sampleAgeNs = (presentationTimeNs - sample.sampledAtRealtimeNs).coerceAtLeast(0L)
        val quality = sampleQuality(sampleAgeNs)
        val projectedPlayerNs = projectSample(sample, presentationTimeNs)

        // 样本陈旧：不做高置信校正（定稿 §63-64）。
        if (quality == SampleQuality.INVALID) {
            largeDriftStreak = 0
            return ClockSyncDecision.Ignore
        }
        if (quality == SampleQuality.STALE) {
            // 只允许轻微 Slew：按 SOFT 上限，且不触发 Reanchor。
            val staleErrorNs = projectedPlayerNs - danmakuMediaNs
            val staleErrorMs = staleErrorNs / 1_000_000.0
            return if (abs(staleErrorMs) < config.ignoreThresholdMs) {
                largeDriftStreak = 0
                ClockSyncDecision.Ignore
            } else {
                largeDriftStreak = 0
                ClockSyncDecision.Slew(
                    errorNs = staleErrorNs,
                    correctionRate = calculateRate(staleErrorNs, config.softMaxRate),
                    durationNs = config.softSlewDurationNs,
                )
            }
        }

        val errorNs = projectedPlayerNs - danmakuMediaNs
        val errorMs = errorNs / 1_000_000.0
        val absErrorMs = abs(errorMs)

        return when {
            absErrorMs < config.ignoreThresholdMs -> {
                largeDriftStreak = 0
                ClockSyncDecision.Ignore
            }

            absErrorMs < config.softThresholdMs -> {
                largeDriftStreak = 0
                ClockSyncDecision.Slew(
                    errorNs = errorNs,
                    correctionRate = calculateRate(errorNs, config.softMaxRate),
                    durationNs = config.softSlewDurationNs,
                )
            }

            absErrorMs < config.hardThresholdMs -> {
                largeDriftStreak = 0
                ClockSyncDecision.Slew(
                    errorNs = errorNs,
                    correctionRate = calculateRate(errorNs, config.strongMaxRate),
                    durationNs = config.strongSlewDurationNs,
                )
            }

            else -> {
                // ≥300ms：向前跳变需连续确认后才 Reanchor。确认期仍做有界
                // Slew，不让 Ignore 误表达成“取消已有校正”。
                // Clock 超前时禁止向后 Reanchor，直接持续减速追赶，避免进入
                // PAUSED 后依赖 2s 看门狗解冻。
                if (errorNs < 0L) {
                    largeDriftStreak = 0
                    ClockSyncDecision.Slew(
                        errorNs = errorNs,
                        correctionRate = calculateRate(errorNs, config.strongMaxRate),
                        durationNs = config.strongSlewDurationNs,
                    )
                } else {
                    largeDriftStreak++
                    if (largeDriftStreak >= config.hardConfirmCount) {
                        largeDriftStreak = 0
                        ClockSyncDecision.Reanchor(targetMediaNs = projectedPlayerNs)
                    } else {
                        ClockSyncDecision.Slew(
                            errorNs = errorNs,
                            correctionRate = calculateRate(errorNs, config.strongMaxRate),
                            durationNs = config.strongSlewDurationNs,
                        )
                    }
                }
            }
        }
    }

    /** 显式 Player Event（seek / discontinuity / timeline 变化）后重置连续计数。 */
    fun reset() {
        largeDriftStreak = 0
    }

    /**
     * 误差 → 校正率：errorMs/2000 → +20ms→+1%、+60ms→+3%、+200ms→clamp 5%
     * （定稿 §36）。负数超前 → 负 rate 减速等 Player 追上。
     */
    private fun calculateRate(errorNs: Long, maxRate: Double): Double {
        val errorMs = errorNs / 1_000_000.0
        val raw = errorMs / 2_000.0
        return raw.coerceIn(-maxRate, maxRate)
    }
}
