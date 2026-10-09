package com.mytvb.core.ui.base

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.ui.adapter.SettingSelectionDialogAdapter

/**
 * 通用单选弹窗：统一替换系统 AlertDialog 的排序/清晰度等选择菜单。
 * 系统 AlertDialog 文字小且不跟随 px 池与界面缩放，这里沿用 dialog_setting_choice
 * 布局与全 App 弹窗一致的字号和尺寸钳制。
 */
object ChoiceDialog {

    fun show(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: Int = 0,
        onSelected: (Int) -> Unit
    ) {
        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_setting_choice)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<android.widget.TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = title

        val adapter = SettingSelectionDialogAdapter(
            options = options,
            selectedIndex = selectedIndex
        ) { index ->
            dialog.dismiss()
            onSelected(index)
        }
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    context.resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        dialog.setOnShowListener {
            recyclerView?.post {
                adapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
        DialogWindowFit.apply(
            dialog.window, context,
            context.resources.getDimensionPixelSize(R.dimen.px800),
            context.resources.getDimensionPixelSize(R.dimen.px615)
        )
    }
}
