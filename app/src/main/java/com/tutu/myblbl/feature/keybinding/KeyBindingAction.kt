package com.tutu.myblbl.feature.keybinding

/**
 * 可被遥控器按键绑定的动作。
 *
 * **声明顺序即设置页的展示顺序**，调整顺序不会影响已保存的绑定
 * （持久化 key 由 [storageId] 决定，与顺序无关）。
 *
 * @param scene 所属场景。
 * @param storageId 持久化 key 后缀，与 [KeyBindingScene.storagePrefix] 拼接后作为完整 key。
 * @param title 设置页显示名。
 */
enum class KeyBindingAction(
    val scene: KeyBindingScene,
    val storageId: String,
    val title: String
) {
    // --- 场景 A：视频播放中 ---
    PLAYER_DM_TOGGLE(KeyBindingScene.PLAYER, "dm_toggle", "切换弹幕开关"),
    PLAYER_SPEED_DOWN(KeyBindingScene.PLAYER, "speed_down", "播放速度减一档"),
    PLAYER_SPEED_UP(KeyBindingScene.PLAYER, "speed_up", "播放速度加一档"),
    PLAYER_SPEED_RESET(KeyBindingScene.PLAYER, "speed_reset", "倍速归位 1x"),
    PLAYER_FAVORITE(KeyBindingScene.PLAYER, "favorite", "收藏"),
    PLAYER_WATCH_LATER(KeyBindingScene.PLAYER, "watch_later", "稍后播放"),
    PLAYER_OWNER(KeyBindingScene.PLAYER, "owner", "查看 UP 主"),
    PLAYER_PREV_EPISODE(KeyBindingScene.PLAYER, "prev_episode", "上一集"),
    PLAYER_NEXT_EPISODE(KeyBindingScene.PLAYER, "next_episode", "下一集"),
    PLAYER_CHOOSE_EPISODE(KeyBindingScene.PLAYER, "choose_episode", "打开选集面板"),
    PLAYER_RELATED(KeyBindingScene.PLAYER, "related", "打开相关推荐"),
    PLAYER_VIDEO_INFO(KeyBindingScene.PLAYER, "video_info", "视频简介详情"),
    PLAYER_SUBTITLE(KeyBindingScene.PLAYER, "subtitle", "字幕选择"),
    PLAYER_REPEAT(KeyBindingScene.PLAYER, "repeat", "单集循环切换"),

    // --- 场景 B：非播放（主界面） ---
    GLOBAL_RECOMMEND(KeyBindingScene.GLOBAL, "recommend", "跳转至推荐"),
    GLOBAL_HOT(KeyBindingScene.GLOBAL, "hot", "跳转至热门"),
    GLOBAL_DYNAMIC(KeyBindingScene.GLOBAL, "dynamic", "跳转至动态"),
    GLOBAL_LIVE(KeyBindingScene.GLOBAL, "live", "跳转至直播"),
    GLOBAL_FAVORITE(KeyBindingScene.GLOBAL, "favorite", "跳转至收藏"),
    GLOBAL_WATCH_LATER(KeyBindingScene.GLOBAL, "watch_later", "跳转至稍后观看"),
    GLOBAL_SEARCH(KeyBindingScene.GLOBAL, "search", "打开搜索"),
    GLOBAL_HISTORY(KeyBindingScene.GLOBAL, "history", "我的（历史）"),
    GLOBAL_ANIMATION(KeyBindingScene.GLOBAL, "animation", "跳转至番剧"),
    GLOBAL_CINEMA(KeyBindingScene.GLOBAL, "cinema", "跳转至影视"),
    GLOBAL_CATEGORY(KeyBindingScene.GLOBAL, "category", "跳转至分类"),
    GLOBAL_CCTV_LIVE(KeyBindingScene.GLOBAL, "cctv_live", "电视直播"),
    GLOBAL_SETTINGS(KeyBindingScene.GLOBAL, "settings", "打开设置页"),
    ;

    /** 完整的 DataStore key。 */
    val storageKey: String
        get() = scene.storagePrefix + storageId

    companion object {

        /** 取某个场景下的全部动作，按声明顺序。 */
        fun of(scene: KeyBindingScene): List<KeyBindingAction> =
            values().filter { it.scene == scene }
    }
}
