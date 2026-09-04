package com.tutu.myblbl.feature.player.danmaku.clock

/**
 * 媒体时钟锚点：连续媒体时间的外推基准（定稿 §6）。
 *
 * @param mediaTimeNs 建立锚点时的媒体时间（ns）。
 * @param realtimeNs 建立锚点时的「显示帧时刻」（Presentation Time，Choreographer
 * frameTimeNs / API33 expectedPresentationTimeNanos）。同一渲染帧内所有模块
 * （Sample 投影 / Clock / Motion / Profiler）必须共用该时刻（定稿约束 1）。
 * @param playbackSpeed 当前有效走速（用户倍速 × (1 + Slew 校正率)）。
 */
internal data class MediaClockAnchor(
    val mediaTimeNs: Long,
    val realtimeNs: Long,
    val playbackSpeed: Double,
)
