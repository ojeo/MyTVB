package com.tutu.myblbl.feature.keybinding

/**
 * 快捷键绑定的两个场景。两个场景的映射表互相独立，
 * 同一个物理按键可以在两个场景分别绑定不同功能，互不冲突。
 */
enum class KeyBindingScene(
    /** 持久化 key 前缀。 */
    val storagePrefix: String,
    /** 设置页分组标题。 */
    val displayName: String
) {
    /** 场景 A：视频播放中（PlayerActivity 前台）。 */
    PLAYER("kb_player_", "视频播放中"),

    /** 场景 B：非播放（MainActivity 前台）。 */
    GLOBAL("kb_global_", "非播放（主界面）");
}
