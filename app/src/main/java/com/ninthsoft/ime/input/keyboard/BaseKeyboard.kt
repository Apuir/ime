package com.ninthsoft.ime.input.keyboard

import android.content.Context
import android.view.View
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
import com.ninthsoft.ime.input.keyboard.key.KeyboardRippleView
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
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
    val rippleView: KeyboardRippleView

    protected val sidePanelViews: List<KeyView> get() = _spanPanelViews
    private var _spanPanelViews: List<KeyView> = emptyList()

    fun updateSidePanel(items: List<KeyDef>) {
        (_spanPanelViews.firstOrNull() as? SidePanelKeyView)?.updateItems(items)
    }

    fun setSidePanelItemListener(listener: (KeyAction) -> Unit) {
        (_spanPanelViews.firstOrNull() as? SidePanelKeyView)?.setOnItemActionListener(listener)
    }

    init {
        data class SpanDef(val def: KeyDef, val startRow: Int, val endRow: Int)

        val spanDefs = mutableListOf<SpanDef>()
        for (ri in keyLayout.indices) {
            for (def in keyLayout[ri]) {
                if (def.appearance is KeyDef.Appearance.SidePannel && def.appearance.rowSpan > 1) {
                    spanDefs.add(
                        SpanDef(
                            def,
                            ri,
                            (ri + def.appearance.rowSpan - 1).coerceAtMost(keyLayout.size - 1)
                        )
                    )
                }
            }
        }
        val spanViews =
            spanDefs.map { createKeyView(it.def).also { v -> v.id = View.generateViewId() } }
        _spanPanelViews = spanViews

        keyRows = keyLayout.mapIndexed { rowIndex, row ->
            val parts =
                row.filterNot { it.appearance is KeyDef.Appearance.SidePannel && it.appearance.rowSpan > 1 }
            val keyViews = parts.map(::createKeyView)
            val sidePanelAtRow = spanDefs.indexOfFirst { rowIndex in it.startRow..it.endRow }
            val spanScale = if (sidePanelAtRow >= 0) {
                1f / (1f - spanDefs[sidePanelAtRow].def.appearance.percentWidth)
            } else 1f

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
                        matchConstraintPercentWidth =
                            parts[index].appearance.percentWidth * spanScale
                    })
                    (parts[index].appearance.percentWidth * spanScale).let {
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    val firstScaledPercent = parts.first().appearance.percentWidth * spanScale
                    val lastScaledPercent = parts.last().appearance.percentWidth * spanScale
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (firstScaledPercent + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (lastScaledPercent + free)
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
                val sidePanelAtRow = spanDefs.indexOfFirst { index in it.startRow..it.endRow }
                if (sidePanelAtRow >= 0) {
                    leftToRightOf(spanViews[sidePanelAtRow])
                } else {
                    leftOfParent()
                }
                rightOfParent()
            })
        }

        for (i in spanDefs.indices) {
            val sd = spanDefs[i]
            add(spanViews[i], lParams {
                topToTop = keyRows[sd.startRow].id
                bottomToBottom = keyRows[sd.endRow].id
                leftOfParent()
                matchConstraintPercentWidth = sd.def.appearance.percentWidth
            })
        }

        rippleView = KeyboardRippleView(context).apply {
            id = View.generateViewId()
            isClickable = false
            isEnabled = false
            isFocusable = false
        }
        add(rippleView, lParams {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
    }

    protected fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.SidePannel -> SidePanelKeyView(context, colors, def.appearance)
        }.apply {
            onPressedChanged = { key ->
                if (key.isPressed) {
                    val keyLoc = IntArray(2)
                    val boardLoc = IntArray(2)
                    key.getLocationOnScreen(keyLoc)
                    this@BaseKeyboard.getLocationOnScreen(boardLoc)
                    val cx = keyLoc[0] + key.width / 2f - boardLoc[0]
                    val cy = keyLoc[1] + key.height / 2f - boardLoc[1]
                    rippleView.startRipple(cx, cy, key)
                }
            }
            if (this is SidePanelKeyView) {
                onRippleRequest = { screenX, screenY ->
                    val boardLoc = IntArray(2)
                    this@BaseKeyboard.getLocationOnScreen(boardLoc)
                    rippleView.startRipple(screenX - boardLoc[0], screenY - boardLoc[1], this)
                }
            }
            def.popups?.forEach { popup ->
                when (popup) {
                    is KeyDef.Popup.Preview -> {
                        setOnLongClickListener {
                            showPreview(it as KeyView)
                            return@setOnLongClickListener true
                        }
                        onTouchUpListener = { previewPopup.dismiss() }
                    }

                    else -> {}
                }
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

    fun setRippleEnabled(enabled: Boolean) {
        rippleView.rippleEnabled = enabled
        rippleView.visibility = if (enabled) VISIBLE else INVISIBLE
    }
}
