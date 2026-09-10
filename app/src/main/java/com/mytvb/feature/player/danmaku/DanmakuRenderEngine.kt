package com.mytvb.feature.player.danmaku

/**
 * 弹幕渲染引擎（全局设置「设置-弹幕-弹幕渲染引擎」）。
 *
 * - [GPU]：OpenGL 实例化渲染（[com.mytvb.feature.player.danmaku.render.GlDanmakuView]），
 *   支持防挡蒙版与双时钟 CONTINUOUS 模式，为默认项。
 * - [LITE]：CPU Canvas 渲染（上游 [DanmakuView]），无蒙版/双时钟（恒 LEGACY），
 *   但通用性更好，适合 GPU 不可用的设备。
 *
 * [prefValue] 与设置项展示文案一致（项目内弹幕设置均为「存即显示」口径），
 * 由 [DanmakuRenderEngine.fromPref] 反向解析，未知值一律回退 [GPU]。
 *
 * 切换在**下一次进入播放页**时生效：弹幕层在播放器 setup 阶段创建且不可热替换
 * （[DanmakuView] detach 即永久 release），因此不支持播放中即时重建。
 */
enum class DanmakuRenderEngine(val prefValue: String) {
    GPU("GPU引擎"),
    LITE("轻量化引擎"),
    ;

    companion object {
        /** 默认引擎：未知/未设置时一律走 GPU。 */
        val DEFAULT: DanmakuRenderEngine = GPU

        fun fromPref(value: String?): DanmakuRenderEngine =
            values().firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
