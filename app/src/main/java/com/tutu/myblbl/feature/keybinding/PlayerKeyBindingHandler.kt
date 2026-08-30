package com.tutu.myblbl.feature.keybinding

import com.tutu.myblbl.R
import com.tutu.myblbl.feature.player.view.MyPlayerSettingView
import com.tutu.myblbl.feature.player.view.MyPlayerView
import kotlin.math.abs

/**
 * 场景 A（视频播放中）的快捷键分发。
 *
 * 在 [android.app.Activity.dispatchKeyEvent] 的最上游调用：命中则消费事件，
 * 未命中返回 false 交由播放器原有按键逻辑处理。
 */
class PlayerKeyBindingHandler(
    private val store: KeyBindingStore,
    private val playerView: MyPlayerView,
    private val actions: Actions
) {

    interface Actions {
        fun toggleFavorite()
        fun toggleWatchLater()
        fun showOwnerDetail()
        fun toast(message: String)

        // --- 播放控制 ---
        fun playPreviousEpisode()
        fun playNextEpisode()
        fun showChooseEpisode()
        fun showRelated()
        fun showVideoInfo()
        fun showSubtitle()
        fun toggleRepeatMode()
    }

    /** @return true 表示该按键已被快捷键消费。 */
    fun handle(keyCode: Int): Boolean {
        val action = store.resolve(KeyBindingScene.PLAYER, keyCode) ?: return false
        when (action) {
            KeyBindingAction.PLAYER_DM_TOGGLE -> toggleDanmaku()
            KeyBindingAction.PLAYER_SPEED_UP -> stepSpeed(1)
            KeyBindingAction.PLAYER_SPEED_DOWN -> stepSpeed(-1)
            KeyBindingAction.PLAYER_SPEED_RESET -> resetSpeed()
            KeyBindingAction.PLAYER_FAVORITE -> actions.toggleFavorite()
            KeyBindingAction.PLAYER_WATCH_LATER -> actions.toggleWatchLater()
            KeyBindingAction.PLAYER_OWNER -> actions.showOwnerDetail()
            KeyBindingAction.PLAYER_PREV_EPISODE -> actions.playPreviousEpisode()
            KeyBindingAction.PLAYER_NEXT_EPISODE -> actions.playNextEpisode()
            KeyBindingAction.PLAYER_CHOOSE_EPISODE -> actions.showChooseEpisode()
            KeyBindingAction.PLAYER_RELATED -> actions.showRelated()
            KeyBindingAction.PLAYER_VIDEO_INFO -> actions.showVideoInfo()
            KeyBindingAction.PLAYER_SUBTITLE -> actions.showSubtitle()
            KeyBindingAction.PLAYER_REPEAT -> actions.toggleRepeatMode()
            else -> return false
        }
        return true
    }

    private fun toggleDanmaku() {
        val enabled = !playerView.isDanmakuEnabled()
        playerView.setDanmakuEnabled(enabled)
        actions.toast(
            if (enabled) {
                playerView.context.getString(R.string.danmaku_on)
            } else {
                playerView.context.getString(R.string.danmaku_off)
            }
        )
    }

    /** 在当前档位基础上移动 [delta] 档；到达边界时只提示不变更。 */
    private fun stepSpeed(delta: Int) {
        val speeds = MyPlayerSettingView.PLAYBACK_SPEEDS
        if (speeds.isEmpty()) return
        val current = playerView.getCurrentSpeed()
        val index = speeds.indexOfFirst { abs(it - current) < 0.001f }
            .takeIf { it >= 0 }
            ?: speeds.indices.minByOrNull { abs(speeds[it] - current) }
            ?: return
        val target = (index + delta).coerceIn(speeds.indices)
        val next = speeds[target]
        val text = formatSpeed(next)
        val context = playerView.context
        if (target == index) {
            // 已在边界：明确告知，避免用户以为按键没生效
            val message = if (delta > 0) {
                context.getString(R.string.playback_speed_max, text)
            } else {
                context.getString(R.string.playback_speed_min, text)
            }
            actions.toast(message)
            return
        }
        actions.toast(context.getString(R.string.playback_speed_changed, text, target + 1, speeds.size))
        playerView.setPlaySpeed(next)
    }

    /** 倍速归位到 1x。 */
    private fun resetSpeed() {
        val speeds = MyPlayerSettingView.PLAYBACK_SPEEDS
        val current = playerView.getCurrentSpeed()
        if (abs(current - 1.0f) < 0.001f) {
            actions.toast(
                playerView.context.getString(
                    R.string.playback_speed_changed,
                    formatSpeed(1.0f),
                    speeds.indexOfFirst { abs(it - 1.0f) < 0.001f } + 1,
                    speeds.size
                )
            )
            return
        }
        playerView.setPlaySpeed(1.0f)
        actions.toast(
            playerView.context.getString(
                R.string.playback_speed_changed,
                formatSpeed(1.0f),
                speeds.indexOfFirst { abs(it - 1.0f) < 0.001f } + 1,
                speeds.size
            )
        )
    }

    private fun formatSpeed(speed: Float): String {
        val asLong = speed.toLong()
        return if (speed == asLong.toFloat()) asLong.toString() else speed.toString()
    }
}
