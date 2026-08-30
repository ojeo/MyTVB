package com.tutu.myblbl.feature.keybinding

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.databinding.DialogKeyCaptureBinding

/**
 * 按键捕获对话框：提示用户按下遥控器按键，捕获后写入 [KeyBindingStore]。
 *
 * 有两种状态，共用同一个 [com.tutu.myblbl.databinding.DialogKeyCaptureBinding.dialog_root]
 * 背景，靠切换 [captureContainer] / [conflictContainer] 的可见性实现：
 * 1. 捕获态：等待按键，可"清除绑定"或"取消"；
 * 2. 冲突确认态：该按键已被本场景其它动作占用，询问是否替换。
 *
 * 冲突确认刻意**不使用** `AlertDialog.Builder(context, R.style.DialogTheme)`：
 * 该主题把 `android:windowBackground` 设为 transparent，弹窗没有背景，
 * 在黑色底上无法辨识。复用本对话框的根布局即可继承 `dialog_background`。
 */
class KeyCaptureDialog(
    context: Context,
    private val action: KeyBindingAction,
    private val store: KeyBindingStore,
    /** 绑定关系发生变化（绑定成功 / 清除绑定）后回调，供调用方刷新列表。 */
    private val onCommitted: () -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogKeyCaptureBinding.inflate(LayoutInflater.from(context))

    /** 待确认的按键；仅在冲突确认态有意义。 */
    private var pendingKeyCode: Int = BindableKeyCatalog.UNBOUND

    /** true = 捕获态（等待按键）；false = 冲突确认态（等待选择替换/取消）。 */
    private var capturing: Boolean = true

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 待延迟显示的"不可用"提示对应的 keyCode；[BindableKeyCatalog.UNBOUND] 表示无待显示。 */
    private var pendingUnsupportedKeyCode: Int = BindableKeyCatalog.UNBOUND

    /**
     * 延迟显示的"该按键不可用"提示。
     *
     * 红外遥控器按下同一个物理键时可能先后投递**多个不同 keyCode** 的事件，
     * 其中一个落在保留键范围内、另一个可绑定。若立即弹提示，就会出现
     * "提示不可用、但实际已绑定成功"的矛盾表现。
     * 因此延后一小段时间再显示，期间一旦成功绑定就取消掉。
     */
    private val showUnsupportedRunnable = Runnable {
        val keyCode = pendingUnsupportedKeyCode
        pendingUnsupportedKeyCode = BindableKeyCatalog.UNBOUND
        if (!capturing || keyCode == BindableKeyCatalog.UNBOUND) return@Runnable
        Toast.makeText(
            context,
            context.getString(R.string.key_capture_unsupported, keyCode),
            Toast.LENGTH_SHORT
        ).show()
    }

    private companion object {
        const val TAG = "KeyCaptureDialog"

        /** "不可用"提示的延迟显示时长：足以覆盖同一个物理按键的多个伴随事件。 */
        const val UNSUPPORTED_TOAST_DELAY_MS = 400L
    }

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)

        binding.topTitle.text = action.title
        renderCurrent()

        binding.buttonClear.setOnClickListener {
            store.unbind(action.scene, action)
            onCommitted()
            Toast.makeText(context, R.string.key_binding_unbound, Toast.LENGTH_SHORT).show()
            dismiss()
        }
        binding.buttonCancel.setOnClickListener { dismiss() }
        binding.buttonReplace.setOnClickListener { commitPending() }
        binding.buttonConflictCancel.setOnClickListener { backToCapture() }

        setOnShowListener {
            setupDialogWindowSize()
            // 焦点直接落在按钮上并 post 执行：对话框窗口焦点在 onShow 时可能尚未就绪，
            // 且根布局若先拿焦点，方向键无法下沉到子按钮。
            binding.buttonClear.post { binding.buttonClear.requestFocus() }
        }
    }

    /**
     * 显式设置对话框窗口尺寸。
     *
     * 根布局用 `match_parent`，但 floating Dialog 窗口默认是 `WRAP_CONTENT`。
     * 如果不设窗口宽度，`match_parent` 会受限于窗口自身很窄的测量结果，
     * 导致消息文本被挤成竖排（每行只有几个字），底部按钮被挤出可视区域。
     *
     * 宽度取屏幕宽度的 60%（上限为屏幕宽度 - 80px，防止在手机上超出屏幕）；
     * 高度始终 `WRAP_CONTENT`，由内容决定。
     */
    private fun setupDialogWindowSize() {
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.6).toInt().coerceAtMost(dm.widthPixels - 80)
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun renderCurrent() {
        val current = store.boundKeyOf(action.scene, action)
        binding.tvCaptured.text = BindableKeyCatalog.displayName(current)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = BindableKeyCatalog.normalize(event.keyCode)
        val isNavigation = isNavigationKey(keyCode)

        if (event.action != KeyEvent.ACTION_DOWN) {
            // UP：导航键与返回键必须放行给 View 树，否则按钮的 performClick 不会触发
            //（Android 的点击是在 UP 时派发的，吞掉 UP 会导致确定键点不动按钮）。
            // 捕获态下其它按键的 UP 则吞掉，避免焦点落在按钮上时误触发点击。
            return if (!capturing || isNavigation || keyCode == KeyEvent.KEYCODE_BACK) {
                super.dispatchKeyEvent(event)
            } else {
                true
            }
        }

        if (event.repeatCount > 0) {
            // 长按连发：导航键放行（焦点可连续移动），其余吞掉，避免同一物理按键被
            // 重复处理（表现为"提示不可用但实际已绑定"）。
            return if (isNavigation) super.dispatchKeyEvent(event) else true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (capturing) dismiss() else backToCapture()
            return true
        }

        // 冲突确认态：按键全部交给 View 树，让"确认 / 取消"按钮可用
        if (!capturing) {
            return super.dispatchKeyEvent(event)
        }

        // 捕获态：方向键与确定键放行，保证对话框内的按钮可用
        if (isNavigation) {
            return super.dispatchKeyEvent(event)
        }

        if (BindableKeyCatalog.isBindable(keyCode)) {
            // 成功捕获：取消可能待显示的"不可用"提示
            cancelUnsupportedToast()
            onKeyCaptured(keyCode)
        } else {
            // 红外遥控器的按键编码各家厂商差异很大，把真实 keyCode 打进日志并显示在提示里，
            // 便于定位未知遥控器发送的实际编码。
            AppLog.i(TAG, "capture unsupported: keyCode=$keyCode name=${BindableKeyCatalog.displayName(keyCode)}")
            scheduleUnsupportedToast(keyCode)
        }
        return true
    }

    private fun scheduleUnsupportedToast(keyCode: Int) {
        pendingUnsupportedKeyCode = keyCode
        mainHandler.removeCallbacks(showUnsupportedRunnable)
        mainHandler.postDelayed(showUnsupportedRunnable, UNSUPPORTED_TOAST_DELAY_MS)
    }

    private fun cancelUnsupportedToast() {
        mainHandler.removeCallbacks(showUnsupportedRunnable)
        pendingUnsupportedKeyCode = BindableKeyCatalog.UNBOUND
    }

    private fun isNavigationKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
    }

    private fun onKeyCaptured(keyCode: Int) {
        val conflict = store.conflictOwner(action.scene, keyCode, action)
        if (conflict == null) {
            commit(keyCode)
            return
        }
        showConflict(conflict, keyCode)
    }

    private fun showConflict(conflict: KeyBindingAction, keyCode: Int) {
        pendingKeyCode = keyCode
        capturing = false
        binding.captureContainer.visibility = View.GONE
        binding.conflictContainer.visibility = View.VISIBLE
        binding.conflictMessage.text = context.getString(
            R.string.key_capture_conflict_message,
            conflict.title,
            action.title
        )
        relayoutWindow { binding.buttonReplace.requestFocus() }
    }

    private fun backToCapture() {
        pendingKeyCode = BindableKeyCatalog.UNBOUND
        cancelUnsupportedToast()
        capturing = true
        binding.conflictContainer.visibility = View.GONE
        binding.captureContainer.visibility = View.VISIBLE
        relayoutWindow { binding.buttonClear.requestFocus() }
    }

    /**
     * 强制对话框窗口按当前内容重新测量尺寸。
     *
     * 对话框是 floating 窗口，尺寸在首次 `show()` 时测定。之后只切换子 View 的
     * 可见性并不会触发窗口重新测量，窗口会停留在旧高度，把切换后更高（或不同高度）
     * 的内容裁掉，表现为"界面显示不全"。
     */
    private fun relayoutWindow(onRelayout: (() -> Unit)? = null) {
        val dialogWindow = window ?: return
        binding.root.post {
            // 宽度沿用窗口当前值（不可改成 WRAP_CONTENT，否则根布局的 match_parent
            // 会随之收缩，退回"对话框很窄"的表现）；只把高度置为 WRAP_CONTENT 触发重测。
            dialogWindow.setLayout(
                dialogWindow.attributes.width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // setLayout 要等下一次 traversal 才生效，焦点请求必须再延后一帧，
            // 否则会对尚未完成布局的 View 请求焦点，系统为使其可见而产生的滚动
            // 会把内容裁掉，表现为"界面显示不全"。
            binding.root.post { onRelayout?.invoke() }
        }
    }

    private fun commitPending() {
        val keyCode = pendingKeyCode
        if (keyCode == BindableKeyCatalog.UNBOUND) {
            backToCapture()
            return
        }
        commit(keyCode)
    }

    override fun dismiss() {
        cancelUnsupportedToast()
        super.dismiss()
    }

    private fun commit(keyCode: Int) {
        cancelUnsupportedToast()
        store.bind(action.scene, action, keyCode)
        AppLog.i(
            TAG,
            "capture bound: action=${action.storageKey} " +
                "keyCode=$keyCode name=${BindableKeyCatalog.displayName(keyCode)}"
        )
        Toast.makeText(
            context,
            context.getString(
                R.string.key_binding_bound_to,
                action.title,
                BindableKeyCatalog.displayName(keyCode)
            ),
            Toast.LENGTH_SHORT
        ).show()
        onCommitted()
        dismiss()
    }
}
