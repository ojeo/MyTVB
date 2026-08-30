package com.tutu.myblbl.feature.keybinding

import android.view.KeyEvent

/**
 * 可绑定按键判定。
 *
 * **采用黑名单排除法（默认可绑定），而不是白名单枚举法。**
 *
 * 原因：红外遥控器没有统一的按键编码规范，不同厂商 / 不同盒子的同一个物理按键
 * 可能发送完全不同的 keyCode（例如数字 1 可能是 `KEYCODE_1`(8)、`KEYCODE_NUMPAD_1`(145)，
 * 也可能是厂商自定义编码）。白名单枚举必然漏掉一部分遥控器，表现为"按了提示不可用"。
 * 因此只排除真正会与框架 / 播放器 / 系统交互抢事件的保留键，其余一律允许绑定。
 */
object BindableKeyCatalog {

    /** 未绑定。 */
    const val UNBOUND = 0

    /**
     * 已知按键的友好显示名。**只影响展示，不影响是否可绑定**。
     * 未命中的按键会退化成系统名（如 `PROG_RED`）或"按键(code)"。
     */
    private val FRIENDLY_NAMES: Map<Int, String> = buildMap {
        for (index in 0..9) {
            put(KeyEvent.KEYCODE_0 + index, "数字 $index")
        }
        put(KeyEvent.KEYCODE_PROG_RED, "红键")
        put(KeyEvent.KEYCODE_PROG_GREEN, "绿键")
        put(KeyEvent.KEYCODE_PROG_YELLOW, "黄键")
        put(KeyEvent.KEYCODE_PROG_BLUE, "蓝键")
        put(KeyEvent.KEYCODE_INFO, "信息键")
        put(KeyEvent.KEYCODE_GUIDE, "指南键")
        put(KeyEvent.KEYCODE_DVR, "录制键")
        put(KeyEvent.KEYCODE_CAPTIONS, "字幕键")
        put(KeyEvent.KEYCODE_BOOKMARK, "书签键")
        put(KeyEvent.KEYCODE_SETTINGS, "设置键")
        put(KeyEvent.KEYCODE_CHANNEL_UP, "频道+")
        put(KeyEvent.KEYCODE_CHANNEL_DOWN, "频道-")
        put(KeyEvent.KEYCODE_TV, "电视键")
        put(KeyEvent.KEYCODE_SEARCH, "搜索键")
        put(KeyEvent.KEYCODE_PAGE_UP, "上页")
        put(KeyEvent.KEYCODE_PAGE_DOWN, "下页")
    }

    /**
     * 不可绑定的保留键：这些键已被框架 / 播放器 / 系统占用，
     * 绑定后会与既有交互抢事件，或导致界面完全无法操作。
     */
    private val RESERVED: Set<Int> = buildSet {
        // 焦点导航与确认：绑定后界面将无法操作
        addAll(
            listOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_SPACE
            )
        )
        // 系统导航
        addAll(
            listOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_SLEEP,
                KeyEvent.KEYCODE_WAKEUP
            )
        )
        // 音量
        addAll(
            listOf(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE
            )
        )
        // 媒体键：播放场景由播放器 / 媒体会话接走
        addAll(
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_RECORD,
                KeyEvent.KEYCODE_MEDIA_EJECT,
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
                KeyEvent.KEYCODE_MEDIA_CLOSE,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_TOP_MENU,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY
            )
        )
        // 修饰键与元键
        addAll(
            listOf(
                KeyEvent.KEYCODE_UNKNOWN,
                KeyEvent.KEYCODE_SOFT_LEFT,
                KeyEvent.KEYCODE_SOFT_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_CAPS_LOCK,
                KeyEvent.KEYCODE_SCROLL_LOCK,
                KeyEvent.KEYCODE_NUM_LOCK,
                KeyEvent.KEYCODE_FUNCTION,
                KeyEvent.KEYCODE_SYM,
                KeyEvent.KEYCODE_NUM,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_MOVE_HOME,
                KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.KEYCODE_INSERT
            )
        )
    }

    /**
     * 把同一物理按键的不同编码归一化到单一 keyCode。
     *
     * 部分遥控器 / 外接键盘的数字键发送小键盘编码 `KEYCODE_NUMPAD_0..9`（144-153），
     * 而不是 `KEYCODE_0..9`（7-16）。
     */
    fun normalize(keyCode: Int): Int {
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            return KeyEvent.KEYCODE_0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        }
        return keyCode
    }

    /** 该按键是否允许绑定：只要不在保留键列表内即可。 */
    fun isBindable(keyCode: Int): Boolean {
        val normalized = normalize(keyCode)
        if (normalized <= 0) return false
        return normalized !in RESERVED
    }

    /** 该按键是否为系统/播放器保留键。 */
    fun isReserved(keyCode: Int): Boolean = normalize(keyCode) in RESERVED

    /**
     * 按键的显示名：友好名 → 系统名（去掉 KEYCODE_ 前缀）→ "按键(code)"。
     */
    fun displayName(keyCode: Int): String {
        if (keyCode == UNBOUND) return "未绑定"
        val normalized = normalize(keyCode)
        FRIENDLY_NAMES[normalized]?.let { return it }
        return describeSystemKey(normalized)
    }

    private fun describeSystemKey(keyCode: Int): String {
        val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrNull().orEmpty()
        if (raw.startsWith("KEYCODE_")) {
            return raw.removePrefix("KEYCODE_")
        }
        return if (raw.isNotBlank()) raw else "按键($keyCode)"
    }
}
