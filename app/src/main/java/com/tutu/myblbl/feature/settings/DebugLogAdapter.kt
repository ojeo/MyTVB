package com.tutu.myblbl.feature.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.databinding.CellDebugLogBinding
import java.text.SimpleDateFormat
import java.util.Locale

class DebugLogAdapter : ListAdapter<AppLog.LogEntry, DebugLogAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION
    private var attachedRecyclerView: RecyclerView? = null

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppLog.LogEntry>() {
            override fun areItemsTheSame(oldItem: AppLog.LogEntry, newItem: AppLog.LogEntry): Boolean {
                return oldItem.timestamp == newItem.timestamp && oldItem.tag == newItem.tag
            }

            override fun areContentsTheSame(oldItem: AppLog.LogEntry, newItem: AppLog.LogEntry): Boolean {
                return oldItem == newItem
            }
        }

        private val LEVEL_COLORS = mapOf(
            AppLog.LogEntry.LEVEL_VERBOSE to Color.GRAY,
            AppLog.LogEntry.LEVEL_DEBUG to Color.GRAY,
            AppLog.LogEntry.LEVEL_INFO to Color.CYAN,
            AppLog.LogEntry.LEVEL_WARN to Color.YELLOW,
            AppLog.LogEntry.LEVEL_ERROR to Color.RED
        )

        private val LEVEL_LABELS = mapOf(
            AppLog.LogEntry.LEVEL_VERBOSE to "V",
            AppLog.LogEntry.LEVEL_DEBUG to "D",
            AppLog.LogEntry.LEVEL_INFO to "I",
            AppLog.LogEntry.LEVEL_WARN to "W",
            AppLog.LogEntry.LEVEL_ERROR to "E"
        )
    }

    fun setData(items: List<AppLog.LogEntry>) {
        // 日志是持续追加的，刷新时保留焦点高亮位置，避免每秒自动刷新把高亮刷没；
        // 仅在列表被清空或焦点位置越界时重置。
        val keepFocus = items.isNotEmpty() && focusedPosition in items.indices
        if (!keepFocus) focusedPosition = RecyclerView.NO_POSITION
        submitList(items)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellDebugLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindFocusState(position == focusedPosition)
        }
    }

    inner class ViewHolder(
        private val binding: CellDebugLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 焦点高亮一律直接改 View，不调 notifyItemChanged()。
            // 焦点变化可能发生在 RecyclerView 的 removeDetachedView（切换 Fragment /
            // 列表回收）过程中，此时 notify 会触发 assertNotInLayoutOrScroll，抛
            // "Cannot call this method while RecyclerView is computing a layout or scrolling"。
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
                if (hasFocus) {
                    val old = focusedPosition
                    focusedPosition = position
                    if (old != RecyclerView.NO_POSITION && old != position) {
                        (attachedRecyclerView?.findViewHolderForAdapterPosition(old) as? ViewHolder)
                            ?.bindFocusState(false)
                    }
                    bindFocusState(true)
                } else if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    bindFocusState(false)
                }
            }
        }

        fun bind(entry: AppLog.LogEntry, position: Int) {
            binding.tvTime.text = TIME_FORMAT.format(entry.timestamp)
            binding.tvLevel.text = LEVEL_LABELS[entry.level] ?: "?"
            binding.tvLevel.setTextColor(LEVEL_COLORS[entry.level] ?: Color.GRAY)
            binding.tvTag.text = entry.tag
            binding.tvMessage.text = entry.message
            bindFocusState(position == focusedPosition)
        }

        fun bindFocusState(isFocused: Boolean) {
            binding.root.animate().cancel()
            binding.root.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .setDuration(120L)
                .start()
            if (isFocused) {
                binding.root.setBackgroundResource(com.tutu.myblbl.R.drawable.cell_background)
            } else {
                binding.root.setBackgroundResource(com.tutu.myblbl.R.drawable.cell_setting_background)
            }
        }
    }
}