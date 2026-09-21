package com.mytvb.feature.player.danmaku

/**
 * 弹幕渲染质量（全局设置「设置-弹幕-弹幕渲染质量」）。
 *
 * GPU 弹幕层是叠在视频之上的**整屏透明 Surface**，其光栅化分辨率直接决定：
 * - SurfaceFlinger 每帧要混合多少像素（填充率 / 内存带宽）；
 * - RenderThread 每帧的实例构建与纹理采样开销。
 *
 * 弱机（尤其电视盒子）上这两项会与视频解码争抢 GPU 与带宽，因此提供手动档位，
 * 让用户主动用"弹幕清晰度"换"视频流畅度"——这与 §0.3 的保播放降载是同一取舍，
 * 区别在于降载是自动的临时措施，本设置是用户长期偏好。
 *
 * [scaleFactor] 是**相对设备基准分辨率**的系数，而不是绝对分辨率：
 * 设备基准为 TV 0.75 / 手机平板 1.0（见 `GlDanmakuView.deviceRenderScale`），
 * 所以 [HIGH] 在 TV 上等价于改造前的默认行为，升级不会改变既有画质与负载。
 * 最低档在 TV 上约落在 0.50，与降载下限 `MIN_RENDER_SCALE` 对齐。
 *
 * [prefValue] 与设置项展示文案一致（项目内弹幕设置均为「存即显示」口径），
 * 由 [fromPref] 反向解析，未知值一律回退 [HIGH]。
 *
 * 生效时机：与「弹幕渲染引擎」相同，**下一次进入播放页**生效
 * （弹幕层 buffer 尺寸在首次 measure 时确定，会话中途改动会触发 Surface 重建）。
 */
enum class DanmakuRenderQuality(
    val prefValue: String,
    val scaleFactor: Float,
) {
    /** 高清（默认）：保持设备基准分辨率，与改造前行为一致。 */
    HIGH("高清", 1.0f),

    /** 均衡：分辨率降约 15%，清晰度损失轻微，填充率与带宽明显下降。 */
    BALANCED("均衡", 0.85f),

    /** 流畅优先：分辨率降约 33%，弱机上优先保证视频不掉帧。 */
    SMOOTH("流畅优先", 0.67f),
    ;

    companion object {
        /** 默认档：未知/未设置时一律走高清（等价于改造前行为）。 */
        val DEFAULT: DanmakuRenderQuality = HIGH

        /** 设置项展示顺序（SettingsFragment 的选项数组直接由它派生，避免两处失配）。 */
        val OPTIONS: Array<String> = values().map { it.prefValue }.toTypedArray()

        fun fromPref(value: String?): DanmakuRenderQuality =
            values().firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
