package com.mytvb.feature.home

import com.mytvb.model.video.VideoModel
import kotlinx.coroutines.flow.StateFlow

interface VideoFeedViewModel {
    val uiState: StateFlow<FeedUiState<VideoModel>>

    fun loadInitial()

    fun refresh()

    fun loadMore()

    fun consumeListChange()
}
