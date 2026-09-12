package com.ninthsoft.ime.input.keyboard.window

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.base.util.appContext
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.IKeyboard
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import timber.log.Timber

object KeyboardStateManager {
    /**
     * UI 渲染回调，由上层 View/Window 层实现。
     * 回调直接把「键盘实例」交出去，由 View 负责把它挂到窗口上；
     * 键盘实例本身由 KeyboardStateManager 维护（注册表），但具体的创建（含主题色）
     * 通过 keyboardFactory 交给 View 层，从而不在这里持有颜色/创建 View 的逻辑。
     */
    interface Callback {
        fun onShowKeyboard(keyboard: IKeyboard)
        fun onHideKeyboard(keyboard: IKeyboard)
        fun onKeyboardChanged(keyboard: IKeyboard)
    }

    var callback: Callback? = null

    private val keyboards: MutableMap<String, IKeyboard> = hashMapOf()
    private var keyboardFactory: ((String) -> IKeyboard)? = null
    private var currentKeyboardName: String? = null
    private var keyboardAttached = false
    /**
     * 「返回上一个键盘」导航栈：记录用户按切换键 / 面板入口之前所在的键盘。
     * 与 [currentKeyboardName] 分离，程序化切换（换方案、换输入框）不会入栈。
     */
    private val backStack = ArrayDeque<String>()
    private var schemas: List<EngineMessage.Schema> = emptyList()
    private var currentSchema: EngineMessage.Schema? = null
    private var defaultKeyboardName = QwertyKeyboard.NAME

    // 打字状态：由 Status 消息驱动（RimeEngine 不参与）
    private var isComposing = false
    private var lastEditorInfo: EditorInfo? = null
    private var lastInputEmpty = true
    // 上次实际应用到键盘的入参，用于避免无变化时的重复刷新
    private var lastImeAction = -1
    private var lastAppliedEmpty = true
    private var lastAppliedComposing = false

    var keyActionListener: KeyActionListener = KeyActionListener.Empty
        set(value) {
            field = value
            for (kb in keyboards.values) kb.keyActionListener = value
        }

    fun setKeyboardFactory(factory: (String) -> IKeyboard) {
        keyboardFactory = factory
    }

    fun getSchemas(): List<EngineMessage.Schema> = schemas
    fun getCurrentSchema(): EngineMessage.Schema? = currentSchema
    fun get(name: String): IKeyboard? = keyboards[name]


    fun onAttach() {
        if (schemas.isEmpty()) refreshSchemas()
        if (currentKeyboardName == null) {
            switchTo(currentSchema?.layout ?: defaultKeyboardName)
            return
        }
        if (keyboardAttached) return
        val name = currentKeyboardName
        val keyboard = name?.let { keyboards[it] }
        if (keyboard == null) {
            // 注册表被 rebuild 清空后名字可能还留着：按新配置重新创建，
            // 不要只把 keyboardAttached 置真却没有任何键盘可显示。
            switchTo(name ?: currentSchema?.layout ?: defaultKeyboardName)
            return
        }
        keyboard.onAttach()
        callback?.onShowKeyboard(keyboard)
        keyboardAttached = true
    }

    fun onDetach() {
        if (!keyboardAttached) return
        currentKeyboardName?.let { name ->
            keyboards[name]?.let { kb ->
                if (keyboardAttached) kb.onDetach()
                callback?.onHideKeyboard(kb)
            }
        }
        keyboardAttached = false
    }

    fun onConfigChanged(key: String) {
        if (key == SchemaManager.KEY_ENABLED_IDS) refreshSchemas()
    }

    fun rotateSchema(): String {
        if (schemas.isEmpty()) return ""
        val index = schemas.indexOf(currentSchema)
        currentSchema = if (index >= 0) schemas[(index + 1) % schemas.size] else schemas.first()
        backStack.clear()
        switchTo(currentSchema?.layout ?: QwertyKeyboard.NAME)
        return currentSchema?.id.orEmpty()
    }

    fun selectSchema(schemaId: String): String {
        val schema = schemas.find { it.id == schemaId } ?: return ""
        if (currentSchema?.id == schemaId) return schemaId
        currentSchema = schema
        EngineFactory.current()?.selectSchema(schema.id)
        backStack.clear()
        switchTo(schema.layout.ifEmpty { QwertyKeyboard.NAME })
        return schema.id
    }

    fun refreshSchemas() {
        val prefs = appContext.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE)
        val schemaIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
            ?.filter { it.isNotBlank() } ?: emptyList()
        val schemaList = EngineFactory.current()?.schemasList() ?: emptyList()
        val byId = schemaList.associateBy { it.id }
        schemas = schemaIds.mapNotNull { byId[it] }
        currentSchema = schemas.firstOrNull()
        currentSchema?.id?.let { EngineFactory.current()?.selectSchema(it) }

