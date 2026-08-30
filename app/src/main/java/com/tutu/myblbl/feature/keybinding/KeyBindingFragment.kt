package com.tutu.myblbl.feature.keybinding

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.databinding.FragmentKeyBindingBinding
import org.koin.android.ext.android.inject

/**
 * 快捷键设置页：按「视频播放中 / 非播放」两个场景分组，逐项绑定遥控器按键。
 *
 * 从设置页「通用」分类的「快捷键设置」项进入（overlay Fragment）。
 */
class KeyBindingFragment : BaseFragment<FragmentKeyBindingBinding>() {

    private val keyBindingStore: KeyBindingStore by inject()
    private lateinit var adapter: KeyBindingAdapter

    /**
     * 列表引用缓存。
     *
     * 不能改用 `view?.findViewById(...)`：`renderList()` 会在 [initView] 中被调用，而
     * [initView] 运行在 `BaseFragment.onCreateView()` 内部，此时 `Fragment.getView()` 仍为
     * null，取不到列表会导致首屏列表永远不提交（只有后续点击按钮再次触发时才显示出来）。
     */
    private var recyclerViewRef: RecyclerView? = null

    companion object {
        /** 列表中第一个可聚焦的行（下标 0 是场景分段标题，不可聚焦）。 */
        private const val FIRST_ACTION_POSITION = 1

        fun newInstance(): KeyBindingFragment = KeyBindingFragment()
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentKeyBindingBinding {
        return FragmentKeyBindingBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        binding.buttonBack.setOnClickListener { navigateBackFromUi() }

        adapter = KeyBindingAdapter { action -> showCaptureDialog(action) }
        recyclerViewRef = binding.recyclerView
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null

        binding.buttonRecommend.setOnClickListener {
            keyBindingStore.applyRecommended()
            renderList()
            Toast.makeText(
                requireContext(),
                R.string.key_binding_applied_recommended,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.buttonClear.setOnClickListener {
            keyBindingStore.clearAll()
            renderList()
            Toast.makeText(
                requireContext(),
                R.string.key_binding_cleared_all,
                Toast.LENGTH_SHORT
            ).show()
        }

        renderList()
        val recyclerView = binding.recyclerView
        recyclerView.post {
            if (!isAdded) return@post
            adapter.requestFocusAt(FIRST_ACTION_POSITION, recyclerView)
        }
    }

    private fun showCaptureDialog(action: KeyBindingAction) {
        KeyCaptureDialog(
            context = requireContext(),
            action = action,
            store = keyBindingStore,
            onCommitted = { renderList() }
        ).show()
    }

    /**
     * 重新渲染列表。
     *
     * [submitList] 一律 post 到下一帧执行：本方法可能在对话框 dismiss / 焦点切换的过程中被回调，
     * 此刻 RecyclerView 可能正处于布局中，同步 notify 会抛
     * "Cannot call this method while RecyclerView is computing a layout or scrolling"。
     */
    private fun renderList() {
        val rows = mutableListOf<KeyBindingAdapter.Row>()
        KeyBindingScene.values().forEach { scene ->
            rows.add(KeyBindingAdapter.Row.Section(scene))
            KeyBindingAction.of(scene).forEach { action ->
                rows.add(
                    KeyBindingAdapter.Row.Action(
                        action = action,
                        boundKeyCode = keyBindingStore.boundKeyOf(scene, action)
                    )
                )
            }
        }
        val recyclerView = recyclerViewRef ?: return
        recyclerView.post {
            if (!isAdded) return@post
            adapter.submitList(rows)
            adapter.requestFocusAt(adapter.focusedPosition(), recyclerView)
        }
    }

    override fun onDestroyView() {
        recyclerViewRef = null
        super.onDestroyView()
    }
}
