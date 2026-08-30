package com.tutu.myblbl.feature.keybinding

/**
 * 场景 B（非播放，主界面）的快捷键分发。
 *
 * 在 [android.app.Activity.onKeyDown] 中调用：Activity 的 onKeyDown 只在 View 树
 * 未消费按键时才回调，因此搜索页自定义键盘、输入框等会天然优先，不会被抢事件。
 */
class GlobalKeyBindingNavigator(
    private val store: KeyBindingStore,
    private val navigator: Navigator
) {

    interface Navigator {
        fun toRecommend()
        fun toHot()
        fun toDynamic()
        fun toLive()
        fun toFavorite()
        fun toWatchLater()

        // --- 扩展页面 ---
        fun toSearch()
        fun toHistory()
        fun toAnimation()
        fun toCinema()
        fun toCategory()
        fun toCctvLive()
        fun openSettings()
    }

    /** @return true 表示该按键已被快捷键消费。 */
    fun handle(keyCode: Int): Boolean {
        val action = store.resolve(KeyBindingScene.GLOBAL, keyCode) ?: return false
        when (action) {
            KeyBindingAction.GLOBAL_RECOMMEND -> navigator.toRecommend()
            KeyBindingAction.GLOBAL_HOT -> navigator.toHot()
            KeyBindingAction.GLOBAL_DYNAMIC -> navigator.toDynamic()
            KeyBindingAction.GLOBAL_LIVE -> navigator.toLive()
            KeyBindingAction.GLOBAL_FAVORITE -> navigator.toFavorite()
            KeyBindingAction.GLOBAL_WATCH_LATER -> navigator.toWatchLater()
            KeyBindingAction.GLOBAL_SEARCH -> navigator.toSearch()
            KeyBindingAction.GLOBAL_HISTORY -> navigator.toHistory()
            KeyBindingAction.GLOBAL_ANIMATION -> navigator.toAnimation()
            KeyBindingAction.GLOBAL_CINEMA -> navigator.toCinema()
            KeyBindingAction.GLOBAL_CATEGORY -> navigator.toCategory()
            KeyBindingAction.GLOBAL_CCTV_LIVE -> navigator.toCctvLive()
            KeyBindingAction.GLOBAL_SETTINGS -> navigator.openSettings()
            else -> return false
        }
        return true
    }
}