        backStack.clear()
        // 只有在已经挂载到窗口时才切换键盘布局，避免在 factory 尚未注入、
        if (keyboardAttached) {
            switchTo(currentSchema?.layout ?: defaultKeyboardName)
        }
    }

    private fun create(name: String): IKeyboard {
        val factory = keyboardFactory
            ?: error("KeyboardStateManager.keyboardFactory must be set before creating keyboards")
        return keyboards.getOrPut(name) { factory(name) }
    }

    fun switchTo(name: String) {
        if (name != currentKeyboardName) {
            detachCurrent()
            attachNew(name)
        }
        val kb = keyboards[name]
        kb?.updateSpaceKeyText(currentSchema?.name.orEmpty())
        kb?.updatePunctuationMode(PunctuationMode.from(currentSchema?.punctuation.orEmpty()))
        kb?.let { callback?.onKeyboardChanged(it) }
    }

    /**
     * 用户主动切到别的键盘（键盘上的切换键 / 候选面板入口）。
     * 与 [switchTo] 的区别：会把当前键盘压入 [backStack]，之后 [resume] 能原路返回，
     * 因此“主键盘 → 数字 → 符号 → 返回”会回到数字键盘而不是直接回主键盘。
     */
    fun pushTo(name: String) {
        val current = currentKeyboardName
        if (name == current) return
        if (current != null && backStack.lastOrNull() != current) {
            backStack.addLast(current)
        }
        switchTo(name)
    }

    private fun attachNew(name: String) {
        val keyboard = create(name)
        keyboard.keyActionListener = keyActionListener
        currentKeyboardName = name
        keyboard.onAttach()
        keyboardAttached = true
        callback?.onShowKeyboard(keyboard)
    }

    fun detachCurrent() {
        val name = currentKeyboardName
        if (name != null) {
            keyboards[name]?.let { kb ->
                kb.keyActionListener = null
                if (keyboardAttached) kb.onDetach()
                callback?.onHideKeyboard(kb)
            }
        }
        currentKeyboardName = null
        keyboardAttached = false
    }

    /**
     * 丢弃注册表里缓存的键盘实例，让下次创建重新走 [keyboardFactory]（含主题配色）。
     *
     * 主题切换通常发生在键盘收起时（在设置页里改配色）：此时 [keyboardAttached] 为 false，
     * 不能像已挂载时那样立刻建好并挂上去，但**必须把旧实例清掉**——键盘实例在创建时就把
     * `ColorScheme` 固化进 KeyView 了，留着它们会让 [onAttach] 复用「上一个主题」的键盘，
     * 出现「面板 / 背景已经换色、键帽还是旧配色」的半刷新状态，自定义主题也会看起来没生效。
     */
    fun rebuild() {
        val wasAttached = keyboardAttached
        val currentName = currentKeyboardName
        detachCurrent()
        keyboards.clear()
        // 已挂载：立刻用新配色重建当前键盘；未挂载：保持 currentKeyboardName 为空，
        // 下次 onAttach / startInput 会按新配置重新创建。
        if (wasAttached && currentName != null) {
            switchTo(currentName)
        }
    }

    fun setRippleEnabled(enabled: Boolean) {
        for (kb in keyboards.values) {
            kb.setRippleEnabled(enabled)
        }
    }

    fun startInput(info: EditorInfo) {
        backStack.clear()
        val start = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> NumberKeyboard.NAME
            else -> currentSchema?.layout ?: defaultKeyboardName
        }
        switchTo(start)
    }

    /** 返回上一个键盘（用户按「切换/返回」触发）；栈空时回落到当前方案的布局。 */
    fun resume() {
        while (true) {
            val previous = backStack.removeLastOrNull() ?: break
            if (previous != currentKeyboardName) {
                switchTo(previous)
                return
            }
        }
        switchTo(currentSchema?.layout ?: defaultKeyboardName)
    }

    fun onInputChanged(info: EditorInfo?, text: String, virtualInputConnection: Boolean = false) {
        if (info != null) {
            val effectiveInfo = if (virtualInputConnection) {
                EditorInfo().apply {
                    inputType = info.inputType
                    imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
                }
            } else {
                info
            }
            lastEditorInfo = effectiveInfo
            lastInputEmpty = text.isEmpty()
            updateReturnKeyIfNeeded()
        }
    }

    fun handleEngineMessage(message: EngineMessage) {
        when (message) {
            is EngineMessage.Status -> {
                isComposing = message.isComposing
                updateReturnKeyIfNeeded()
            }

            is EngineMessage.Depoly -> {
                Timber.d("handleEngineMessage EngineMessage.Depoly ")
                if (message.state == EngineMessage.Depoly.State.Finish) {
                    refreshSchemas()
                }
            }

            else -> {}
        }
    }

    // 仅当 (imeAction, empty, isComposing) 三者有实际变化时才刷新回车键。
    private fun updateReturnKeyIfNeeded() {
        val info = lastEditorInfo ?: return
        val keyboard = currentKeyboardName?.let { keyboards[it] } ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == lastImeAction &&
            lastInputEmpty == lastAppliedEmpty &&
            isComposing == lastAppliedComposing
        ) {
            return
        }
        lastImeAction = action
        lastAppliedEmpty = lastInputEmpty
        lastAppliedComposing = isComposing
        keyboard.updateEditorInfo(info, lastInputEmpty, isComposing)
    }
}
