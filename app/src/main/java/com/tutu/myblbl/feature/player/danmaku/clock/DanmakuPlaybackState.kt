package com.tutu.myblbl.feature.player.danmaku.clock

/**
 * 弹幕播放状态机（定稿 §39）。
 *
 * Clock 行为映射：
 * - IDLE / PREPARED：reset 或 frozen，无外推；
 * - PLAYING：Anchor 外推；
 * - PAUSED / BUFFERING：frozen（冻结当前 Continuous Clock，不吸附 raw sample）；
 * - SEEKING：frozen，等待新 Anchor（READY / isPlaying 后 onResume 重建）；
 * - RELEASED：无效。
 */
internal enum class DanmakuPlaybackState {
    IDLE,
    PREPARED,
    PLAYING,
    PAUSED,
    BUFFERING,
    SEEKING,
    RELEASED,
}
