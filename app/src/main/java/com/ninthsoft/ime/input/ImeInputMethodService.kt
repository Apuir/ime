package com.ninthsoft.ime.input

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.InputConnectionUtil
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.input.keyboard.window.KeyboardWindow
import com.ninthsoft.ime.input.keyboard.window.KeyboardStateManager
import com.ninthsoft.ime.input.panel.component.TextEditView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ImeInputMethodService : InputMethodService() {
    internal val engine: IEngine? get() = EngineFactory.current()
    internal var keyboardWindow: KeyboardWindow? = null
    internal lateinit var keyActionListener: KeyActionListener

    /** 桥接模式：添加常用语时为真，所有提交/删除/语音输出写入 [virtualInputConnection] 而非真实编辑器。 */
    var phraseAddBridgeActive = false
    val virtualInputConnection = ImeInputConnection(this)

    /** 输入框实时上屏（预览）控制器。 */
    val livePreview = LivePreviewController(this)

    fun activeInputConnection(): android.view.inputmethod.InputConnection? =
        if (phraseAddBridgeActive) virtualInputConnection else currentInputConnection
    var scope: CoroutineScope? = null
    var messageObserveJob: Job? = null
    private var showingDialog: android.app.Dialog? = null
    private var lastSelectionStart = 0
    private var lastSelectionEnd = 0

    /** 复用缓冲区，避免 onComputeInsets 每次分配（该方法在一次布局中可能被多次调用）。 */
    private val insetsRegion = Rect()

    /** 进入悬浮模式前的 IME 窗口背景，离开时还原。 */
    private var originalImeWindowBackground: Drawable? = null
    private var imeWindowBackgroundCleared = false
    private val themePrefs: SharedPreferences by lazy {
        getSharedPreferences(KeyboardManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val schemaPrefs: SharedPreferences by lazy {
        getSharedPreferences(SchemaManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val candidatePrefs: SharedPreferences by lazy {
        getSharedPreferences(CandidateManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 上屏模式变化后立即刷新当前预览，无需重新输入。
        if (key == CandidateManager.KEY_PREVIEW_MODE) {
            livePreview.onPreviewModeChanged()
        }
        // 悬浮开关可能是在键盘收起时改的，这里同步一次窗口背景，避免窗口全屏却不透明。
        if (key == KeyboardManager.Keyboard.Floating.KEY_ENABLED) {
            applyFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(this))
        }
        keyboardWindow?.onConfigChanged(key.orEmpty())
    }

    override fun onCreate() {
        super.onCreate()
        virtualInputConnection.addOnChangeListener {
            if (phraseAddBridgeActive) syncActiveInputState()
        }
        //service scope & message subscribe
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                val app = applicationContext as? ImeApplication
                // 监听应用状态，当引擎启动的时候时开始绑定消息
                app?.state?.collectLatest { state ->
                    if (state == ImeApplication.AppState.EngineStarting) {
                        messageObserveJob = engine?.observeMessages(this) { message ->
                            keyboardWindow?.handleEngineMessage(message)
                        }
                    }
                }
            }
        }
        // KeyActionListener & prefrece change listenter
        keyActionListener = KeyActionListener(service = this)
        themePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        candidatePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏会重建窗口尺寸：整屏手写必须先退出整屏形态（触摸区域/窗口背景都是按旧方向算的），
        // 否则会出现「可写区域还在上一方向的整屏、顶栏却按新方向排」的错位。
        // 偏好在退出的路径里刻意保留（applyHandwritingFullScreen(false) 不写偏好），
        // 所以下次进手写仍是用户上次选的范围。
        keyboardWindow?.view?.exitHandwritingFullScreen()
        // 横竖屏切换时框架会重建输入视图，这里同步一次悬浮模式，保证窗口尺寸立即跟随方向变化。
        applyFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(this))
        keyboardWindow?.view?.refreshColors()
    }

    override fun onCreateInputView(): View {
        val floating = KeyboardManager.Keyboard.Floating.shouldUseFloating(this)
        keyboardWindow?.let {
            applyFloatingMode(floating)
            // InputMethodService adds the returned root to its own container.
            // Detach it first when the same window instance is requested again.
            (it.view.parent as? ViewGroup)?.removeView(it.view)
            return it.view
        }
        val window = KeyboardWindow(
            service = this,
            keyboardStateManager = KeyboardStateManager,
            panelActionListener = PanelActionListener(this),
        )
        keyboardWindow = window
        window.setKeyActionListener(keyActionListener)
        applyFloatingMode(floating)
        // KeyboardStateManager 是进程级单例，其键盤注册表可能残留上一实例（旧配色）的键盘；
        // 新建窗口（销毁重建路径）时重建一次，让键盘用本次实例解析出的新配色生成。
        KeyboardStateManager.rebuild()
        return window.view
    }

    /**
     * 切换悬浮模式：除了通知键盘视图，还要同步 IME 窗口本身的状态。
     */
    private fun applyFloatingMode(floating: Boolean) {
        keyboardWindow?.setFloatingMode(floating)
        syncImeWindow(floating)
    }

    /**
     * 把 IME 窗口的**背景**与**尺寸**同步到键盘视图当前的形态上。
     *
     * 会改变这两件事的入口全部走这里，窗口状态因此只有一个更新点：
     * 输入视图创建 / 窗口重新显示 / 转屏 / 悬浮开关变化（[applyFloatingMode]），
     * 以及键盘视图进出「真·整屏手写」（[KeyboardWindowView.isFullScreenHandwriting]）。
     *
     * 两个来源：
     * - **横屏悬浮卡片**：卡片本身就画在一个铺满屏幕的透明窗口里，窗口背景必须透明；
     * - **真·整屏手写**：窗口要铺满整屏、背景透明、可触摸区域给整窗，同时窗口**高度**也要
     *   显式设成整屏（见 [applyWindowSize]）。
     *
     * 窗口背景不清成透明的话，铺满屏幕的窗口底会把下层应用整个盖住 ——
     * 整屏手写就变成「整屏黑/白板」，悬浮卡片则会把应用挡掉。
     */
    internal fun syncImeWindow(
        floating: Boolean = KeyboardManager.Keyboard.Floating.shouldUseFloating(this),
    ) {
        val win = window?.window ?: return
        val overlay = keyboardWindow?.view?.isFullScreenHandwriting == true
        applyWindowBackground(win, transparent = floating || overlay)
        applyWindowSize(win, fullScreen = overlay)
    }

    /** 按「是否需要整窗透明」刷新 IME 窗口背景；离开透明状态时还原成原来的 decor 背景。 */
    private fun applyWindowBackground(win: Window, transparent: Boolean) {
        if (transparent) {
            if (!imeWindowBackgroundCleared) {
                originalImeWindowBackground = win.decorView.background
                imeWindowBackgroundCleared = true
            }
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else if (imeWindowBackgroundCleared) {
            win.setBackgroundDrawable(originalImeWindowBackground)
            imeWindowBackgroundCleared = false
        }
    }

    /**
     * 整屏手写时把 IME 窗口高度设成 `MATCH_PARENT`，让窗口**直接**铺满可用区域。
     *
     * 为什么不能沿用默认的 `WRAP_CONTENT`（让内容把窗口撑高）：
     * IME 窗口默认是 `MATCH_PARENT × WRAP_CONTENT`（框架在 `InputMethodService.onCreate()`
     * 里设的，API 30 起还带 `setFitInsetsTypes(statusBars | navigationBars)`、
     * `setFitInsetsSides(all & ~bottom)` —— 窗口避开状态栏，但允许铺到导航栏后面）。
     * 同时 `ViewRootImpl` 对 `TYPE_INPUT_METHOD` 有专门分支（`shouldUseDisplaySize()`）：
     * 它给根视图的高度 MeasureSpec 用的是**屏幕真实高度**（AT_MOST），而不是窗口当前高度。
     * 于是「内容愿意多高」与「窗口现在多高」是两件事：整屏要经过
     * 「测量 → relayout → 窗口管理器按测量值改窗口 → 再测一次」这一圈才收敛，
     * 中间任何一步没跟上，底部三条就会被排在窗口下沿之外 —— 看起来就是「键盘整块消失」。
     *
     * `MATCH_PARENT` 是平台自己的全屏窗口策略（系统默认的 [onConfigureWindow] 在 fullscreen
     * 提取模式下用的就是它）：窗口一次到位，根视图随后拿到的是 `EXACTLY(窗口高度)`，
     * 测量与布局不再依赖收敛过程；万一某次测量先于窗口放大发生，退化的结果也只是
     * 「三条挤在窗口底部」而不会排到屏幕外。
     *
     * 退出时必须还原成 `WRAP_CONTENT`：否则窗口会一直是整屏高度，普通键盘会被排在窗口
     * 顶部，而且 [onComputeInsets] 上报给应用的底衬也不再是键盘高度。
     *
     * 这一段在 API 31~36 上没有版本差异：`Window.setLayout` 是 API 1 就有的公开接口，
     * `TYPE_INPUT_METHOD` 的「按屏幕真实高度测量」与 IME 窗口的 fit insets 从 API 30 起
     * 就是现在这个样子（31 与 36 的 `InputMethodService.onCreate()` 两处源码一致）。
     */
    private fun applyWindowSize(win: Window, fullScreen: Boolean) {
        win.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullScreen) {
                WindowManager.LayoutParams.MATCH_PARENT
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            },
        )
    }

    /**
     * 系统在「全屏提取模式 / 仅候选模式」变化时会重设窗口尺寸，默认实现是
     * 「fullscreen → MATCH_PARENT，否则 WRAP_CONTENT」。它会在整屏手写期间把窗口改回
     * WRAP_CONTENT，所以这里按同一个策略覆写一次，补上「整屏手写 = 全屏窗口」这条：
     * 窗口尺寸只有一个来源（本方法 + [applyWindowSize]），不会被系统回调改回去。
     */
    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        val overlay = keyboardWindow?.view?.isFullScreenHandwriting == true
        applyWindowSize(win, fullScreen = isFullscreen || overlay)
    }

    /**
     * 悬浮键盘 / 整屏手写模式下 IME 窗口占满整个屏幕但背景透明。
     *
     * 这里把 `contentTopInsets` / `visibleTopInsets` 设为窗口底部，使 IME 上报给下层应用的
     * 内容边衬为零（应用不会被顶起），同时通过 `touchableRegion` 声明哪些区域接收触摸。
     * 悬浮卡片时只报卡片矩形，卡片之外的手势会穿透到下层应用；整屏手写时键盘视图会把
     * 可触摸区域报成整窗（见 `KeyboardWindowView.floatingTouchableRegion`），
     * 于是「整个屏幕都能写」。这正是主流输入法「悬浮键盘 / 全屏手写」的实现方式。
     *
     * 注意「报整窗」只有在**窗口本身就是整屏**时才有意义：窗口尺寸由 [syncImeWindow] /
     * [applyWindowSize] 负责（整屏手写会把窗口高度设成 MATCH_PARENT），
     * 两边必须同时成立，缺一个都会变成「触摸区域在屏幕上、窗口却只有键盘那么高」。
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        val view = keyboardWindow?.view
        if (outInsets == null || view == null || !view.usesOverlayLayout) {
            super.onComputeInsets(outInsets)
            return
        }
        // 整屏手写要把应用顶到底部三条之上（输入框贴在键盘上沿）；
        // 悬浮卡片报窗口底，等于告诉框架「键盘没占地方」，应用不被顶起。
        val contentBottom = if (view.isFullScreenHandwriting) {
            view.fullScreenHandwritingContentBottomPx()
        } else {
            view.contentBottomInWindowPx()
        }
        view.floatingTouchableRegion(insetsRegion)
        outInsets.contentTopInsets = contentBottom
        outInsets.visibleTopInsets = contentBottom
        outInsets.touchableRegion.set(insetsRegion)
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // 新的输入会话：正常路径上组合态已被上一次 onFinishInputView 收尾（保留了那个字），
        // 这里兜底再收一次（只在确实是我们设过组合时才动，不会误伤应用自己的组合）。
        // 用「收尾」而不是直接清标记：万一这次是同一连接上的 restart、上一次没收尾，
        // 清标记会让这个字在下一笔时被 setComposingText 整段替换掉，那就丢了。
        finalizeHandwritingComposing()
        keyboardWindow?.onStartInputView(info, restarting)
        engine?.onStartInputView(currentInputConnection)
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
        // 手写组合态要先收尾：面板隐藏/键盘销毁那条路径（onDetachedFromWindow）不一定
        // 还拿得到活动的 InputConnection，在这里做才能保证那个字一定留在输入框里。
        finalizeHandwritingComposing()
        // 收起键盘前按设置决定是否保留已上屏内容，再重置引擎组合。
        livePreview.finalizeForKeyboardSwitch()
        engine?.resetComposition()
        keyboardWindow?.onFinishInputView(finishingInput)
        engine?.onFinishInputView()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        // 窗口隐藏（关闭输入法）同样按「保留这个字」收尾，与收起键盘一致。
        finalizeHandwritingComposing()
        keyboardWindow?.onWindowHidden()
        ClipboardManager.stopMonitoring(this)
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 每次显示都对齐一次悬浮模式：键盘可能是收起期间被改了设置，或输入视图被框架复用。
        applyFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(this))
        keyboardWindow?.onWindowShown()
        ClipboardManager.startMonitoring(this)
        if (messageObserveJob == null) {
            messageObserveJob = scope?.let { scope ->
                engine?.observeMessages(scope) {
                    keyboardWindow?.handleEngineMessage(it)
                }
            }
        }
    }

    override fun onDestroy() {
        // 输入法进程里的这条服务实例要销毁了：如果还有未上屏的手写组合，先把它收尾
        // （正常路径上 onFinishInputView / onWindowHidden 已经做过，这里是兜底）。
        finalizeHandwritingComposing()
        messageObserveJob?.cancel()
        scope?.cancel()
        scope = null
        keyboardWindow = null

        ClipboardManager.stopMonitoring(this)
        themePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        candidatePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    fun showDialog(dialog: android.app.Dialog) {
        showingDialog?.dismiss()
        val tokenView = keyboardWindow?.view ?: return
        dialog.window?.apply {
            attributes.token = tokenView.windowToken
            attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            setDimAmount(0.5f)
        }
        dialog.setOnDismissListener { showingDialog = null }
        dialog.show()
        showingDialog = dialog
    }

    internal fun handleTextEditingAction(action: TextEditView.Action) {
        val ic = activeInputConnection()
        when (action) {
            is TextEditView.Action.MoveLeft -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_LEFT, action.shift
            )

            is TextEditView.Action.MoveRight -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_RIGHT, action.shift
            )

            is TextEditView.Action.MoveUp -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_UP, action.shift
            )

            is TextEditView.Action.MoveDown -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_DOWN, action.shift
            )

            is TextEditView.Action.MoveHome -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_MOVE_HOME, action.shift
            )

            is TextEditView.Action.MoveEnd -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_MOVE_END, action.shift
            )

            TextEditView.Action.SelectToggle -> { /* local state toggle handled in view */
            }

            TextEditView.Action.CancelSelection -> {
                if (ic != null) {
                    val selection = if (phraseAddBridgeActive) virtualInputConnection.selection
                    else lastSelectionStart to lastSelectionEnd
                    if (selection.first != selection.second) {
                        ic.setSelection(selection.second, selection.second)
                    }
                }
            }

            TextEditView.Action.SelectAll -> ic?.performContextMenuAction(android.R.id.selectAll)
            TextEditView.Action.Cut -> ic?.performContextMenuAction(android.R.id.cut)
            TextEditView.Action.Copy -> ic?.performContextMenuAction(android.R.id.copy)
            TextEditView.Action.Paste -> ic?.performContextMenuAction(android.R.id.paste)

            TextEditView.Action.Backspace -> engine?.processKey(
                this, com.ninthsoft.ime.engine.event.KeyEvent.CodeEvent(
                    KeyEvent.KEYCODE_DEL, com.ninthsoft.ime.engine.event.KeyModifiers.Empty
                )
            )
        }
    }

    private fun sendCombinationKeyEvent(
        ic: android.view.inputmethod.InputConnection?, keyCode: Int, shift: Boolean,
    ) {
        InputConnectionUtil.sendCombinationKeyEvent(ic, keyCode, shift = shift)
    }

    /**
     * 回车键在「没有拼音组合」时的处理：按输入框声明的语义执行。
     *
     * 声明了 editor action（搜索 / 发送 / 前往 / 完成…）就执行它；没声明就发一个真正的
     * 回车按键事件交给应用自己决定换行还是提交。
     *
     * **不要用 `commitText("\n")` 代替换行** —— 单行输入框会把换行显示成空格，
     * 用户看到的就是「按回车只会多一个空格」，而搜索框根本收不到搜索动作。
     */
    internal fun submitEditorActionOrEnter() {
        val action = declaredEditorAction()
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            val ic = prepareForCommit() ?: return
            InputConnectionUtil.sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER)
        } else {
            submitEditorAction(action)
        }
    }

    /** 执行指定的 editor action（输入框声明的，或工具栏按钮显式指定的）。 */
    internal fun submitEditorAction(action: Int) {
        val ic = prepareForCommit() ?: return
        ic.performEditorAction(action)
    }

    /** 当前输入框声明的 editor action；未声明时返回 [EditorInfo.IME_ACTION_NONE]。 */
    private fun declaredEditorAction(): Int =
        (keyboardWindow?.currentEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION

    /**
     * 提交类动作（回车 / editor action）前的收尾：结束输入框里的预览 composing，
     * 并清空引擎组合 —— 否则动作执行完可能又冒出一段未完成的输入。
     */
    private fun prepareForCommit(): android.view.inputmethod.InputConnection? {
        val ic = activeInputConnection() ?: return null
        livePreview.finalizeForKeyboardSwitch()
        engine?.resetComposition()
        return ic
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        lastSelectionStart = newSelStart
        lastSelectionEnd = newSelEnd
        keyboardWindow?.onSelectionUpdate(newSelStart, newSelEnd)
        endCompositionIfSelectionMovedAway(newSelStart, newSelEnd)
        notifyInputChanged()
    }

    /**
     * 用户把光标点到组合区之外时，结束这次输入。
     *
     * 我们往输入框写预览本身也会触发 `onUpdateSelection`，所以只能比「光标还在不在
     * [LivePreviewController.previewRange] 里」：在里面就是我们自己造成的（连打时系统回调
     * 晚一拍也仍在区间内），跑到区间外才是用户把光标移走了。
     *
     * 收尾策略是「直接输入完毕」——输入框里显示的候选词 / 拼音结束 composing 后原样留下，
     * 再清空引擎组合、收起候选面板。否则用户点回原处继续打字时，又会接在旧组合后面输入。
     */
    private fun endCompositionIfSelectionMovedAway(newSelStart: Int, newSelEnd: Int) {
        if (phraseAddBridgeActive) return
        if (!KeyboardStateManager.isComposingNow) return
        val range = livePreview.previewRange ?: return
        if (!SelectionMath.isCursorAwayFromComposition(
                newSelStart, newSelEnd, range.first, range.last,
            )
        ) {
            return
        }

        livePreview.commitPreview()
        engine?.resetComposition()
        keyboardWindow?.setCandidates(emptyList())
    }

    /**
     * 手写组合态当前内容；非空表示输入框里有一段手写未上屏文本。
     *
     * 手写不复用 [LivePreviewController]：那条路是 Rime 的 preedit，读的是「上屏模式」偏好，
     * 默认模式下什么都不写，而手写组合态是**无条件**要显示在输入框里的。但两者走的是
     * 同一条底层通道（`InputConnection.setComposingText`），所以行为（下划线、被 commitText
     * 替换、被退格整体删掉）与拼音完全一致。
     */
    private var handwritingComposing: String = ""

    /**
     * 手写候选进入组合态 / 用户换字：把 [text] 作为未上屏文本写进输入框。
     *
     * 由 [keyboardWindow] 转来（面板不认识 `InputConnection`）。用 `setComposingText`
     * 而不是 `commitText`：换字时它会**替换**现有组合区，不需要任何退格动作。
     */
    internal fun setHandwritingComposing(text: String) {
        if (text.isEmpty()) {
            discardHandwritingComposing()
            return
        }
        val ic = activeInputConnection() ?: return
        ic.setComposingText(text, 1)
        handwritingComposing = text
    }

    /**
     * 把当前组合文本收尾成正式文本：结束 composing 但**不删除**内容，
     * 输入框里显示的那个字就此成为普通文本（用户说的「保留这个字」）。
     *
     * 落笔写下一个字 / 切键盘 / 收起键盘 / 空格回车标点都会走到这里。
     */
    internal fun finalizeHandwritingComposing() {
        if (handwritingComposing.isEmpty()) return
        handwritingComposing = ""
        activeInputConnection()?.finishComposingText()
    }

    /** 丢掉当前组合文本（不保留在输入框里）：⌫ 撤销本次手写时用。 */
    internal fun discardHandwritingComposing() {
        if (handwritingComposing.isEmpty()) return
        handwritingComposing = ""
        val ic = activeInputConnection() ?: return
        ic.setComposingText("", 1)
        ic.finishComposingText()
    }

    /**
     * 打开手写面板前，把引擎当前未结束的组合收尾掉。
     *
     * 手写组合态与 Rime 的组合区是两套东西：不先结束 Rime 的组合，它的组合文本会与手写的
     * `setComposingText` 互相替换，而且手写期间按空格/回车/标点时 Rime 的 `requestCommit`
     * 会先把它的组合提交掉（见 `RimeEngine.requestCommit`），凭空多出一段字。
     * 收尾语义沿用既有的「切换键盘」：按偏好决定预览文本留还是丢，再重置组合区。
     */
    internal fun settleCompositionForHandwriting() {
        livePreview.finalizeForKeyboardSwitch()
        engine?.resetComposition()
        keyboardWindow?.setCandidates(emptyList())
    }

    internal fun syncActiveInputState() {
        val ic = activeInputConnection() ?: return
        val selection = if (phraseAddBridgeActive) virtualInputConnection.selection
        else lastSelectionStart to lastSelectionEnd
        keyboardWindow?.onSelectionUpdate(selection.first, selection.second)
        keyboardWindow?.onInputChanged(
            ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.toString().orEmpty() +
                ic.getTextAfterCursor(Int.MAX_VALUE, 0)?.toString().orEmpty(),
            virtualInputConnection = phraseAddBridgeActive,
        )
    }

    fun notifyInputChanged() {
        val ic = activeInputConnection() ?: return
        var text = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        text += ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        keyboardWindow?.onInputChanged(text, virtualInputConnection = phraseAddBridgeActive)
        if (text.isEmpty()) engine?.onInputCleared()
    }
}
