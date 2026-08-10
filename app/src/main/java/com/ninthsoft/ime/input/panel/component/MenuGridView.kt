package com.ninthsoft.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.geometry.Size
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.size
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.panel.KawaiiPanel
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent

@SuppressLint("ViewConstructor")
class MenuGridView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    var onAction: ((KawaiiPanel.Action) -> Unit)? = null,
) : ComponentView(context, colors) {

    private data class MenuItem(
        val label: String,
        val icon: Int,
        val action: KawaiiPanel.Action,
    )

    private val items = listOf(
        MenuItem(context.getString(R.string.menu_emoji), R.drawable.ic_keyboard_emoticon, KawaiiPanel.Action.EmojiKeyboard),
        MenuItem(context.getString(R.string.menu_clipboard), R.drawable.ic_keyboard_clipboard, KawaiiPanel.Action.Clipboard),
        MenuItem(context.getString(R.string.menu_voice), R.drawable.ic_keyboard_voice, KawaiiPanel.Action.ToggleVoice),
        MenuItem(context.getString(R.string.menu_cursor), R.drawable.ic_keyboard_cursor_move, KawaiiPanel.Action.CursorMove),
        MenuItem(context.getString(R.string.menu_settings), R.drawable.ic_keyboard_setting, KawaiiPanel.Action.Settings),
        MenuItem(context.getString(R.string.menu_schema), R.drawable.ic_keyboard_tune, KawaiiPanel.Action.SchemaSettings),
        MenuItem(context.getString(R.string.menu_theme), R.drawable.ic_keyboard_palette, KawaiiPanel.Action.Palette),
        MenuItem(context.getString(R.string.menu_reload_engine), R.drawable.ic_keyboard_reload, KawaiiPanel.Action.ReloadEngine),
    )

    private val columns = 4
    private val radius = dp(16).toFloat()
    private val iconSize = dp(28)

    var gap: Int = dp(12)
        private set

    var pad: Int = dp(24)
        private set

    var itemSize: Int = 0
        private set

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(matchParent, wrapContent)
        }

        val rows = (items.size + columns - 1) / columns
        for (rowIndex in 0 until rows) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    setPadding(
                        pad,
                        if (rowIndex == 0) pad else gap,
                        pad,
                        if (rowIndex == rows - 1) pad else 0,
                    )
                }
            }
            for (colIndex in 0 until columns) {
                val itemIndex = rowIndex * columns + colIndex
                if (itemIndex < items.size) {
                    row.addView(
                        createItemView(items[itemIndex]), LinearLayout.LayoutParams(0, 0).apply {
                            if (colIndex < columns - 1) marginEnd = gap
                        })
                }
            }
            root.addView(row)
        }

        addView(root)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val v = visibility
        visibility = VISIBLE

        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val totalHeight = MeasureSpec.getSize(heightMeasureSpec)
        val root = getChildAt(0) as ViewGroup
        val rows = root.childCount
        if (rows <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            visibility = v
            return
        }

        val widthBased = (totalWidth - pad * 2 - gap * (columns - 1)) / columns
        val heightBased = (totalHeight - pad * 2 - gap * (rows - 1)) / rows
        itemSize = minOf(widthBased, heightBased).coerceAtLeast(0)

        for (i in 0 until rows) {
            val row = root.getChildAt(i) as ViewGroup
            row.setPadding(
                pad,
                if (i == 0) pad else gap,
                pad,
                if (i == rows - 1) pad else 0,
            )
            for (j in 0 until row.childCount) {
                val child = row.getChildAt(j)
                val lp = child.layoutParams
                lp.width = itemSize
                lp.height = itemSize
                child.layoutParams = lp
                (lp as? LinearLayout.LayoutParams)?.marginEnd = if (j < columns - 1) gap else 0
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        visibility = v
    }

    private fun createItemView(item: MenuItem): View {
        val bg = GradientDrawable().apply {
            setColor(this@MenuGridView.colors.keyBackground)
            cornerRadius = radius
        }

        val parentId = View.generateViewId()

        return constraintLayout {
            id = parentId
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener { onAction?.invoke(item.action) }

            val icon = imageView {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(item.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_help)
                imageTintList =
                    android.content.res.ColorStateList.valueOf(this@MenuGridView.colors.panel.toolbarIcon)
            }
            add(icon, lParams(iconSize, iconSize) {
                centerHorizontally()
                topToTop = parentId
                bottomToBottom = parentId
                verticalBias = 0.30f
            })

            val label = textView {
                text = item.label
                textSize = 12f
                setTextColor(this@MenuGridView.colors.panel.toolbarText)
                gravity = Gravity.CENTER
            }
            add(label, lParams(wrapContent, wrapContent) {
                centerHorizontally()
                topToTop = parentId
                bottomToBottom = parentId
                verticalBias = 0.85f
            })
        }
    }
}
