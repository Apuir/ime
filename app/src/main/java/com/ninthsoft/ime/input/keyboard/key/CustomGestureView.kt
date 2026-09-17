package com.ninthsoft.ime.input.keyboard.key

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class SwipeAxis { X, Y }

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    private var lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var touchMovedOutside = false

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled = false
    private var longPressJob: Job? = null

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = Runnable { fireRepeat() }

    private fun fireRepeat() {
        if (isEnabled) {
            repeatStarted = true
            onRepeatListener?.invoke(this@CustomGestureView)
            repeatHandler.postDelayed(repeatRunnable, RepeatInterval)
        }
    }

    var swipeEnabled = false
    var keyboardGestureEnabled = false
    var swipeRepeatEnabled = false
    var swipeThresholdX = 24f
    var swipeThresholdY = 24f

    private var swipeRepeatTriggered = false
    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f
    private var swipeTotalX = 0
    private var swipeTotalY = 0
    private var gestureConsumed = false

    var doubleTapEnabled = false
    private var lastClickTime = 0L
    private var maybeDoubleTap = false

    // ------------------------------------------------------------ 按键气泡

    /**
     * 长按 / 上滑停留时弹出的候选气泡。由 [HasKeyBubble] 提供，默认不启用。
     * 气泡展示后手指不离开屏幕，左右滑动切换高亮项，抬手提交高亮项。
     */
    var bubbleController: HasKeyBubble? = null

    /** 气泡是否只由长按触发（否则上滑停留也触发）。默认 true，即长按。 */
    var bubbleTriggerOnLongPress = true

    /**
     * 上滑模式下的次级输入动作（26 键键帽上的符号 / 数字）。
     *
     * 有它才能区分「快速上滑」和「上滑后停住」：抬手时气泡还没弹出来就把这个动作直接上屏，
     * 弹出来了（说明手指停住了）就改成由气泡的左右划选决定输入什么。
     */
    var bubbleSwipeAltAction: KeyboardAction? = null

    /** 气泡延迟弹出的毫秒数，与长按判定保持一致。 */
    var bubblePressDelay: Long = longPressDelay

    /** 上滑后要停留多久才弹气泡；比长按短一点，因为上滑本身已经是一次明确动作了。 */
    var bubbleSwipeDelay: Long = 200L

    /** 气泡高亮项被提交时回调（抬手时触发一次）。 */
    var onBubbleAction: ((KeyboardAction) -> Unit)? = null

    private var bubbleView: KeyBubblePopup? = null
    private var bubbleTriggered = false
    private var bubbleJob: Job? = null
    private var bubbleSwipeJob: Job? = null

    /** 本次手势是否已经上滑超过阈值（决定抬手时要不要补一次「直接上滑输入」）。 */
    private var bubbleSwipedUp = false

    /** 本次按下的起点 x / y，用于判断上滑与「手指是否还停在键附近」。 */
    private var downX = 0f
    private var downY = 0f


    var onTouchMoveListener: ((Float, Float) -> Unit)? = null
    var onTouchDownListener: ((View) -> Unit)? = null
    var onTouchUpListener: ((View) -> Unit)? = null
    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null
    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard
    private val touchSlop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    /**
     * 「上滑输入」的触发距离系数：阈值 = **当前按键高度 × 该系数**，默认 1.0（整整一个键高）。
     *
     * 由 `KeyboardManager.Keyboard.SwipeUp` 下发；判定与兜底见 [SwipeUpMath]。
     * 用键高而不是固定 dp，是为了让 26 键 / 九键 / 各档键盘高度下的手感一致。
     */
    var swipeUpRatio: Float = SwipeUpMath.DEFAULT_RATIO

    /**
     * 「上滑输入」的方向系数：纵向位移须 ≥ 横向位移 × 该值才算「纵向占主导」。
     *
     * 只看纵向距离会把「横滑时带的纵向漂移」也当成上滑（从 q 斜划到 e 就会出符号）；
     * 加上这一条后，只有方向对才算。同样由 `KeyboardManager.Keyboard.SwipeUp` 下发。
     */
    var swipeUpDirectionTan: Float = SwipeUpMath.DEFAULT_DIRECTION_TAN

    /**
     * Y 轴滑动阈值是否锚定键高。
     *
     * 上滑输入路径（[BaseKeyboard.setupSwipeAltInput]）开，这样它和气泡路径用同一套距离；
     * `KeyDef.Behavior.Swipe` 那种固定挡位路径保持关，继续用写死的 [swipeThresholdY]。
     */
    var swipeThresholdYFollowsKeyHeight = false

    /** 本次手势的按键高度，在 ACTION_DOWN 时缓存 —— 手势期间不会变，缓存可避免判定抖动。 */
    private var touchKeyHeight = 0f

    /** 上滑触发距离的兜底下限：键盘被拖到极小时不至于「一碰就触发」。 */
    private val swipeUpMinDistance: Float
        get() = touchSlop * SwipeUpMath.MIN_DISTANCE_SLOP


    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = true
        isHapticFeedbackEnabled = true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun pointInView(x: Float, y: Float): Boolean {
        return -touchSlop <= x && -touchSlop <= y && x < (width + touchSlop) && y < (height + touchSlop)
    }

    private fun resetState() {
        touchMovedOutside = false
        if (longPressEnabled) {
            longPressTriggered = false
            longPressJob?.cancel()
            longPressJob = null
        }
        if (repeatEnabled) {
            repeatStarted = false
            repeatHandler.removeCallbacks(repeatRunnable)
        }
        if (swipeEnabled) {
            if (swipeRepeatEnabled) {
                swipeRepeatTriggered = false
            }
            swipeXUnconsumed = 0f
            swipeYUnconsumed = 0f
            swipeTotalX = 0
            swipeTotalY = 0
            gestureConsumed = false
        }
        cancelBubbleTimer()
        hideBubble()
        // double tap state should be preserved on touch up
    }

    /** 取消「按住 / 上滑停留一会儿再弹气泡」的两个定时器。 */
    private fun cancelBubbleTimer() {
        bubbleJob?.cancel()
        bubbleJob = null
        bubbleSwipeJob?.cancel()
        bubbleSwipeJob = null
    }

    /**
     * 长按：按住 [bubblePressDelay] 后弹气泡。
     *
     * 定时器一旦启动就要能跑到点：中途手指轻微抖动不应该把它掐掉（那样用户会「按住很久也没反应」），
     * 真正决定弹不弹的是 [showBubble] 里的「手指还在不在键附近」。只有明确飘远了（MOVE 里判断）
     * 才取消。
     */
    private fun startBubbleTimer() {
        if (bubbleJob != null || bubbleTriggered) return
        bubbleJob = lifecycleScope.launch {
            delay(bubblePressDelay)
            bubbleJob = null
            showBubble()
        }
    }

    /** 上滑：手指必须在键附近停住 [bubbleSwipeDelay] 才弹气泡，快速上滑不会弹。 */
    private fun startBubbleSwipeTimer() {
        if (bubbleTriggerOnLongPress || bubbleTriggered || bubbleSwipeJob != null) return
        bubbleSwipeJob = lifecycleScope.launch {
            delay(bubbleSwipeDelay)
            bubbleSwipeJob = null
            showBubble()
        }
    }

    private fun showBubble() {
        if (bubbleView != null || bubbleTriggered) return
        val controller = bubbleController ?: run {
            Log.w(BUBBLE_TAG, "showBubble: bubbleController == null")
            return
        }
        if (!isAttachedToWindow) {
            Log.w(BUBBLE_TAG, "showBubble: view not attached")
            return
        }
        // 这里刻意不再判断「手指离按键多远」：长按和上滑本来就会让手指离开键帽，
        // 26 键的键宽只有 35px 上下，按漂移量做闸门会把气泡几乎全部挡掉。
        // 真正决定「要不要选气泡里的项」的是接下来手指往哪儿滑 —— 滑动即切换高亮。
        val popup = KeyBubblePopup(context)
        popup.show(
            anchor = this,
            items = controller.bubbleItems,
            normalTextColor = controller.bubbleTextColor,
            selectedTextColor = controller.bubbleSelectedTextColor,
            bgColor = controller.bubbleBackgroundColor,
            selectedBgColor = controller.bubbleSelectedBackgroundColor,
            cornerRadius = controller.bubbleCornerRadius,
            strokeColor = controller.bubbleStrokeColor,
            strokeWidth = controller.bubbleStrokeWidth,
        )
        bubbleView = popup
        bubbleTriggered = true
        Log.i(
            BUBBLE_TAG,
            "showBubble: labels=${controller.bubbleItems.map { it.label }} " +
                "showing=${popup.isShowing} left=${popup.contentLeft} width=${popup.contentWidth}",
        )
        // 气泡出现时补一次长按触感，让「弹出来了」有明确反馈。
        InputFeedbacks.hapticFeedback(this, true)
    }

    private fun hideBubble() {
        bubbleView?.dismiss()
        bubbleView = null
        bubbleTriggered = false
    }

    /**
     * 抬手时如果气泡没弹出来，把这次上滑当作「直接输入次级符号 / 数字」处理。
     * 这是「快速上滑 = 输入数字，上滑后停住 = 弹气泡」的分界点。
     */
    private fun fireSwipeAltActionIfNeeded(): Boolean {
        if (!bubbleSwipedUp || bubbleTriggered) return false
        val action = bubbleSwipeAltAction ?: return false
        resetState()
        onBubbleAction?.invoke(action)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                isPressed = true
                downX = x
                downY = y
                touchKeyHeight = height.toFloat()
                bubbleSwipedUp = false
                InputFeedbacks.hapticFeedback(this)
                InputFeedbacks.soundEffect(context, soundEffect)
                onTouchDownListener?.invoke(this)
                dispatchGestureEvent(GestureType.Down, x, y)
                if (longPressEnabled) {
                    longPressJob?.cancel()
                    longPressJob = lifecycleScope.launch {
                        delay(longPressDelay)
                        if (longPressFeedbackEnabled) {
                            InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
                        }
                        longPressTriggered = performLongClick()
                    }
                }
                if (repeatEnabled) {
                    repeatHandler.removeCallbacks(repeatRunnable)
                    repeatHandler.postDelayed(repeatRunnable, longPressDelay)
                }
                if (swipeEnabled) {
                    swipeLastX = x
                    swipeLastY = y
                }
                if (bubbleController != null && bubbleTriggerOnLongPress) {
                    startBubbleTimer()
                }
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                onTouchUpListener?.invoke(this)
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                if (bubbleView != null) {
                    commitBubbleSelection()
                    return true
                }
                // 上滑了但气泡还没弹出来（手指没停住）→ 按「直接上滑输入」处理。
                if (fireSwipeAltActionIfNeeded()) return true
                // 气泡模式却没弹出气泡：别把这次点击吞掉。
                if (bubbleController != null && !bubbleTriggered) {
                    resetState()
                    bubbleFallbackClick()
                    return true
                }
                val shouldPerformClick =
                    !(touchMovedOutside || longPressTriggered || repeatStarted || swipeRepeatTriggered || gestureConsumed)
                resetState()
                if (shouldPerformClick) {
                    if (doubleTapEnabled) {
                        val now = System.currentTimeMillis()
                        if (maybeDoubleTap && now - lastClickTime <= longPressDelay) {
                            maybeDoubleTap = false
                            onDoubleTapListener?.invoke(this)
                        } else {
                            maybeDoubleTap = true
                            performClick()
                        }
                        lastClickTime = now
                    } else {
                        performClick()
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                if (longPressTriggered) {
                    onTouchMoveListener?.invoke(event.rawX, event.rawY)
                }
                if (!touchMovedOutside && !pointInView(x, y)) {
                    touchMovedOutside = true
                    if (longPressEnabled) {
                        longPressJob?.cancel()
                        longPressJob = null
                    }
                    // 气泡的定时器不在这里取消：长按 / 上滑本来就会让手指离开键帽，
                    // 一移动就取消的话，「按住等气泡」几乎不可能成功。
                    if (repeatEnabled) {
                        repeatHandler.removeCallbacks(repeatRunnable)
                    }
                    if (repeatStarted || (!swipeEnabled && !keyboardGestureEnabled)) {
                        isPressed = false
                    }
                }
                // 气泡展示期间：完全由气泡接管，左右滑切项、上下滑不动，抬手才提交。
                if (bubbleView != null) {
                    moveBubbleSelection(x)
                    return true
                }
                // 上滑识别（跟「长按弹不弹气泡」无关，否则快速上滑会被漏掉）：
                //   快速上滑抬手 → ACTION_UP 里直接输入符号 / 数字；
                //   上滑后停住   → 弹气泡（长按那一档已经弹出来的话就直接用它的结果）。
                if (bubbleController != null) {
                    // 触发距离 = 当前键高 × swipeUpRatio（默认整整一个键高），判定收在 SwipeUpMath 里。
                    // dy 传 downY - y（上滑为正），与 MotionEvent 的坐标方向相反，别写反。
                    val swipedUpNow = SwipeUpMath.isSwipeUp(
                        dx = x - downX,
                        dy = downY - y,
                        keyHeight = touchKeyHeight,
                        ratio = swipeUpRatio,
                        directionTan = swipeUpDirectionTan,
                        minDistance = swipeUpMinDistance,
                    )
                    if (swipedUpNow && !bubbleSwipedUp) {
                        bubbleSwipedUp = true
                        startBubbleSwipeTimer()
                    } else if (!swipedUpNow && bubbleSwipedUp) {
                        // 滑回阈值内：这次不算上滑输入，交回长按 / 点击那条路。
                        bubbleSwipedUp = false
                        cancelBubbleTimer()
                    }
                    // 气泡路径接管了上滑：不再走下面那套上滑手势，否则抬手会被重复触发一次。
                    if (bubbleSwipedUp) return true
                }
                if ((!swipeEnabled && !keyboardGestureEnabled) || (longPressTriggered && !keyboardGestureEnabled && !swipeEnabled) || repeatStarted) return true
                val countX = consumeSwipe(x, SwipeAxis.X)
                val countY = consumeSwipe(y, SwipeAxis.Y)
                dispatchGestureEvent(GestureType.Move, x, y, countX, countY)
                swipeLastX = x
                swipeLastY = y
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                onTouchUpListener?.invoke(this)
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                resetState()
                // reset double tap state on cancel
                if (doubleTapEnabled) {
                    maybeDoubleTap = false
                    lastClickTime = 0
                }
                return true
            }
        }
        return true
    }

    /**
     * 把当前 x 坐标换算成气泡里的高亮项。
     *
     * 高亮项按气泡的**屏幕坐标**取最近一项，而不是按手指相对按键的位移，
     * 这样手指滑到气泡哪一项，高亮的就是哪一项（主流输入法的手感）；
     * 滑出气泡左右边界时夹到首尾两项，避免「划出去就没反应」。
     */
    private fun moveBubbleSelection(x: Float) {
        val popup = bubbleView ?: return
        if (popup.itemCount == 0) return
        val step = popup.itemStep
        if (step <= 0f) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val screenX = location[0] + x
        val rawIndex = ((screenX - popup.contentLeft) / step).toInt()
        val target = when {
            screenX < popup.contentLeft -> 0
            screenX >= popup.contentLeft + popup.contentWidth -> popup.itemCount - 1
            else -> rawIndex.coerceIn(0, popup.itemCount - 1)
        }
        if (target == popup.selectedIndex) return
        val controller = bubbleController ?: return
        if (popup.selectIndex(
                index = target,
                normalTextColor = controller.bubbleTextColor,
                selectedTextColor = controller.bubbleSelectedTextColor,
                selectedBgColor = controller.bubbleSelectedBackgroundColor,
            )
        ) {
            InputFeedbacks.hapticFeedback(this)
        }
    }

    /** 抬手：提交气泡里高亮的那一项；顺带清掉可能还挂着的长按 / 上滑定时器。 */
    private fun commitBubbleSelection() {
        val popup = bubbleView
        val controller = bubbleController
        val action = controller?.bubbleItems?.getOrNull(popup?.selectedIndex ?: 0)?.action
        hideBubble()
        resetState()
        if (action != null) {
            onBubbleAction?.invoke(action)
        }
    }

    /**
     * 气泡路径下「原本会被吞掉」的那次点击。
     *
     * 气泡模式会摘掉按键自己的长按监听（长按改成弹气泡），所以一旦气泡没能显示出来，
     * 用户按下去就会「什么都不发生」。这时补一次普通点击，结果等同于轻点这个键
     * （26 键打字、九键进候选），不会丢输入。
     */
    private fun bubbleFallbackClick() {
        Log.w(BUBBLE_TAG, "bubble not shown -> fallback click on $this")
        performClick()
    }

    private fun dispatchGestureEvent(
        type: GestureType, x: Float, y: Float, countX: Int = 0, countY: Int = 0
    ) {
        val event = Event(type, gestureConsumed, x, y, countX, countY, swipeTotalX, swipeTotalY)
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed && !gestureConsumed) {
            gestureConsumed = true
        }
    }

    private fun consumeSwipe(current: Float, axis: SwipeAxis): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = swipeThresholdX
            }

            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                // 上滑输入路径锚定键高（与气泡路径同一套距离）；其余路径仍用写死的挡位。
                threshold = if (swipeThresholdYFollowsKeyHeight) {
                    SwipeUpMath.threshold(touchKeyHeight, swipeUpRatio, swipeUpMinDistance)
                } else {
                    swipeThresholdY
                }
            }
        }
        val remains: Float = unconsumed % threshold
        val count: Int = (unconsumed / threshold).toInt()
        if (count != 0) {
            if (swipeRepeatEnabled && !swipeRepeatTriggered) {
                swipeRepeatTriggered = true
            }
            if (longPressEnabled && !longPressTriggered) {
                longPressJob?.cancel()
                longPressJob = null
            }
            if (repeatEnabled && !repeatStarted) {
                repeatHandler.removeCallbacks(repeatRunnable)
            }
        }
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }

            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDetachedFromWindow() {
        lifecycleScope.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** 气泡诊断日志的 tag；日志在应用内「设置 → 运行日志」里能直接看到。 */
        const val BUBBLE_TAG = "ImeBubble"

        const val longPressDelay = 250L
        const val RepeatInterval = 100L
    }
}
