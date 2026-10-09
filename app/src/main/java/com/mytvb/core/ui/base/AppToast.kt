package com.mytvb.core.ui.base

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Toast
import com.mytvb.R

/**
 * 大字 Toast：系统默认 Toast 文字过小（TV 远距离不可读），
 * 自绘深色圆角底 + px 池字号，与全局 UI 缩放同通道。
 * 文案简短时与系统 Toast 一样即显即走，仅样式更醒目。
 */
object AppToast {

    fun show(context: Context, text: String, duration: Int = Toast.LENGTH_SHORT) {
        val view = ScaledTextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.px36)
            )
            gravity = Gravity.CENTER
            setPadding(
                resources.getDimensionPixelSize(R.dimen.px40),
                resources.getDimensionPixelSize(R.dimen.px18),
                resources.getDimensionPixelSize(R.dimen.px40),
                resources.getDimensionPixelSize(R.dimen.px18)
            )
            background = GradientDrawable().apply {
                setColor(0xCC1C1C1E.toInt())
                cornerRadius = resources.getDimension(R.dimen.px15)
            }
        }
        Toast(context).apply {
            setView(view)
            this.duration = duration
            // API 30+ 会忽略自定义 toast 的位置，退回系统默认底部，视觉仍成立
            setGravity(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                context.resources.getDimensionPixelSize(R.dimen.px80)
            )
        }.show()
    }
}
