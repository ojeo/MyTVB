package com.tutu.myblbl.feature.player

import android.content.Context
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.model.video.VideoModel

/**
 * PlayerActivity 与 VideoPlayerFragment 共用的未成年人保护拦截判断。
 */
internal object PlayerContentGuards {

    fun isVideoBlocked(context: Context, video: VideoModel): Boolean {
        return ContentFilter.isVideoBlocked(
            context = context,
            typeName = video.typeName,
            title = video.title,
            teenageMode = video.teenageMode,
            desc = video.desc,
            authorName = video.authorName,
            aid = video.aid,
            bvid = video.bvid,
            coverUrl = video.coverUrl,
            typeId = video.typeId
        )
    }

    fun isEpisodeBlocked(context: Context, episode: VideoPlayerViewModel.PlayableEpisode): Boolean {
        return ContentFilter.isVideoBlocked(
            context = context,
            typeName = "",
            title = episode.title,
            aid = episode.aid,
            bvid = episode.bvid,
            coverUrl = episode.cover
        )
    }
}
