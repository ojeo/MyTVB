package com.mytvb.feature.player.danmaku.render

/**
 * 弹幕渲染帧率节流器（RenderThread 独占，无锁）。
 *
 * 背景：GPU 弹幕层是叠在视频之上的透明 Surface，每提交一帧都会让 SurfaceFlinger
 * 多混合一整屏；而直播内容多为 30fps，弹幕跟着 60Hz 提交收益很低、代价很高。
 * 本类把提交节奏压到 [targetFps]，其余 vsync 只续帧不渲染。
 *
 * 只影响"提交节奏"，不影响弹幕位置：位置仍由时钟层按真实时间推进，
 * 因此降帧后单帧位移变大（30Hz 约为 60Hz 的 2 倍），但不会出现时间倒流或跳跃。
 *
 * 历史：本文件出现前，GlDanmakuView 里的 `targetRenderFps = 60` 只是日志字段，
 * 弹幕层恒定按 vsync 提交；保播放降载需要它真实生效，故抽成独立可测组件。
 */
internal class DanmakuRenderPacer {

    /** 目标提交帧率（Hz）。由保播放降载策略写入（主线程），RenderThread 读取。 */
    @Volatile
    var targetFps: Int = DEFAULT_FPS

    private var lastRenderedNs: Long = Long.MIN_VALUE

    /**
     * 是否应在本 vsync 渲染一帧。返回 false 时调用方只续帧、不提交，
     * 从而把弹幕层的提交频率压到 [targetFps]。
     */
    fun shouldRender(frameTimeNanos: Long): Boolean {
        val fps = targetFps.coerceIn(MIN_FPS, MAX_FPS)
        if (fps >= MAX_FPS) {
            lastRenderedNs = frameTimeNanos
            return true
        }
        val minIntervalNs = 1_000_000_000L / fps - TOLERANCE_NS
        val last = lastRenderedNs
        // frameTimeNanos 倒退只可能出现在时间域被重置时（换播放源/测试重启计时），
        // 此时当作欠采样处理并重新起算，绝不能因为"上一帧时间戳在未来"而长期不放行。
        if (last == Long.MIN_VALUE || frameTimeNanos < last || frameTimeNanos - last >= minIntervalNs) {
            lastRenderedNs = frameTimeNanos
            return true
        }
        return false
    }

    /** 重新起算节拍（渲染循环重启后避免用陈旧时间戳误判）。 */
    fun reset() {
        lastRenderedNs = Long.MIN_VALUE
    }

    private companion object {
        const val DEFAULT_FPS = 60
        const val MIN_FPS = 15
        const val MAX_FPS = 120

        /**
         * 判定容差：吸收 59.94Hz 面板与 Choreographer 抖动。
         * 没有它时 60fps 目标在 59.94Hz 上会被周期性判掉一帧。
         */
        const val TOLERANCE_NS = 2_000_000L
    }
}
