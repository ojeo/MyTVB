package com.tutu.myblbl.feature.player.danmaku.clock

/**
 * 连续媒体时钟接口（定稿 §5）。
 *
 * 核心不变量：
 * 1. [mediaTimeAt] 是幂等纯读取——相同 Clock State + 相同 Presentation Time
 *    必然得到相同 Media Time；读取行为绝不修改内部状态（定稿约束 3 / Invariant 2-4）。
 * 2. PLAYING 期间媒体时间单调不减（Invariant 1）。
 * 3. Pause 后媒体时间固定（Invariant 5），Resume 连续（Invariant 6），
 *    Speed change 位置连续（Invariant 7）。
 * 4. 只有 [onSeek] 拥有「媒体时间瞬时向后跳变」权限（Invariant 8 / 定稿约束 4）。
 *
 * 所有状态变更方法接收统一 presentationTimeNs（同一渲染帧内与渲染使用的
 * frameTimeNs 相同），保证采样投影与时钟外推处于同一 monotonic 时间域。
 */
internal interface DanmakuClock {

    /**
     * 在本帧读取媒体时间前推进有限期内部状态（例如结束 Slew）。
     *
     * 状态推进与 [mediaTimeAt] 分离，保证后者仍是无副作用的纯读取。
     */
    fun onFrame(presentationTimeNs: Long) = Unit

    /** 纯读取：给定显示帧时刻返回连续媒体时间（ns）。禁止副作用。 */
    fun mediaTimeAt(presentationTimeNs: Long): Long

    fun onPlay(sample: MediaClockSample, presentationTimeNs: Long)

    /** 冻结当前 Continuous Clock 输出（而非吸附 raw sample）。 */
    fun onPause(presentationTimeNs: Long)

    fun onResume(sample: MediaClockSample, presentationTimeNs: Long)

    /** 显式 Seek：硬重锚 + 唯一允许向后跳变的入口（配套 Scene Rebuild）。 */
    fun onSeek(mediaPositionNs: Long, presentationTimeNs: Long, playbackSpeed: Double)

    /** 速度变化：先取当前连续时间再重锚，保证位置连续、仅导数变化。 */
    fun onPlaybackSpeedChanged(newSpeed: Double, presentationTimeNs: Long)

    /** ClockSynchronizer 唯一入口；禁止其它模块直接改走速。 */
    fun applySyncDecision(decision: ClockSyncDecision, presentationTimeNs: Long)

    fun reset()
}
