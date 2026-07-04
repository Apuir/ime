package com.ninthsoft.ime.input.panel.component

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.ninthsoft.ime.base.util.slideDownExpand
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.panel.KawaiiPanel
import splitties.dimensions.dp
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

@SuppressLint("ViewConstructor")
class MenuGridView(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
    var onAction: ((KawaiiPanel.Action) -> Unit)? = null,
) : FrameLayout(context) {

    private data class MenuItem(
        val label: String,
        val icon: Int,
        val action: KawaiiPanel.Action,
    )

    private val items = listOf(
        MenuItem("设置", 0, KawaiiPanel.Action.Settings),
        MenuItem("键盘", 0, KawaiiPanel.Action.SwitchKeyboard),
        MenuItem("剪贴板", 0, KawaiiPanel.Action.Clipboard),
        MenuItem("光标", 0, KawaiiPanel.Action.CursorMove),
        MenuItem("调色板", 0, KawaiiPanel.Action.Palette),
        MenuItem("语音", 0, KawaiiPanel.Action.ToggleVoice),
    )

    init {
        visibility = INVISIBLE
        setBackgroundColor(colors.panel.background)

        val grid = GridLayout(context).apply {
            columnCount = 3
            rowCount = 2
            layoutParams = LayoutParams(matchParent, wrapContent, Gravity.CENTER)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        for (item in items) {
            grid.addView(createItemView(item))
        }

        addView(grid)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val v = visibility
        visibility = VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        visibility = v
    }

    private fun createItemView(item: MenuItem): View {
        val itemView = FrameLayout(context).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(56)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            setOnClickListener { onAction?.invoke(item.action) }
        }

        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
            setImageResource(item.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_help)
            setColorFilter(colors.panel.toolbarText)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val label = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(wrapContent, wrapContent).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(32)
            }
            text = item.label
            textSize = 11f
            typeface = Typeface.DEFAULT
            setTextColor(colors.panel.toolbarText)
            gravity = Gravity.CENTER
        }

        itemView.addView(icon)
        itemView.addView(label)
        return itemView
    }

    fun show() {
        if (visibility != VISIBLE) {
            bringToFront()
            slideDownExpand()
        }
    }

    fun hide() {
        if (visibility != VISIBLE) return
        var cancelled = false
        animate().scaleY(0f).setDuration(200).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                visibility = INVISIBLE
                scaleY = 1f
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }
        }).start()
    }

    fun reset() {
        animate().cancel()
        visibility = INVISIBLE
        scaleY = 1f
    }
}
