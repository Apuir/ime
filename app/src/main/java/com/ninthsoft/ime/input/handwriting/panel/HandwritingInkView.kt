package com.ninthsoft.ime.input.handwriting.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.ninthsoft.ime.input.handwriting.HwStroke
import com.ninthsoft.ime.input.handwriting.StrokeMath
import splitties.dimensions.dp

/**
 * 手写面板的书写区：只做两件事 —— 采点、画墨迹。
 *
 * 刻意保持「哑」，三个边界都别越：
 * - **不做识别**。什么时候识别是 [HandwritingPanelView] 的事（停手计时在那里），
 *   这里只把「一笔开始 / 抬笔」告诉宿主。
 * - **不做任何坐标缩放**。坐标就是本 View 的本地像素，左上为 (0, 0)；
 *   归一化由引擎内部完成（ochwpro 自带逐轴包围盒归一化），外面再缩一次是双重归一化，
 *   而且会让 [writingAreaWidth] / [writingAreaHeight] 与坐标不再同单位 —— 那两个值
 *   是要原样交给引擎当「书写区实际尺寸」用的。
 * - **不画边框、不画底色**。面板本身就是同一块底色，多画一层会在换主题时露馅。
 */
class HandwritingInkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * 书写事件的出口。宿主靠这两个回调驱动「停手 0.7s 就识别」的时序，
     * 所以不在 View 里自己起计时器：那是面板的职责，这里只报告事实。
     */
    interface Listener {
        /** 新的一笔落下。宿主据此取消待识别 —— 用户还在写，上一拍的停手判定作废。 */
        fun onStrokeStarted()

        /** 抬笔（含 [MotionEvent.ACTION_CANCEL]）。宿主据此起停手计时。 */
        fun onStrokeFinished()
    }

    var listener: Listener? = null

    /** 是否已有落定的笔迹。宿主用它在识别前挡掉「空笔迹也去跑一次」的情况。 */
    val hasInk: Boolean get() = strokes.isNotEmpty()

    /** 书写区宽高（px）。识别时原样传给引擎，必须与笔迹坐标同单位，所以不做任何换算。 */
    val writingAreaWidth: Int get() = width
    val writingAreaHeight: Int get() = height

    /** 已落定的笔。正在写的那一笔还留在 [pendingPoints] 缓冲里，抬笔时才定型进来。 */
    private val strokes = ArrayList<HwStroke>()

    /**
     * 当前一笔的采点缓冲。
     *
     * 用「可增长数组」而不是 `ArrayList<PointF>`：一笔常有两三百个点，
     * 逐点装箱在连续书写时会实打实压到 GC 上；而且识别要的本来就是扁平数组，
     * 定型时 `copyOf` 一次即可，不必再逐点转换。
     */
    private var pendingPoints = FloatArray(INITIAL_POINT_CAPACITY * 2)
    private var pendingTimes = LongArray(INITIAL_POINT_CAPACITY)
    private var pendingCount = 0

    /** 上一个**被保留**的点，给 [StrokeMath.shouldKeepPoint] 做间距判定。 */
    private var lastX = 0f
    private var lastY = 0f

    /** 中心提示语；null 表示用默认的「在此手写」。 */
    private var hint: String? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(STROKE_WIDTH_DP).toFloat()
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // 提示语是给人看的文字，用 sp 而不是 dp，跟随系统字号
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            HINT_TEXT_SIZE_SP,
            resources.displayMetrics,
        )
    }

    /** 复用同一个 Path：每次重画都会 reset，没有跨帧状态需要保留。 */
    private val path = Path()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 正常情况下这里 pendingCount 一定是 0；不为 0 说明上一笔的 UP/CANCEL 丢了
                // （事件被系统抢走等）。把它落定成一笔而不是直接丢掉，免得墨迹凭空少一截。
                finishStroke()
                appendPoint(event.x, event.y, event.eventTime)
                listener?.onStrokeStarted()
            }

            MotionEvent.ACTION_MOVE -> {
                // **必须先把被合批的中间点取回来**：一次 ACTION_MOVE 可能跨过几十像素，
                // 只读 event.x/y 会让采到的折线被拉成直线段，笔画形状直接被抹平。
                // 历史点的时间要用 getHistoricalEventTime，和坐标一一对应。
                for (i in 0 until event.historySize) {
                    appendPoint(
                        event.getHistoricalX(i),
                        event.getHistoricalY(i),
                        event.getHistoricalEventTime(i),
                    )
                }
                appendPoint(event.x, event.y, event.eventTime)
            }

            MotionEvent.ACTION_UP -> {
                // 最后一小段位移常常只在 UP 里报出来，补上；重复点会被去重吃掉
                appendPoint(event.x, event.y, event.eventTime)
                finishStroke()
                performClick()
            }

            // 取消也当抬笔：已经画出来的墨迹保留，停手计时的语义和抬笔一致
            MotionEvent.ACTION_CANCEL -> finishStroke()

            // 主手指先抬起（还有其他手指在屏幕上）时，pointer 0 会换成别的手指，
            // 后续 MOVE 就不再是这一笔的轨迹了 —— 当成抬笔，避免笔画接出一条乱线。
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == 0) finishStroke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (strokes.isEmpty() && pendingCount == 0) {
            drawHint(canvas)
            return
        }
        for (stroke in strokes) {
            drawPolyline(canvas, stroke.points, stroke.pointCount)
        }
        // 正在写的那一笔也要画，否则手指抬起前画布一直是空的（看起来像没写上）
        drawPolyline(canvas, pendingPoints, pendingCount)
    }

    /** 墨迹色与提示色。都由面板在 [HandwritingPanelView.refreshColors] 里按主题给。 */
    fun setColors(strokeColor: Int, hintColor: Int) {
        strokePaint.color = strokeColor
        hintPaint.color = hintColor
        invalidate()
    }

    /**
     * 换掉中心提示语（识别失败 / 引擎不可用）。传 null 恢复默认的「在此手写」。
     *
     * 与 [clearAll] 分开：提示语是「这一轮为什么没出字」的说明，
     * 不该因为清了一次画布就消失。
     */
    fun setHint(text: String?) {
        if (hint == text) return
        hint = text
        invalidate()
    }

    /** 清掉全部笔迹（含正在写的那一笔）。 */
    fun clearAll() {
        strokes.clear()
        pendingCount = 0
        path.rewind()
        invalidate()
    }

    /**
     * 笔迹快照，交给引擎识别。
     *
     * 返回一份浅拷贝：[HwStroke] 本身不可变，但外层列表必须复制 —— 引擎在后台线程读它的
     * 同时，用户可能已经又开始写并触发 [clearAll]，共享同一个可变列表就是并发问题。
     */
    fun snapshotStrokes(): List<HwStroke> = ArrayList(strokes)

    // ------------------------------------------------------------------
    // 采点
    // ------------------------------------------------------------------

    /**
     * 采一个点。
     *
     * 去重交给 [StrokeMath.shouldKeepPoint]：它会给一笔的**第一个点**无条件放行
     * （否则起点会被间距判定吃掉，笔画缺头），其余按最小间距过滤抖动噪声。
     */
    private fun appendPoint(x: Float, y: Float, time: Long) {
        if (!StrokeMath.shouldKeepPoint(pendingCount > 0, lastX, lastY, x, y)) return
        ensureCapacity(pendingCount + 1)
        pendingPoints[pendingCount * 2] = x
        pendingPoints[pendingCount * 2 + 1] = y
        pendingTimes[pendingCount] = time
        pendingCount++
        lastX = x
        lastY = y
        invalidate()
    }

    /** 缓冲按 2 倍增长，摊还下来每点 O(1)，避免一笔里反复重新分配。 */
    private fun ensureCapacity(points: Int) {
        if (pendingTimes.size >= points) return
        var capacity = pendingTimes.size
        while (capacity < points) capacity *= 2
        pendingTimes = pendingTimes.copyOf(capacity)
        pendingPoints = pendingPoints.copyOf(capacity * 2)
    }

    /**
     * 抬笔：把缓冲里的点定型成一笔存下来，然后通知宿主。
     *
     * 一个点都没有时什么都不做 —— 既不留空笔，也不发 [Listener.onStrokeFinished]
     * （宿主会为一个不存在的字起停手计时）。
     */
    private fun finishStroke() {
        if (pendingCount == 0) return
        strokes += HwStroke(
            points = pendingPoints.copyOf(pendingCount * 2),
            times = pendingTimes.copyOf(pendingCount),
        )
        pendingCount = 0
        invalidate()
        listener?.onStrokeFinished()
    }

    // ------------------------------------------------------------------
    // 画
    // ------------------------------------------------------------------

    /**
     * 用「相邻采样点的中点」做二次贝塞尔端点、原始点做控制点，把折线画顺。
     *
     * 直接 `lineTo` 连原始点的话，采样噪声和手抖会让线条全是折角；中点法是最省的一种平滑
     * ——不引入额外拟合、也不会把笔画的大形改掉，只在每两个采样点之间补一段弧。
     */
    private fun drawPolyline(canvas: Canvas, points: FloatArray, pointCount: Int) {
        if (pointCount <= 0) return
        if (pointCount == 1) {
            // 单点（点一下没动）：STROKE 的 Path 画不出东西，补一个直径等于线宽的圆点
            canvas.drawCircle(points[0], points[1], strokePaint.strokeWidth / 2f, strokePaint)
            return
        }
        path.rewind()
        path.moveTo(points[0], points[1])
        for (i in 1 until pointCount - 1) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            val nextX = points[(i + 1) * 2]
            val nextY = points[(i + 1) * 2 + 1]
            path.quadTo(x, y, (x + nextX) / 2f, (y + nextY) / 2f)
        }
        path.lineTo(points[(pointCount - 1) * 2], points[(pointCount - 1) * 2 + 1])
        canvas.drawPath(path, strokePaint)
    }

    private fun drawHint(canvas: Canvas) {
        val metrics = hintPaint.fontMetrics
        // 用 fontMetrics 而不是 ascent/descent 直接相加：中文的基线位置靠它才算得准
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(hint ?: DEFAULT_HINT, width / 2f, baseline, hintPaint)
    }

    private companion object {
        /** 墨迹线宽（dp）。4dp 大致对应手指书写的观感：再细在深色主题下发虚。 */
        const val STROKE_WIDTH_DP = 4

        const val HINT_TEXT_SIZE_SP = 15f

        /** 一笔的初始点数容量。多数一笔不到 128 个点，省掉绝大多数扩容。 */
        const val INITIAL_POINT_CAPACITY = 128

        const val DEFAULT_HINT = "在此手写"
    }
}
