package com.mytvb.feature.me

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.mytvb.R
import com.mytvb.databinding.FragmentMeBinding
import com.mytvb.event.AppEventHub
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import com.mytvb.ui.fragment.main.MainTabFocusTarget
import com.mytvb.feature.settings.SignInFragment
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.ui.tab.enableTouchNavigation
import com.mytvb.core.ui.tab.focusNearestTabTo
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.core.ui.base.OnBackPressedHandler
import com.mytvb.core.ui.tab.disableAdjacentPagePrefetch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeFragment : BaseFragment<FragmentMeBinding>(), MainTabFocusTarget, OnBackPressedHandler {
    companion object {
        private const val USER_INFO_CACHE_TTL_MS = 10 * 60 * 1000L

        fun newInstance(): MeFragment = MeFragment()
    }

    private val appSettings: AppSettingsDataStore by inject()
    private val appEventHub: AppEventHub by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val sessionGateway: SessionStateRepository by inject()
    private val viewModel: MeViewModel by viewModel()
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: MeFragmentAdapter
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null
    private var pendingRefreshAfterLogin = false
    private var wasLoggedInOnPause = false
    /** 视图尚未创建时暂存的待切换子页，见 [selectPage]。 */
    private var pendingPage: Int? = null
    private var viewReady = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMeBinding {
        return FragmentMeBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager
        binding.rightStateImage.setOnClickListener {
            if (!viewModel.isLoggedIn.value) {
                openInHostContainer(SignInFragment.newInstance())
            }
        }

        adapter = MeFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        // 保留全部 3 个 tab 页（历史/收藏/稍后观看）：切 tab 不销毁 fragment，
        // 滚动进度/焦点/已加载数据切回来原样保留（同首页，见 disableAdjacentPagePrefetch 注释）
        viewPager.disableAdjacentPagePrefetch(retainAllPages = true)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)?.let(::getString).orEmpty()
        }.attach()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            onNavigateDown = ::focusCurrentPageNearestContent,
            onNavigateLeft = ::focusLeftFunctionArea,
            onTabReselected = {
                // 重选当前 tab = 回顶聚焦第一卡（与首页一致）；刷新只留 MENU 键。
                // 未登录时 tab 栏不可见不会触发，无需登录判断。
                val page = getCurrentTabPage()
                if (page != null && !page.scrollToTopAndFocus()) {
                    page.scrollToTop()
                }
            }
        )
        tabSelectedListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        }.also { tabLayout.addOnTabSelectedListener(it) }

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                notifyCurrentTabWithRetry({ page -> page.onTabSelected() })
            }
        }.also { viewPager.registerOnPageChangeCallback(it) }

        viewPager.currentItem = getDefaultTabIndex()
        viewReady = true
        pendingPage?.let { page ->
            pendingPage = null
            if (viewPager.currentItem != page) {
                viewPager.currentItem = page
            }
        }
        notifyCurrentTabWithRetry({ it.onTabSelected() })
    }

    /**
     * 切换到指定子页（0 历史 / 1 收藏 / 2 稍后再看）。
     *
     * 供遥控器快捷键调用：视图尚未创建时先记录，等 [initView] 建好 ViewPager 再落地。
     */
    fun selectPage(position: Int) {
        if (!viewReady) {
            pendingPage = position
            return
        }
        pendingPage = null
        if (viewPager.currentItem != position) {
            viewPager.currentItem = position
        }
    }

    /**
     * 判断 [fragment] 是否为当前子页的实例（供子页在 initData 时决定是否立即加载）。
     *
     * 快捷键在首次创建本页时切到非默认子页，ViewPager 首次布局可能把目标页 Fragment
     * 先建一次又重建，重建后的实例收不到 onTabSelected。子页 initData 用引用相等判断
     * "我是不是当前页"，是就直接加载，不依赖通知时序。
     */
    fun isCurrentPage(fragment: Fragment): Boolean {
        if (!viewReady) return false
        return adapter.getFragment(viewPager.currentItem) === fragment
    }

    override fun onPause() {
        wasLoggedInOnPause = sessionGateway.isLoggedIn()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (sessionGateway.isLoggedIn() && !wasLoggedInOnPause) {
            pendingRefreshAfterLogin = true
            viewModel.loadUserInfo()
        }
    }

    override fun initData() {
        viewModel.loadUserInfo()
        renderLoginState(viewModel.isLoggedIn.value)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userInfo.collectLatest { userInfo ->
                    userInfo?.let {
                        // Avatar badge is managed by TabBarView in MainActivity
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.syncWithGateway()
                viewModel.isLoggedIn.collectLatest { isLoggedIn ->
                    renderLoginState(isLoggedIn)
                    if (!isLoggedIn) {
                        // Avatar state is managed by TabBarView in MainActivity
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    // MainTabSelected(5) 是「正在切到本页」的信号：事件经 Main.immediate
                    // 同步分发，先于 showFragment 的 show 事务生效，此刻 isHidden 仍是
                    // true——本页自己的选中事件若被下面的 isHidden 守卫吞掉，从其它主
                    // tab 切回时 SELECT_TAB4 永远送不到子 tab（历史页表现为不刷新）。
                    if (event is MainNavigationViewModel.Event.MainTabSelected) {
                        if (event.index == 5) {
                            if (viewModel.shouldRefresh(USER_INFO_CACHE_TTL_MS)) {
                                viewModel.loadUserInfo()
                            }
                            dispatchHostEvent(MeTabPage.HostEvent.SELECT_TAB4)
                        }
                        return@collectLatest
                    }
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 5) {
                                if (viewModel.shouldRefresh(USER_INFO_CACHE_TTL_MS)) {
                                    viewModel.loadUserInfo()
                                }
                                dispatchHostEvent(MeTabPage.HostEvent.CLICK_TAB4) {
                                    getCurrentTabPage()?.refresh()
                                }
                            }

                        MainNavigationViewModel.Event.BackPressed -> {
                            dispatchHostEvent(MeTabPage.HostEvent.BACK_PRESSED) {
                                Unit
                            }
                        }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            dispatchHostEvent(MeTabPage.HostEvent.KEY_MENU_PRESS) {
                                getCurrentTabPage()?.refresh()
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged) {
                        pendingRefreshAfterLogin = true
                        viewModel.loadUserInfo()
                        adapter.getFragments().forEach { fragment ->
                            if (fragment.view != null) {
                                val page = fragment as? MeTabPage ?: return@forEach
                                if (page.shouldDeferSessionRefresh()) {
                                    // 静默刷新进行中（如播放器返回），同接口数据在途，跳过回顶重排
                                    return@forEach
                                }
                                page.refresh()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getDefaultTabIndex(): Int {
        val startPage = appSettings.getCachedInt("defaultStartPage", 1)
        return (startPage - 4).coerceIn(0, adapter.itemCount - 1)
    }

    fun scrollToTop() {
        if (!viewModel.isLoggedIn.value) {
            return
        }
        getCurrentTabPage()?.scrollToTop()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    /**
     * BACK 三层分发（与 HomeFragment 一致）：
     * 内容区 → 当前选中的二级 tab；tab 栏 → 主边栏当前主 tab 按钮；
     * 未登录时 tab/viewPager 均 GONE（hasFocus 恒 false），自然放行给退出流程。
     */
    override fun onBackPressed(): Boolean {
        if (tabLayout.hasFocus()) {
            return (activity as? com.mytvb.ui.activity.MainActivity)?.focusSidebarCurrentTab() == true
        }
        if (viewPager.hasFocus()) {
            return tabLayout.focusSelectedTab()
        }
        return false
    }

    /** 首个二级 tab 上按 LEFT：聚焦左侧主边栏（与首页一致）。 */
    private fun focusLeftFunctionArea(): Boolean {
        return (activity as? com.mytvb.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
    }

    /** 列表顶行 UP 的落点：聚焦当前选中的二级 tab（不切页）。 */
    fun focusSelectedTabFromContent(): Boolean {
        return tabLayout.focusSelectedTab()
    }

    /** 内容网格左右边缘切二级 tab（与 HomeFragment 一致）；首/末 tab 越界方向保持原有行为。 */
    fun switchAdjacentTabFromContentEdge(delta: Int): Boolean {
        val target = viewPager.currentItem + delta
        if (target < 0) {
            return (activity as? com.mytvb.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
        }
        if (target >= adapter.itemCount) {
            return true
        }
        viewPager.setCurrentItem(target, true)
        // 切页后旧页焦点随页面滑出，需把焦点交接到新页；布局未稳时补一次延迟重试
        viewPager.post {
            if (isAdded && !focusCurrentPageNearestContent()) {
                viewPager.postDelayed({
                    if (isAdded) {
                        focusCurrentPageNearestContent()
                    }
                }, 120L)
            }
        }
        return true
    }

    /** 顶部 tab 栏按 DOWN：就近聚焦可见第一项（列表位置不动，半截轻滚补齐）；回顶只由重选 tab 承担。 */
    private fun focusCurrentPageNearestContent(): Boolean {
        val page = getCurrentTabPage() ?: return false
        if (page.focusNearestVisibleContent()) {
            return true
        }
        return focusCurrentPagePrimaryContent()
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (!viewModel.isLoggedIn.value) {
            return false
        }
        if (preferSpatialEntry && anchorView != null && isVerticallyAlignedWith(anchorView, tabLayout)) {
            if (focusCurrentTab(anchorView)) return true
        }
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry) ||
            focusCurrentTab(anchorView)
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return getCurrentTabPage()?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    private fun getCurrentTabPage(): MeTabPage? {
        return adapter.getFragment(viewPager.currentItem) as? MeTabPage
    }

    /**
     * 通知当前子页执行 [MeTabPage.onTabSelected]，重试直到子页 view 就绪。
     *
     * 初版只重试"子页 Fragment 为 null"且用立即 post：快捷键首次创建本页（懒创建）时，
     * 5 次立即重试全在 ViewPager 首次布局之前耗尽，子页创建后 onTabSelected 永远等不到，
     * 表现为"跳到稍后再看但列表空白"。改为同时等待 view 就绪 + postDelayed 跨帧重试。
     */
    private fun notifyCurrentTabWithRetry(action: (MeTabPage) -> Unit, retries: Int = 10) {
        val page = getCurrentTabPage()
        if (page != null && (page as? Fragment)?.view != null) {
            action(page)
        } else if (retries > 0) {
            viewPager.postDelayed({ notifyCurrentTabWithRetry(action, retries - 1) }, 50L)
        }
    }

    private fun dispatchHostEvent(event: MeTabPage.HostEvent, fallback: (() -> Unit)? = null) {
        if (!viewModel.isLoggedIn.value) {
            return
        }
        if (getCurrentTabPage()?.onHostEvent(event) == true) {
            return
        }
        fallback?.invoke()
    }

    private fun renderLoginState(isLoggedIn: Boolean) {
        if (!isAdded || view == null) {
            return
        }
        binding.rightStateContainer.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        binding.tabLayout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.viewPager.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.rightStateText.text = getString(R.string.need_sign_in)

        if (isLoggedIn && pendingRefreshAfterLogin) {
            pendingRefreshAfterLogin = false
            binding.viewPager.post {
                if (!isAdded || view == null) return@post
                adapter.getFragments().forEach { fragment ->
                    if (fragment.view != null) {
                        val page = fragment as? MeTabPage ?: return@forEach
                        if (page.shouldDeferSessionRefresh()) {
                            // 静默刷新进行中（如播放器返回），同接口数据在途，跳过回顶重排
                            return@forEach
                        }
                        page.refresh()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        viewReady = false
        pageChangeCallback?.let(viewPager::unregisterOnPageChangeCallback)
        pageChangeCallback = null
        tabSelectedListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        tabSelectedListener = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }

    private fun isVerticallyAlignedWith(anchor: View, target: View): Boolean {
        val anchorRect = Rect()
        val targetRect = Rect()
        if (!anchor.getGlobalVisibleRect(anchorRect)) return false
        if (!target.getGlobalVisibleRect(targetRect)) return false
        return maxOf(anchorRect.top, targetRect.top) < minOf(anchorRect.bottom, targetRect.bottom)
    }
}
