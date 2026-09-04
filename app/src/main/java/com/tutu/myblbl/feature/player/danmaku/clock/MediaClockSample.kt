package com.tutu.myblbl.feature.player.danmaku.clock

/**
 * Player 媒体时钟采样：position 必须与采样时刻成对绑定（定稿约束 2）。
 *
 * 由 Player Adapter（如 MyPlayerView 的 MediaClockSampleProvider）在主线程采样，
 * 渲染线程只读取本样本并投影到当前显示帧时刻，绝不把裸 position 与新帧时刻直接配对。
 *
 * @param mediaPositionNs 采样到的媒体时间（ns）。对应 player.currentPosition × 1e6。
 * @param sampledAtRealtimeNs 执行 position 读取的 monotonic 时刻（System.nanoTime()），
 * 建议 before/after 取中降低读取本身偏差。
 * @param playbackSpeed 采样时播放速度（1.0x/1.5x/2.0x…）。
 * @param isPlaying Player 是否实际推进媒体时间（解码播放中）。
 * @param playbackState Player 状态码（IDLE/BUFFERING/READY/ENDED，由 Adapter 映射写入，
 * 本层不依赖 media3）。
 * @param sequence 采样序号：用于识别新样本，避免对同一份数据重复同步。
 */
internal data class MediaClockSample(
    val mediaPositionNs: Long,
    val sampledAtRealtimeNs: Long,
    val playbackSpeed: Double,
    val isPlaying: Boolean,
    val playbackState: Int,
    val sequence: Long,
)

/**
 * 把 Player Sample 投影到目标显示帧时刻（定稿约束 2）。
 *
 * 播放中：media + (target - sampledAt) × speed；暂停中：返回采样位置本身。
 * 纯函数，无副作用。
 *
 * 反例（禁止）：sample=10s@T0 直接拿去与 mediaTimeAt(T0+80ms) 比较，
 * 会凭空制造约 80ms 的 Clock Error。
 */
internal fun projectSample(
    sample: MediaClockSample,
    targetRealtimeNs: Long,
): Long {
    if (!sample.isPlaying) return sample.mediaPositionNs
    val deltaNs = (targetRealtimeNs - sample.sampledAtRealtimeNs).coerceAtLeast(0L)
    return sample.mediaPositionNs + (deltaNs * sample.playbackSpeed).toLong()
}
