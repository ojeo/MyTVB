package com.mytvb.feature.player.danmaku.clock

/**
 * ClockSynchronizer 输出的时钟治理决策（定稿 §12）。
 *
 * Clock Decision 与 Scene Decision 分离：本接口只表达「时钟怎么走」，
 * 不含 notifySeek / rebuildScene / clearDanmaku 等 Scene 行为。
 *
 * - [Ignore]：误差在死区内，结束现有临时校正并恢复用户倍速（避免微校准 jitter）。
 * - [Slew]：走速校正。通过临时改变 Continuous Clock 的走速（加速/减速）
 *   逐步消除相位误差，媒体时间不瞬移。每拍重新计算唯一 correction rate，不叠加。
 * - [Reanchor]：硬重锚到目标媒体时间，仅允许向前；防御性收到负向目标时 Clock
 *   使用有界减速，绝不后退重滚。显式 Seek 的向后跳变走 DanmakuClock.onSeek()，
 *   不经过本决策（定稿约束 3/4）。
 */
internal sealed interface ClockSyncDecision {

    data object Ignore : ClockSyncDecision

    data class Slew(
        /** 相位误差（ns），>0 = Clock 落后 Player，需加速；<0 = 超前，需减速。 */
        val errorNs: Long,
        /** 相对用户倍速的校正率（如 0.03 = +3%），已 clamp 到配置上限。 */
        val correctionRate: Double,
        /** 本次 Slew 的最长持续时间（ns）；到期后 Clock 自动恢复用户倍速。 */
        val durationNs: Long,
    ) : ClockSyncDecision

    data class Reanchor(
        /** 目标媒体时间（ns），必须已投影到当前显示帧时刻。 */
        val targetMediaNs: Long,
    ) : ClockSyncDecision
}
