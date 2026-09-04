package com.tutu.myblbl.feature.player.danmaku.clock

/**
 * 显示帧时间解析（定稿 §16-18）。
 *
 * 目标：Clock 层只消费抽象的「presentationTimeNs」，不感知 Android API。
 * 首版统一使用 Choreographer `frameTimeNs`（定稿约束 1）；
 * API 33+ 的 FrameTimeline `expectedPresentationTimeNanos` 作为后续精度增强接入，
 * 届时由上层（如 GlDanmakuView 的 VsyncCallback）把期望呈现时刻注入 [resolve]，
 * 本解析器完成「有期望时刻优先用期望时刻，否则用 legacy frameTimeNs」的唯一决策。
 *
 * 同一渲染帧内 Sample 投影 / Clock / Motion / Profiler 必须共用 resolve 的结果，
 * 禁止再各自取不同 realtime 源（定稿约束 1/19）。
 */
internal object PresentationTimestampResolver {

    /**
     * 解析本帧的呈现时间（ns）。
     *
     * @param expectedPresentationTimeNs API 33+ FrameTimeline 的期望呈现时刻；
     * ≤0 表示当前帧未提供（API<33 或无 VsyncCallback），回退 [legacyFrameTimeNs]。
     * @param legacyFrameTimeNs Choreographer.FrameCallback.frameTimeNanos。
     */
    fun resolve(
        expectedPresentationTimeNs: Long,
        legacyFrameTimeNs: Long,
    ): Long = if (expectedPresentationTimeNs > 0L) {
        expectedPresentationTimeNs
    } else {
        legacyFrameTimeNs.coerceAtLeast(0L)
    }
}
