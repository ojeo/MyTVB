package com.mytvb.feature.player.danmaku.model

import com.mytvb.feature.player.danmaku.Danmaku
internal enum class DanmakuKind {
    SCROLL,
    TOP,
    BOTTOM,
}

internal enum class DanmakuCacheState {
    Init,
    Rendering,
    Rendered,
}

internal class DanmakuItem(
    val data: Danmaku,
) {
    // ---- Measure/cache (updated by cache thread) ----
    @Volatile var measuredWidthPx: Float = Float.NaN
    @Volatile var measureGeneration: Int = -1

    @Volatile var cacheEntry: SharedCacheEntry? = null
    @Volatile var cacheGeneration: Int = -1
    @Volatile var pendingCacheGeneration: Int = -1
    @Volatile var cacheState: DanmakuCacheState = DanmakuCacheState.Init

    /**
     * 内联段（表情/高赞图标）解析结果缓存。emoteVersion 记录解析时的词典版本，
     * 词典加载/刷新后按版本对比失效重解析（含表情命中状态变化）；
     * showIcon 记录解析时的高赞图标开关，开关切换后同样失效。
     */
    @Volatile var inlineSegments: List<DanmakuInlineSegment>? = null
    @Volatile var inlineSegmentsEmoteVersion: Int = -1
    @Volatile var inlineSegmentsShowIcon: Boolean = true

    // ---- Active state (action thread only) ----
    var kind: DanmakuKind = DanmakuKind.SCROLL
    var lane: Int = 0

    /**
     * 建图失败（预算耗尽等）后的重试退避时间点（引擎时钟 ms，action 线程私有）。
     * 防止预算紧张时同一 item 每帧重入队空转刷日志。releaseItemCache 时归零。
     */
    var cacheRetryNotBeforeMs: Int = 0
    /**
     * 是否当前在场（action 线程私有）。activate() 置 true，所有从 active 列表移除的路径置 false。
     * 替代 O(n) 的 `item in active` 线性扫描（applyCacheResult/rebuildScene 每帧/每条都会查）。
     */
    var inActive: Boolean = false
    /**
     * 调度域入场时刻（毫秒）。**仅供日志/诊断**，不参与任何运动计算。
     * 时间语义见 danmaku/clock 解耦方案：Schedule Clock 决定"何时出现"。
     */
    @Volatile var startTimeMs: Int = 0

    /**
     * 运动域起点（纳秒）。入场（或缓存就绪重锚）时写入，此后该条弹幕的
     * 位置 / 退场 / 轨道碰撞全部只依赖 `motionNowNs - motionStartNs`，
     * **彻底不再读取播放器位置**。
     *
     * 用纳秒而非毫秒：使 draw（亚毫秒）与退场判定使用完全相同的 ΔT，
     * 消除此前 Int 毫秒带来的 ≤1ms（约 0.37px）两轨偏差。
     * 时间语义见 danmaku/clock 解耦方案：Motion Clock 决定"出现后怎么运动"。
     */
    @Volatile var motionStartNs: Long = 0L
    @Volatile var motionStarted: Boolean = false
    var durationMs: Int = 0
    var pxPerMs: Float = 0f
    var textWidthPx: Float = 0f
    var layoutTopPx: Float = 0f
    /**
     * 时间线整体替换（setDanmakus）时，若新实例与"当前仍在场的条目"内容相同（同发送时间+同内容），
     * 标记 consumed 使 rebuildScene 不再把它重新入场——否则同一条弹幕会滚两遍。
     * 用户回看（位置倒退）时忽略该标记，允许重新入场。仅替换路径设置，普通入场不置位。
     */
    var consumed: Boolean = false

    fun timeMs(): Int = data.timeMs
}
