package com.tutu.myblbl.feature.keybinding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellSettingBinding
import com.tutu.myblbl.databinding.ItemKeyBindingSectionBinding

/**
 * 快捷键设置列表：按场景分段，段内为可绑定的动作行。
 *
 * 行布局复用设置页的 `cell_setting.xml`，保证与既有设置项视觉一致。
 *
 * **重要**：焦点高亮一律直接改 View，不调 `notifyItemChanged()`。
 * 焦点变化可能发生在 RecyclerView 的 `removeDetachedView`（切换 Fragment / 列表回收）过程中，
 * 此时调 notify 会触发 `assertNotInLayoutOrScroll`，抛
 * `IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling`。
 */
class KeyBindingAdapter(
    private val onActionClick: (KeyBindingAction) -> Unit
) : ListAdapter<KeyBindingAdapter.Row, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    sealed interface Row {

        /** 场景分段标题。 */
        data class Section(val scene: KeyBindingScene) : Row

        /** 一个可绑定的动作。 */
        data class Action(val action: KeyBindingAction, val boundKeyCode: Int) : Row
    }

    private var focusedPosition = RecyclerView.NO_POSITION
    private var attachedRecyclerView: RecyclerView? = null

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_ACTION = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean {
                return when {
                    oldItem is Row.Section && newItem is Row.Section -> oldItem.scene == newItem.scene
                    oldItem is Row.Action && newItem is Row.Action -> oldItem.action == newItem.action
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Row.Section -> TYPE_SECTION
            is Row.Action -> TYPE_ACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionViewHolder(ItemKeyBindingSectionBinding.inflate(inflater, parent, false))
        } else {
            ActionViewHolder(CellSettingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Section -> (holder as SectionViewHolder).bind(row)
            is Row.Action -> (holder as ActionViewHolder).bind(row, position == focusedPosition, position)
        }
    }

    /** 当前获得焦点的行下标；无焦点时返回 [RecyclerView.NO_POSITION]。 */
    fun focusedPosition(): Int = focusedPosition

    /** 供外部在列表重建后恢复焦点。 */
    fun requestFocusAt(position: Int, recyclerView: RecyclerView) {
        if (position == RecyclerView.NO_POSITION) return
        recyclerView.post {
            if (!recyclerView.isAttachedToWindow) return@post
            recyclerView.findViewHolderForAdapterPosition(position)
                ?.itemView
                ?.findViewById<ViewGroup>(R.id.click_view)
                ?.requestFocus()
        }
    }

    inner class SectionViewHolder(
        private val binding: ItemKeyBindingSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.isFocusable = false
        }

        fun bind(row: Row.Section) {
            binding.tvSectionTitle.text = row.scene.displayName
            binding.tvSectionDesc.setText(
                when (row.scene) {
                    KeyBindingScene.PLAYER -> R.string.key_binding_section_player_desc
                    KeyBindingScene.GLOBAL -> R.string.key_binding_section_global_desc
                }
            )
        }
    }

    inner class ActionViewHolder(
        private val binding: CellSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.clickView.setOnClickListener {
                val row = getItemOrNull(bindingAdapterPosition) as? Row.Action ?: return@setOnClickListener
                onActionClick(row.action)
            }
            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
                if (hasFocus) {
                    val oldFocused = focusedPosition
                    focusedPosition = position
                    // 直接改 View，不经过 notify，避免在 RecyclerView 布局过程中触发断言
                    if (oldFocused != RecyclerView.NO_POSITION && oldFocused != position) {
                        (attachedRecyclerView?.findViewHolderForAdapterPosition(oldFocused) as? ActionViewHolder)
                            ?.bindFocusState(false, oldFocused)
                    }
                    bindFocusState(true, position)
                    return@setOnFocusChangeListener
                }
                if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    bindFocusState(false, position)
                }
            }
        }

        fun bind(row: Row.Action, isFocused: Boolean, position: Int) {
            bindContent(row)
            bindFocusState(isFocused, position)
        }

        fun bindContent(row: Row.Action) {
            binding.tvTitle.text = row.action.title
            binding.tvInfo.text = BindableKeyCatalog.displayName(row.boundKeyCode)
        }

        fun bindFocusState(isFocused: Boolean, position: Int) {
            binding.iconArrow.alpha = if (isFocused) 1f else 0.6f
            binding.tvInfo.alpha = if (isFocused) 1f else 0.8f
            if (isFocused) {
                binding.clickView.setBackgroundResource(R.drawable.cell_background)
            } else {
                binding.clickView.setBackgroundResource(
                    if (position % 2 == 0) R.drawable.cell_setting_background else 0
                )
            }
            binding.clickView.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .setDuration(120L)
                .start()
        }
    }

    private fun getItemOrNull(position: Int): Row? {
        if (position == RecyclerView.NO_POSITION) return null
        if (position < 0 || position >= itemCount) return null
        return getItem(position)
    }
}
