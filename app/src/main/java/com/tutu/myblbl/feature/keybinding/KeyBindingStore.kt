package com.tutu.myblbl.feature.keybinding

import android.view.KeyEvent
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore

/**
 * 快捷键绑定关系的存储与查询。
 *
 * 持久化沿用项目既有的"一个设置项一个 key、值存字符串"的风格（不引入 JSON），
 * 值统一为 keyCode 的十进制字符串，[BindableKeyCatalog.UNBOUND]（0）表示未绑定。
 *
 * 查询路径（按键触发时）全部走内存快照，零磁盘 IO。
 */
class KeyBindingStore(
    private val appSettings: AppSettingsDataStore
) {

    /** scene → (keyCode → action)。 */
    private val keyToAction = HashMap<KeyBindingScene, MutableMap<Int, KeyBindingAction>>()

    /** scene → (action → keyCode)。 */
    private val actionToKey = HashMap<KeyBindingScene, MutableMap<KeyBindingAction, Int>>()

    init {
        refresh()
    }

    /** 从 DataStore 重新构建内存快照。 */
    @Synchronized
    fun refresh() {
        keyToAction.clear()
        actionToKey.clear()
        for (scene in KeyBindingScene.values()) {
            val byKey = HashMap<Int, KeyBindingAction>()
            val byAction = HashMap<KeyBindingAction, Int>()
            for (action in KeyBindingAction.of(scene)) {
                val keyCode = readKeyCode(action)
                if (keyCode == BindableKeyCatalog.UNBOUND) continue
                // 同一个按键被重复绑定（异常数据）时，保留先声明的动作
                if (byKey.containsKey(keyCode)) continue
                byKey[keyCode] = action
                byAction[action] = keyCode
            }
            keyToAction[scene] = byKey
            actionToKey[scene] = byAction
        }
    }

    private fun readKeyCode(action: KeyBindingAction): Int {
        val raw = appSettings.getCachedString(action.storageKey) ?: return BindableKeyCatalog.UNBOUND
        val keyCode = raw.toIntOrNull() ?: return BindableKeyCatalog.UNBOUND
        if (keyCode == BindableKeyCatalog.UNBOUND) return BindableKeyCatalog.UNBOUND
        // 白名单已调整的旧数据（例如曾绑定过后来被排除的键）视为未绑定
        if (!BindableKeyCatalog.isBindable(keyCode)) return BindableKeyCatalog.UNBOUND
        // 归一化后再入库：历史数据可能存的是小键盘编码，不归一化会与按键触发时
        // 归一化的 keyCode 对不上，导致"已配置但按了没反应"。
        return BindableKeyCatalog.normalize(keyCode)
    }

    /** 某个动作当前绑定的按键；未绑定返回 [BindableKeyCatalog.UNBOUND]。 */
    @Synchronized
    fun boundKeyOf(scene: KeyBindingScene, action: KeyBindingAction): Int {
        return actionToKey[scene]?.get(action) ?: BindableKeyCatalog.UNBOUND
    }

    /** 某个按键在该场景下绑定的动作；未绑定返回 null。 */
    @Synchronized
    fun resolve(scene: KeyBindingScene, keyCode: Int): KeyBindingAction? {
        val normalized = BindableKeyCatalog.normalize(keyCode)
        if (normalized == BindableKeyCatalog.UNBOUND) return null
        return keyToAction[scene]?.get(normalized)
    }

    /**
     * 该按键在该场景下已被哪个**其它**动作占用；未被占用返回 null。
     * 用于绑定前的冲突提示。
     */
    @Synchronized
    fun conflictOwner(
        scene: KeyBindingScene,
        keyCode: Int,
        exclude: KeyBindingAction
    ): KeyBindingAction? {
        val owner = keyToAction[scene]?.get(BindableKeyCatalog.normalize(keyCode)) ?: return null
        return if (owner == exclude) null else owner
    }

    /**
     * 绑定按键。若该按键在本场景已被其它动作占用，会先解除原绑定（覆盖语义）。
     */
    @Synchronized
    fun bind(scene: KeyBindingScene, action: KeyBindingAction, keyCode: Int) {
        val normalized = BindableKeyCatalog.normalize(keyCode)
        if (!BindableKeyCatalog.isBindable(normalized)) return
        val byKey = keyToAction.getOrPut(scene) { HashMap() }
        val byAction = actionToKey.getOrPut(scene) { HashMap() }

        // 解除该动作原有的绑定
        byAction.remove(action)?.let { byKey.remove(it) }
        // 解除该按键原有的绑定（冲突覆盖）
        byKey.remove(normalized)?.let { byAction.remove(it) }

        byKey[normalized] = action
        byAction[action] = normalized
        appSettings.putStringAsync(action.storageKey, normalized.toString())
    }

    /** 解除某个动作的绑定。 */
    @Synchronized
    fun unbind(scene: KeyBindingScene, action: KeyBindingAction) {
        val byKey = keyToAction[scene] ?: return
        val byAction = actionToKey[scene] ?: return
        byAction.remove(action)?.let { byKey.remove(it) }
        appSettings.putStringAsync(action.storageKey, BindableKeyCatalog.UNBOUND.toString())
    }

    /** 应用推荐方案（覆盖当前全部绑定）。 */
    @Synchronized
    fun applyRecommended() {
        clearAll()
        RECOMMENDED.forEach { (action, keyCode) -> bind(action.scene, action, keyCode) }
    }

    /** 清除全部绑定。 */
    @Synchronized
    fun clearAll() {
        for (scene in KeyBindingScene.values()) {
            for (action in KeyBindingAction.of(scene)) {
                unbind(scene, action)
            }
        }
        refresh()
    }

    /** 导出当前全部绑定，供设置页渲染。 */
    @Synchronized
    fun snapshot(scene: KeyBindingScene): Map<KeyBindingAction, Int> {
        return actionToKey[scene]?.toMap() ?: emptyMap()
    }

    private companion object {

        /**
         * 推荐方案：只在用户主动点击「应用推荐方案」时套用，默认全部未绑定。
         *
         * 刻意只覆盖每个场景最常用的 10 项——可用按键有限，全占满反而难记、易误触，
         * 其余动作留给用户按需自行绑定。
         */
        val RECOMMENDED: List<Pair<KeyBindingAction, Int>> = listOf(
            // 播放场景：彩色键管最高频的弹幕/倍速/收藏，功能键管集数相关
            KeyBindingAction.PLAYER_DM_TOGGLE to KeyEvent.KEYCODE_PROG_RED,
            KeyBindingAction.PLAYER_SPEED_DOWN to KeyEvent.KEYCODE_PROG_GREEN,
            KeyBindingAction.PLAYER_SPEED_UP to KeyEvent.KEYCODE_PROG_YELLOW,
            KeyBindingAction.PLAYER_FAVORITE to KeyEvent.KEYCODE_PROG_BLUE,
            KeyBindingAction.PLAYER_WATCH_LATER to KeyEvent.KEYCODE_INFO,
            KeyBindingAction.PLAYER_OWNER to KeyEvent.KEYCODE_GUIDE,
            KeyBindingAction.PLAYER_PREV_EPISODE to KeyEvent.KEYCODE_BOOKMARK,
            KeyBindingAction.PLAYER_NEXT_EPISODE to KeyEvent.KEYCODE_DVR,
            KeyBindingAction.PLAYER_CHOOSE_EPISODE to KeyEvent.KEYCODE_SETTINGS,
            KeyBindingAction.PLAYER_RELATED to KeyEvent.KEYCODE_CAPTIONS,

            // 非播放场景：数字键 0-9 依次对应各主要页面，最好记
            KeyBindingAction.GLOBAL_RECOMMEND to KeyEvent.KEYCODE_1,
            KeyBindingAction.GLOBAL_HOT to KeyEvent.KEYCODE_2,
            KeyBindingAction.GLOBAL_DYNAMIC to KeyEvent.KEYCODE_3,
            KeyBindingAction.GLOBAL_LIVE to KeyEvent.KEYCODE_4,
            KeyBindingAction.GLOBAL_FAVORITE to KeyEvent.KEYCODE_5,
            KeyBindingAction.GLOBAL_WATCH_LATER to KeyEvent.KEYCODE_6,
            KeyBindingAction.GLOBAL_SEARCH to KeyEvent.KEYCODE_7,
            KeyBindingAction.GLOBAL_HISTORY to KeyEvent.KEYCODE_8,
            KeyBindingAction.GLOBAL_ANIMATION to KeyEvent.KEYCODE_9,
            KeyBindingAction.GLOBAL_CINEMA to KeyEvent.KEYCODE_0
        )
    }
}
