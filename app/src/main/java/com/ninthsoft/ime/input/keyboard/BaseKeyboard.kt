package com.ninthsoft.ime.input.keyboard

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.AltTextKeyView
import com.ninthsoft.ime.input.keyboard.key.CustomGestureView
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.ImageTextKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyPreviewPopup
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

abstract class BaseKeyboard(
    context: Context,
    protected val colors: KeyboardColors.ColorScheme,
    private val keyLayout: List<List<KeyDef>>,
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    var expandKeypressArea = false

    private val previewPopup = KeyPreviewPopup(context)

    protected val keyRows: List<ConstraintLayout>

    init {
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView)
            constraintLayout {
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        val def = row[index]
                        matchConstraintPercentWidth = def.appearance.percentWidth
                    })
                    row[index].appearance.percentWidth.let {
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (row.last().appearance.percentWidth + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
    }

    protected fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, colors, def.appearance)
        }.apply {
            val previewText = displayText
            if (previewText != null) {
                onTouchDownListener = { showPreview(it as KeyView) }
                onTouchUpListener = { previewPopup.dismiss() }
            }
            def.behaviors.forEach { behavior ->
                when (behavior) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener({
                            onAction(behavior.action)
                        })
                    }

                    is KeyDef.Behavior.LongPress -> {
                        longPressEnabled = true
                        setOnLongClickListener {
                            onAction(behavior.action)
                            return@setOnLongClickListener true
                        }
                    }

                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { onAction(behavior.action) }
                    }

                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = dp(800f)
                        swipeThresholdY = dp(36f)
                        onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                            when (event.type) {
                                CustomGestureView.GestureType.Up -> {
                                    if (!event.consumed && event.totalY < 0) {
                                        onAction(behavior.action)
                                        true
                                    } else false
                                }

                                else -> false
                            }
                        }
                    }

                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { onAction(behavior.action) }
                    }
                }
            }
        }
    }

    private fun showPreview(view: KeyView) {
        val text = view.displayText ?: return
        previewPopup.show(
            anchor = view,
            text = text,
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
            },
            bgColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyBackground
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyBackground
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyBackground
            },
        )
    }

    protected open fun onAction(action: KeyAction) {
        keyActionListener?.onKeyAction(action)
    }

    open fun onAttach() {}
    open fun onDetach() {
        previewPopup.dismiss()
    }
}
