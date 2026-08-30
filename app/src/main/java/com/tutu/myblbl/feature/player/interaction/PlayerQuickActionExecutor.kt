package com.tutu.myblbl.feature.player.interaction

import android.content.Context
import android.widget.Toast
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放器内的"静默"快捷操作：收藏 / 稍后播放。
 *
 * 逻辑与 [com.tutu.myblbl.ui.dialog.PlayerActionDialog] 保持一致（同样的接口与判定顺序），
 * 但不弹对话框，只做登录校验 → 关系查询 → 切换 → Toast。
 *
 * 关系状态按 aid+bvid 做短缓存，避免连按快捷键时重复请求。
 */
class PlayerQuickActionExecutor(
    private val context: Context,
    private val videoRepository: VideoRepository,
    private val favoriteRepository: FavoriteRepository,
    private val sessionGateway: NetworkSessionGateway
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 收藏与稍后播放是两条独立的业务状态，各自持有缓存 key，
    // 否则只查过其中一项时另一项会误用未刷新的默认值。
    private var cachedFavoriteKey: String? = null
    private var cachedFavorited: Boolean = false
    private var cachedWatchLaterKey: String? = null
    private var cachedWatchLater: Boolean = false

    /** 防止连按快捷键时并发切换同一状态。 */
    @Volatile
    private var running = false

    /** 切换当前视频的收藏状态。 */
    fun toggleFavorite(aid: Long, bvid: String, ownerMid: Long) {
        if (!checkLogin()) return
        if (aid <= 0L && bvid.isBlank()) {
            toast(context.getString(R.string.video_info_not_ready))
            return
        }
        if (!begin()) return
        scope.launch {
            try {
                toggleFavoriteInternal(aid, bvid, ownerMid)
            } finally {
                running = false
            }
        }
    }

    private suspend fun toggleFavoriteInternal(aid: Long, bvid: String, ownerMid: Long) {
        val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L } ?: ownerMid
        if (currentUserMid <= 0L) {
            toast("收藏夹信息未加载完成")
            return
        }
        val favorited = resolveFavorited(aid, bvid) ?: run {
            toast("收藏状态获取失败")
            return
        }
        val folderId = resolveDefaultFolderId(currentUserMid) ?: run {
            toast("暂无可用收藏夹")
            return
        }
        val result = if (favorited) {
            favoriteRepository.removeFavorite(aid, folderId)
        } else {
            favoriteRepository.addFavorite(aid, folderId)
        }
        result.onSuccess { response ->
            if (!response.isSuccess) {
                AppLog.w(TAG, "favorite failed: code=${response.code}, message=${response.errorMessage}")
                toast(response.errorMessage.ifBlank { "操作失败" })
                return@onSuccess
            }
            cachedFavorited = !favorited
            toast(
                if (cachedFavorited) {
                    context.getString(R.string.collection_)
                } else {
                    "取消收藏"
                }
            )
        }.onFailure {
            AppLog.e(TAG, "favorite failed", it)
            toast("操作失败")
        }
    }

    /** 切换当前视频的稍后播放（稍后再看）状态。 */
    fun toggleWatchLater(aid: Long, bvid: String) {
        if (!checkLogin()) return
        if (aid <= 0L && bvid.isBlank()) {
            toast(context.getString(R.string.video_info_not_ready))
            return
        }
        if (!begin()) return
        scope.launch {
            try {
                toggleWatchLaterInternal(aid, bvid)
            } finally {
                running = false
            }
        }
    }

    private suspend fun toggleWatchLaterInternal(aid: Long, bvid: String) {
        val inWatchLater = resolveWatchLater(aid, bvid)
        val response = runCatching {
            if (inWatchLater) {
                videoRepository.removeWatchLater(aid, bvid)
            } else {
                videoRepository.addWatchLater(aid, bvid)
            }
        }.getOrNull()
        if (response == null) {
            toast("操作失败")
            return
        }
        if (!response.isSuccess) {
            AppLog.w(TAG, "watchLater failed: code=${response.code}, message=${response.errorMessage}")
            toast(response.errorMessage.ifBlank { "操作失败" })
            return
        }
        cachedWatchLater = !inWatchLater
        toast(
            if (cachedWatchLater) {
                context.getString(R.string.later_watch_added)
            } else {
                context.getString(R.string.later_watch_removed)
            }
        )
    }

    /** Activity 销毁时调用，取消协程。 */
    fun release() {
        scope.cancel()
        running = false
    }

    private fun begin(): Boolean {
        if (running) return false
        running = true
        return true
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            toast(context.getString(R.string.need_sign_in_first))
            return false
        }
        return true
    }

    private suspend fun resolveFavorited(aid: Long, bvid: String): Boolean? {
        val key = cacheKeyOf(aid, bvid)
        if (cachedFavoriteKey == key) return cachedFavorited
        val relation = runCatching {
            videoRepository.getArchiveRelation(aid, bvid.takeIf { it.isNotBlank() })
        }.getOrNull()
        if (relation == null || !relation.isSuccess || relation.data == null) {
            return null
        }
        cachedFavoriteKey = key
        cachedFavorited = relation.data.favorite
        return cachedFavorited
    }

    private suspend fun resolveWatchLater(aid: Long, bvid: String): Boolean {
        val key = cacheKeyOf(aid, bvid)
        if (cachedWatchLaterKey == key) return cachedWatchLater
        cachedWatchLaterKey = key
        cachedWatchLater = videoRepository.checkWatchLater(aid, bvid)
        return cachedWatchLater
    }

    private suspend fun resolveDefaultFolderId(upMid: Long): String? {
        val folders = favoriteRepository.getFavoriteFolders(upMid)
            .getOrNull()
            ?.data
            ?.list
            .orEmpty()
        return folders.firstOrNull()?.id?.toString()
    }

    private fun cacheKeyOf(aid: Long, bvid: String): String = "$aid|$bvid"

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "PlayerQuickAction"
    }
}
