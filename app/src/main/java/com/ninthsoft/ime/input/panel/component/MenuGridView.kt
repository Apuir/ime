package com.ninthsoft.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.input.panel.PanelAction
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
    var onAction: ((PanelAction) -> Unit)? = null,
) : ComponentView(context, colors) {

    private data class MenuItem(
        val label: String,
        val icon: Int,
        val action: PanelAction,
    )

    // 工具顺序就是这里的平铺顺序（内层分组只是可读性，以前一组是一页）。
    private val items: List<MenuItem> = arrayOf(
        arrayOf(
            MenuItem(
                context.getString(R.string.menu_emoji),
                R.drawable.ic_keyboard_emoticon,
                PanelAction.EmojiKeyboard
            ),
            MenuItem(
                context.getString(R.string.menu_symbol),
                R.drawable.ic_keyboard_symbol,
                PanelAction.SymbolKeyboard
            ),
            MenuItem(
                context.getString(R.string.tool_resize_keyboard),
                R.drawable.ic_keyboard_resize,
                PanelAction.ResizeKeyboard
            ),
            MenuItem(
                context.getString(R.string.model_prediction),
                R.drawable.ic_keyboard_lightbulb_on_outline,
                PanelAction.TogglePrediction
            ),
            MenuItem(
                context.getString(R.string.menu_candidate_comment),
                R.drawable.ic_keyboard_bubble,
                PanelAction.ToggleShowComment
            ),
            MenuItem(
                context.getString(R.string.menu_traditional_chinese),
                R.drawable.ic_keyboard_traditional_ch,
                PanelAction.ToggleTraditionalChinese
            ),
            MenuItem(
                context.getString(R.string.menu_ascii_mode),
                R.drawable.ic_keyboard_english_mode,
                PanelAction.ToggleAsciiMode
            ),
            MenuItem(
                context.getString(R.string.menu_emoji_input),
                R.drawable.ic_keyboard_sticker_emoji,
                PanelAction.ToggleEmojiInput
            ),
        ),
        arrayOf(
            MenuItem(
                context.getString(R.string.menu_voice),
                R.drawable.ic_keyboard_voice,
                PanelAction.ToggleVoice
            ),
            MenuItem(
                context.getString(R.string.menu_clipboard),
                R.drawable.ic_keyboard_clipboard,
                PanelAction.Clipboard
            ),
            MenuItem(
                context.getString(R.string.phrase_tab),
                R.drawable.ic_keyboard_star_david,
                PanelAction.CommonPhrases
            ),
            MenuItem(
                context.getString(R.string.menu_cursor),
                R.drawable.ic_keyboard_cursor_move,
                PanelAction.CursorMove
            ),
            MenuItem(
                context.getString(R.string.menu_schema),
                R.drawable.ic_keyboard_tune,
                PanelAction.SchemaSettings
            ),
            MenuItem(
                context.getString(R.string.menu_switch_layout),
                R.drawable.ic_keyboard_layout_switch,
                PanelAction.SwitchLayout
            ),
            MenuItem(
                context.getString(R.string.menu_settings),
                R.drawable.ic_keyboard_setting,
                PanelAction.Settings
            ),
            MenuItem(
                context.getString(R.string.menu_theme),
                R.drawable.ic_keyboard_palette,
                PanelAction.Palette
            ),
            MenuItem(
                context.getString(R.string.menu_reload_engine),
                R.drawable.ic_keyboard_reload,
                PanelAction.ReloadEngine
            ),
        ),
        arrayOf(
            MenuItem(
                context.getString(R.string.menu_about),
                R.drawable.ic_keyboard_information_outline,
                PanelAction.About
            ),
        ),
    ).flatten()

    private val columns = 4

    /**
     * 算键格大小时按「一屏两行」估：与分页那会儿一致，所以工具的**大小没变**；
     * 行数多出这一屏就纵向滑动（单页、无级滑动，不翻页也不吸附）。
     */
    private val rowsPerScreen = 2
    private val radius = dp(16).toFloat()

    var gap: Int = dp(BASE_GAP_DP)
        private set

    var pad: Int = dp(BASE_PAD_DP)
        private set

    var itemSize: Int = 0
        private set

    /** 图标边长（px）。随笔格等比缩小，见 [REFERENCE_ITEM_DP]。 */
    private var iconSize: Int = dp(ICON_DP)

    /** 工具名字号（sp）。随笔格等比缩小，见 [REFERENCE_ITEM_DP]。 */
    private var labelSizeSp: Float = LABEL_SP

    /** 单页网格的滚动容器。关掉滚动条与边缘光晕：在 IME 里视觉上很脏。 */
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private val grid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    init {
        scroller.addView(grid, ViewGroup.LayoutParams(matchParent, wrapContent))
        addView(scroller, LayoutParams(matchParent, matchParent))
        // 单页：按 4 列一行行铺，行内左对齐（最后一行不满也从左侧起排）
        items.chunked(columns).forEach { rowItems -> grid.addView(createRow(rowItems)) }
    }

    /** 一行：左对齐铺开，不满一行时剩下的位置空着（**不居中**）。 */
    private fun createRow(rowItems: List<MenuItem>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
            rowItems.forEachIndexed { index, item ->
                addView(
                    createItemView(item),
                    LinearLayout.LayoutParams(0, 0).apply {
                        if (index < rowItems.lastIndex) marginEnd = gap
                    },
                )
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val oldVisibility = visibility
        visibility = VISIBLE

        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val totalHeight = MeasureSpec.getSize(heightMeasureSpec)
        // 先用原始间距估一版键格，拿它定「等比缩放」系数，再按缩放后的间距算最终键格：
        // 横屏悬浮卡片、键盘被拖小时，格子 / 图标 / 文字 / 间距一起变小，
        // 而不是只有格子变小、字还那么大。键格本来就够大时系数封顶 1，观感与以前一致。
        val probe = minOf(
            (totalWidth - dp(BASE_PAD_DP) * 2 - dp(BASE_GAP_DP) * (columns - 1)) / columns,
            (totalHeight - dp(BASE_PAD_DP) * 2 - dp(BASE_GAP_DP) * (rowsPerScreen - 1)) /
                rowsPerScreen,
        ).coerceAtLeast(0)
        val scale = ((probe / resources.displayMetrics.density) / REFERENCE_ITEM_DP)
            .coerceIn(MIN_SCALE, 1f)
        pad = (dp(BASE_PAD_DP) * scale).roundToInt()
        gap = (dp(BASE_GAP_DP) * scale).roundToInt()
        iconSize = (dp(ICON_DP) * scale).roundToInt()
        labelSizeSp = LABEL_SP * scale

        val widthBased = (totalWidth - pad * 2 - gap * (columns - 1)) / columns
        val heightBased =
            (totalHeight - pad * 2 - gap * (rowsPerScreen - 1)) / rowsPerScreen
        itemSize = minOf(widthBased, heightBased).coerceAtLeast(0)

        val lastRow = grid.childCount - 1
        for (rowIndex in 0 until grid.childCount) {
            val row = grid.getChildAt(rowIndex) as ViewGroup
            row.setPadding(
                pad,
                if (rowIndex == 0) pad else gap,
                pad,
                // 滚到底时底部也要有一圈内边距，与顶部对称
                if (rowIndex == lastRow) pad else 0,
            )
            for (itemIndex in 0 until row.childCount) {
                val item = row.getChildAt(itemIndex) as ViewGroup
                item.layoutParams = item.layoutParams.apply {
                    width = itemSize
                    height = itemSize
                }
                // 图标与文字跟着键格一起缩放（文字用 sp：系统字号放大时它跟着放大）
                for (childIndex in 0 until item.childCount) {
                    when (val child = item.getChildAt(childIndex)) {
                        is ImageView -> child.layoutParams = child.layoutParams.apply {
                            width = iconSize
                            height = iconSize
                        }

                        is TextView -> child.setTextSize(
                            TypedValue.COMPLEX_UNIT_SP, labelSizeSp
                        )
                    }
                }
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        visibility = oldVisibility
    }

    private fun createItemView(item: MenuItem): View {
        val bg = GradientDrawable().apply {
            setColor(this@MenuGridView.colors.keyBackground)
            cornerRadius = radius
        }
        val parentId = View.generateViewId()
        return constraintLayout {
            id = parentId
            tag = item.action
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                InputFeedbacks.hapticFeedback(this)
                onAction?.invoke(item.action)
            }

            val icon = imageView {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(item.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_help)
                imageTintList = ColorStateList.valueOf(itemIconColor(item))
            }
            add(icon, lParams(iconSize, iconSize) {
                centerHorizontally()
                topToTop = parentId
                bottomToBottom = parentId
                verticalBias = 0.30f
            })

            val label = textView {
                text = item.label
                textSize = labelSizeSp
                setTextColor(itemTextColor(item))
                typeface = Typeface.DEFAULT
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

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        setBackgroundColor(newColors.background)
        for (rowIndex in 0 until grid.childCount) {
            val row = grid.getChildAt(rowIndex) as ViewGroup
            for (itemIndex in 0 until row.childCount) {
                val item = row.getChildAt(itemIndex) as ViewGroup
                (item.background as? GradientDrawable)?.setColor(newColors.keyBackground)
                val action = item.tag as? PanelAction
                for (childIndex in 0 until item.childCount) {
                    when (val child = item.getChildAt(childIndex)) {
                        is ImageView -> {
                            if (isToggleAction(action)) {
                                child.setImageResource(toggleIcon(action!!))
                            }
                            child.imageTintList = ColorStateList.valueOf(itemIconColor(action))
                        }

                        is TextView -> child.setTextColor(itemTextColor(action))
                    }
                }
            }
        }
    }

    fun refreshPredictionState() {
        for (rowIndex in 0 until grid.childCount) {
            val row = grid.getChildAt(rowIndex) as ViewGroup
            for (itemIndex in 0 until row.childCount) {
                val item = row.getChildAt(itemIndex) as ViewGroup
                if (!isToggleAction(item.tag as? PanelAction)) continue
                for (childIndex in 0 until item.childCount) {
                    when (val child = item.getChildAt(childIndex)) {
                        is ImageView -> {
                            child.setImageResource(toggleIcon(item.tag as PanelAction))
                            child.imageTintList =
                                ColorStateList.valueOf(itemIconColor(item.tag as PanelAction))
                        }

                        is TextView -> child.setTextColor(itemTextColor(item.tag as PanelAction))
                    }
                }
            }
        }
    }

    private fun isToggleAction(action: PanelAction?): Boolean =
        action == PanelAction.TogglePrediction ||
            action == PanelAction.ToggleShowComment ||
            action == PanelAction.ToggleTraditionalChinese ||
            action == PanelAction.ToggleEmojiInput ||
            action == PanelAction.ToggleAsciiMode

    private fun isEnabled(action: PanelAction): Boolean = when (action) {
        PanelAction.TogglePrediction -> CandidateManager.isPredictionEnabled(context)
        PanelAction.ToggleShowComment -> CandidateManager.isShowComment(context)
        PanelAction.ToggleTraditionalChinese -> CandidateManager.isTraditionalChineseEnabled(context)
        PanelAction.ToggleEmojiInput -> CandidateManager.isEmojiEnabled(context)
        PanelAction.ToggleAsciiMode -> CandidateManager.isAsciiModeEnabled(context)
        else -> false
    }


    private fun toggleIcon(action: PanelAction): Int = when (action) {
        PanelAction.TogglePrediction ->  R.drawable.ic_keyboard_lightbulb_on_outline
        PanelAction.ToggleShowComment -> R.drawable.ic_keyboard_bubble
        PanelAction.ToggleTraditionalChinese -> R.drawable.ic_keyboard_traditional_ch
        PanelAction.ToggleEmojiInput -> R.drawable.ic_keyboard_sticker_emoji
        PanelAction.ToggleAsciiMode -> R.drawable.ic_keyboard_english_mode
        else -> android.R.drawable.ic_menu_help
    }


    private companion object {
        /** 键格参考尺寸（dp）：图标 28dp / 字号 12sp 是照它定的，键格小于它才等比缩小。 */
        const val REFERENCE_ITEM_DP = 64f

        /** 缩放下限：键盘再小也留一个能看清的下限，不至于缩成一条线。 */
        const val MIN_SCALE = 0.4f

        const val BASE_PAD_DP = 16
        const val BASE_GAP_DP = 10
        const val ICON_DP = 28
        const val LABEL_SP = 12f
    }

    private fun itemIconColor(item: MenuItem): Int = itemIconColor(item.action)

    private fun itemIconColor(action: PanelAction?): Int =
        if (isToggleAction(action) && isEnabled(action!!)) colors.panel.toolbarActived else colors.panel.toolbarIcon

    private fun itemTextColor(item: MenuItem): Int = itemTextColor(item.action)

    private fun itemTextColor(action: PanelAction?): Int =
        if (isToggleAction(action) && isEnabled(action!!)) colors.panel.toolbarActived else colors.panel.toolbarText
}
