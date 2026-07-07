package com.ninthsoft.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.ninthsoft.ime.base.util.slideDownExpand
import com.ninthsoft.ime.base.util.slideUpCollapse
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.nextPageKey
import com.ninthsoft.ime.input.keyboard.key.prevPageKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import kotlin.math.abs
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

@SuppressLint("ViewConstructor")
class CandidateGridView(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onPrevPage: (() -> Unit)? = null,
    var onNextPage: (() -> Unit)? = null,
    var onBackspace: (() -> Unit)? = null,
    var onReturn: (() -> Unit)? = null,
    var onSidePanelAction: ((KeyboardAction) -> Unit)? = null,
    var subscribePossibleCandidatePinYin: Boolean = true,
) : FrameLayout(context) {

    private class CellPos(val row: Int, val col: Int, val wide: Boolean, val extraWide: Boolean)

    private var candidates: List<EngineMessage.Candidate> = emptyList()

    // ── Left: side panel ──
    private val sidePanelKey = SidePanelKeyView(
        context, colors,
        KeyDef.Appearance.SidePannel(
            rowSpan = 4,
            visableRow = 5,
            margin = false,
            variant = KeyDef.Appearance.Variant.None,
        ),
    ).apply { setOnItemActionListener { action -> onSidePanelAction?.invoke(action) } }

    private val sidePanelPunctuationItems: List<KeyDef>

    // ── Center: grid canvas ──
    private val gridCanvas = object : View(context) {
        private var pressedIndex = -1
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val cols = 4
        private var rowH = 0f
        private var colW = 0f

        private var positions = emptyList<CellPos>()
        private val rowScrollX = mutableMapOf<Int, Float>()
        private var downX = 0f
        private var horizontalDrag = -1

        fun updateColors(pc: KeyboardColors.ColorScheme.PanelColors) {
            bgPaint.color = pc.background
            textPaint.color = pc.candidateText
            sepPaint.color = pc.candidateDivider
            pressPaint.color = pc.candidateBackground
        }

        fun recomputeLayout() {
            if (colW <= 0f || width <= 0f) { positions = emptyList(); return }
            val list = this@CandidateGridView.candidates
            val result = mutableListOf<CellPos>()
            var row = 0; var col = 0
            for (c in list) {
                val tw = textPaint.measureText(c.text); val wide = tw > colW
                if (wide) {
                    result.add(CellPos(row, 0, true, tw > width))
                    row++; col = 0
                } else {
                    result.add(CellPos(row, col, false, false)); col++
                    if (col >= cols) { row++; col = 0 }
                }
            }
            positions = result; rowScrollX.clear()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val d = resources.displayMetrics.density
            textPaint.textSize = 17f * d; rowH = 40f * d; colW = w.toFloat() / cols
            sepPaint.strokeWidth = 1f * d
            updateColors(colors.panel); recomputeLayout()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            for (i in candidates.indices) {
                if (i >= positions.size) break
                val pos = positions[i]; val c = candidates[i]
                val y = pos.row * rowH
                if (y + rowH > height) break
                val cl = if (pos.wide) 0f else pos.col * colW
                val cw = if (pos.wide) width.toFloat() else colW
                if (i == pressedIndex) canvas.drawRect(cl, y, cl + cw, y + rowH, pressPaint)
                val ty = y + rowH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                if (pos.extraWide) {
                    val contentW = width - 64f
                    val sx = (rowScrollX[pos.row] ?: 0f).coerceIn(0f, (textPaint.measureText(c.text) - contentW).coerceAtLeast(0f))
                    canvas.save(); canvas.clipRect(32f, y, width - 32f, y + rowH)
                    canvas.drawText(c.text, 32f - sx, ty, textPaint)
                    canvas.restore()
                } else {
                    canvas.drawText(c.text, cl + (cw - textPaint.measureText(c.text)) / 2f, ty, textPaint)
                }
                if (!pos.wide && pos.col < cols - 1) {
                    val sx = cl + colW; val cy = y + rowH / 2f; val lh = textPaint.textSize * 0.8f
                    canvas.drawLine(sx, cy - lh / 2f, sx, cy + lh / 2f, sepPaint)
                }
            }
        }

        private fun hitTest(x: Float, y: Float): Int {
            for (i in positions.indices) {
                val pos = positions[i]
                val cl = if (pos.wide) 0f else pos.col * colW
                val cr = if (pos.wide) width.toFloat() else cl + colW
                val ct = pos.row * rowH; val cb = ct + rowH
                if (x >= cl && x < cr && y >= ct && y < cb) return i
            }
            return -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val idx = hitTest(event.x, event.y)
                    pressedIndex = idx; downX = event.x; horizontalDrag = -1; invalidate()
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (horizontalDrag >= 0) {
                        val pos = positions.getOrNull(horizontalDrag) ?: return true
                        val tw = textPaint.measureText(candidates[horizontalDrag].text)
                        val maxSx = (tw - (width - 64f)).coerceAtLeast(0f)
                        val old = rowScrollX[pos.row] ?: 0f
                        val dx = downX - event.x
                        rowScrollX[pos.row] = (old + dx).coerceIn(0f, maxSx.coerceAtLeast(0f))
                        downX = event.x; invalidate()
                    } else if (pressedIndex >= 0) {
                        val pos = positions.getOrNull(pressedIndex)
                        if (pos != null && pos.extraWide && abs(event.x - downX) > 12f) {
                            horizontalDrag = pressedIndex; pressedIndex = -1; invalidate()
                        } else {
                            val n = hitTest(event.x, event.y)
                            if (n != pressedIndex) { pressedIndex = n; invalidate() }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (horizontalDrag < 0) {
                        val i = pressedIndex; pressedIndex = -1; invalidate()
                        if (i in candidates.indices) onCandidateSelected?.invoke(candidates[i])
                    } else {
                        horizontalDrag = -1
                    }
                }
                MotionEvent.ACTION_CANCEL -> { pressedIndex = -1; horizontalDrag = -1; invalidate() }
            }
            return true
        }
    }

    // ── Right: buttons ──
    private val btnPanel = FrameLayout(context).apply { setBackgroundColor(colors.panel.background) }

    private fun makeBtn(text: String, variant: KeyDef.Appearance.Variant, onClick: () -> Unit) = TextKeyView(
        context, colors,
        KeyDef.Appearance.Text(displayText = text, textSize = 16f, percentWidth = 1f, variant = variant, margin = false),
    ).apply { setOnClickListener { onClick() } }

    init {
        setBackgroundColor(colors.panel.background)
        sidePanelPunctuationItems = listOf(".", "?", "!", "@", "/", "-").map { ch ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(displayText = ch, textSize = 15f, percentWidth = 0.5f, margin = false),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(ch))),
            )
        }
        sidePanelKey.updateItems(sidePanelPunctuationItems)
        btnPanel.addView(ImageKeyView(context, colors, prevPageKey(1f).appearance as KeyDef.Appearance.Image).apply { setOnClickListener { onPrevPage?.invoke() } })
        btnPanel.addView(ImageKeyView(context, colors, nextPageKey(1f).appearance as KeyDef.Appearance.Image).apply { setOnClickListener { onNextPage?.invoke() } })
        btnPanel.addView(ImageKeyView(context, colors, backspaceKey().appearance as KeyDef.Appearance.Image).apply { setOnClickListener { onBackspace?.invoke() } })
        btnPanel.addView(ImageKeyView(context, colors, returnKey(1f).appearance as KeyDef.Appearance.Image).apply { setOnClickListener { onReturn?.invoke() } })
        addView(sidePanelKey, lParams(0, matchParent))
        addView(gridCanvas, lParams(0, matchParent))
        addView(btnPanel, lParams(0, matchParent))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        val sw = (tw * 0.15f).toInt(); val rw = (tw * 0.15f).toInt(); val gw = (tw - sw - rw).coerceAtLeast(0)
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        gridCanvas.measure(mES(gw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        btnPanel.measure(mES(rw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        val bh = th / 4
        for (i in 0..3) btnPanel.getChildAt(i).measure(mES(rw, MeasureSpec.EXACTLY), mES(bh, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t; val sw = ((r - l) * 0.15f).toInt(); val rw = ((r - l) * 0.15f).toInt()
        sidePanelKey.layout(0, 0, sw, h)
        gridCanvas.layout(sw, 0, r - l - rw, h)
        btnPanel.layout(r - l - rw, 0, r - l, h)
        val bh = h / 4
        for (i in 0..3) btnPanel.getChildAt(i).layout(0, i * bh, rw, (i + 1) * bh)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    fun refreshTheme(context: Context) { gridCanvas.updateColors(KeyboardColors.resolve(context).panel); invalidate() }

    fun show(list: List<EngineMessage.Candidate>) {
        candidates = list
        gridCanvas.recomputeLayout()
        bringToFront()
        slideDownExpand()
    }

    fun hide() {
        slideUpCollapse {
            candidates = emptyList()
        }
    }

    fun onPossibleCandidatePinYin(data: Array<CandidatePinYin>) {
        if (!subscribePossibleCandidatePinYin) return
        if (data.isEmpty()) {
            sidePanelKey.updateItems(sidePanelPunctuationItems)
        } else {
            sidePanelKey.updateItems(data.map { pinYin ->
                KeyDef(
                    appearance = KeyDef.Appearance.Text(
                        displayText = pinYin.pinYin,
                        textSize = 15f,
                        percentWidth = 0.5f,
                        margin = false,
                    ),
                    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(pinYin.pinYin))),
                )
            })
        }
    }}
