package com.ninthsoft.ime.input.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import splitties.dimensions.dp
import androidx.core.graphics.drawable.toDrawable

object SchemaPickerDialog {

    private var currentDialog: Dialog? = null

    /**
     * 弹窗里的一行。
     *
     * [onClick] 为 null 表示不可点 —— 英文槽是固定的，只把当前方案展示出来，
     * 不提供任何可切换的动作。
     */
    data class Entry(
        val title: String,
        val subtitle: String? = null,
        val selected: Boolean = false,
        val onClick: (() -> Unit)? = null,
    )

    /**
     * 将可能带有透明度的颜色，与基准底色（默认黑色/深色输入法背景）进行混合，
     * 计算出视觉效果完全一致但 Alpha 为 255（完全不透明）的新颜色。
     */
    private fun getOpaqueColor(color: Int, fallbackBgColor: Int = Color.BLACK): Int {
        val alpha = Color.alpha(color)
        if (alpha == 255) return color
        if (alpha == 0) return fallbackBgColor

        val srcR = Color.red(color)
        val srcG = Color.green(color)
        val srcB = Color.blue(color)

        val bgR = Color.red(fallbackBgColor)
        val bgG = Color.green(fallbackBgColor)
        val bgB = Color.blue(fallbackBgColor)

        val a = alpha / 255.0f
        val r = (srcR * a + bgR * (1 - a)).toInt().coerceIn(0, 255)
        val g = (srcG * a + bgG * (1 - a)).toInt().coerceIn(0, 255)
        val b = (srcB * a + bgB * (1 - a)).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
    }

    fun build(
        context: Context,
        entries: List<Entry>,
        colors: KeyboardColors.ColorScheme,
        onDismiss: () -> Unit = {},
    ): Dialog {
        val opaqueBackgroundColor = getOpaqueColor(colors.specialKeyBackground, colors.background)
        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = context.getString(R.string.choose_schema)
                textSize = 17f
                setTextColor(colors.keyText)
                setPadding(context.dp(28), context.dp(22), context.dp(28), context.dp(22))
            })

            addView(android.view.View(context).apply {
                setBackgroundColor(colors.specialKeyText)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
            })

            val optionsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, context.dp(4), 0, context.dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            entries.forEach { entry ->
                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(context.dp(18), context.dp(8), context.dp(18), context.dp(8))

                    val radioSize = context.dp(18)
                    val slotWidth = context.dp(36)
                    val radioSlot = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            slotWidth, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    val radioView = android.view.View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(context.dp(1.5f).toInt(), colors.accentKeyBackground)
                        }
                        layoutParams = FrameLayout.LayoutParams(radioSize, radioSize).apply {
                            gravity = Gravity.CENTER
                        }
                    }
                    radioSlot.addView(radioView)
                    if (entry.selected) {
                        val innerView = android.view.View(context).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(colors.accentKeyBackground)
                            }
                            layoutParams =
                                FrameLayout.LayoutParams(context.dp(10), context.dp(10)).apply {
                                    gravity = Gravity.CENTER
                                }
                        }
                        radioSlot.addView(innerView)
                    }
                    addView(radioSlot)

                    val textColumn = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                        ).apply {
                            marginStart = context.dp(12)
                        }
                    }

                    textColumn.addView(TextView(context).apply {
                        text = entry.title
                        textSize = 16f
                        setTextColor(colors.keyText)
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    })

                    if (!entry.subtitle.isNullOrBlank()) {
                        textColumn.addView(TextView(context).apply {
                            text = entry.subtitle
                            textSize = 12f
                            setTextColor(colors.specialKeyText)
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = context.dp(2)
                            }
                        })
                    }

                    addView(textColumn)

                    val onClick = entry.onClick
                    if (onClick != null) {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onClick()
                            dismiss()
                        }
                    }
                }

                optionsContainer.addView(itemLayout)
            }

            addView(optionsContainer)

            addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, context.dp(22)
                )
            })
        }

        val cornerRadius = context.dp(16f)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = (screenWidth * 0.84f).toInt()

        val contentView = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(opaqueBackgroundColor)
                setCornerRadius(cornerRadius)
            }
            clipToOutline = true
            addView(innerLayout)
        }

        return Dialog(context).apply {
            setContentView(contentView)
            setOnDismissListener {
                currentDialog = null
                onDismiss()
            }
            currentDialog = this
        }.also { dialog ->
            val w = dialog.window ?: return@also
            w.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

            // 去掉非弹层区域变灰
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)

            val attrs = w.attributes
            attrs.width = dialogWidth
            attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT
            w.attributes = attrs
        }
    }

    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }
}
