package com.mytvb.feature.home

import com.mytvb.core.ui.focus.TabContentFocusTarget

interface HomeTabPage : TabContentFocusTarget {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}
}
