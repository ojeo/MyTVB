package com.tutu.myblbl.feature.player.danmaku.clock

/**
 * 弹幕时钟模式（定稿 §60 A/B 开关）。
 *
 * - [LEGACY]：旧 `DanmakuTimer` 增量积分 + 追赶校准（现状，默认），行为不变；
 * - [CONTINUOUS]：新 `ContinuousMediaClock`（Anchor + 外推纯函数）+ `ClockSynchronizer`
 *   唯一治理者，统一以 presentationTimeNs 推进。
 *
 * 每阶段可运行时切换；同一视频同一弹幕可做 A/B 对比（P1 验收）。
 * 该枚举暴露在公开 API（DanmakuRenderHost/MyPlayerView）中，故为 public。
 */
enum class DanmakuClockMode {
    LEGACY,
    CONTINUOUS,
}
