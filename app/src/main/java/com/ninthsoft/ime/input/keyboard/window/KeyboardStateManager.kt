package com.ninthsoft.ime.input.keyboard.window

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.base.util.appContext
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.IKeyboard
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.slot.KeyboardSlot
import com.ninthsoft.ime.input.keyboard.slot.KeyboardSlotPlan
import com.ninthsoft.ime.input.keyboard.slot.SlotInputMethod
import com.ninthsoft.ime.input.keyboard.slot.SlotSwitchItem
import com.ninthsoft.ime.input.keyboard.slot.buildChineseSlotItems
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

        /**
         * 当前槽选中的是「手写」。
         *
         * 手写不经过 `createKeyboard`（没有键盘实例可交出去），也不经过引擎、不需要方案，
         * 面板只有 View 层持有，所以这里只发一个请求，由 View 打开手写面板。
         */
        fun onHandwritingRequested()

        /** 当前槽不再是手写：收起手写面板，把键盘让出来。 */
        fun onHandwritingDismissed()
    }

    var callback: Callback? = null

    private val keyboards: MutableMap<String, IKeyboard> = hashMapOf()
    private var keyboardFactory: ((String) -> IKeyboard)? = null
    private var currentKeyboardName: String? = null
    private var keyboardAttached = false
    /**
     * 「返回上一个键盘」导航栈：记录用户按切换键 / 面板入口之前所在的键盘。
     * 与 [currentKeyboardName] 分离，程序化切换（换槽、换输入框）不会入栈。
     */
    private val backStack = ArrayDeque<String>()
    /** 引擎里全部可用方案（含英文方案）；中文槽的平铺列表由它推出。 */
    private var schemas: List<EngineMessage.Schema> = emptyList()
    /** 中文槽的平铺切换列表（含不可用占位项，设置页要用）。 */
    private var slotItems: List<SlotSwitchItem> = emptyList()
    /** 当前生效的键盘槽。 */
    private var activeSlot: KeyboardSlot = KeyboardSlot.Chinese
    /** 中文槽偏好里的键盘名与方案 id；槽内切换时更新。 */
    private var chineseKeyboardName: String = QwertyKeyboard.NAME
    private var chineseSchemaId: String? = null
    /** 引擎当前方案；英文槽时是 wanxiang_english。 */
    private var currentSchema: EngineMessage.Schema? = null
    private var defaultKeyboardName = QwertyKeyboard.NAME

    // 打字状态：由 Status 消息驱动（RimeEngine 不参与）
    private var isComposing = false

    /**
     * 当前是否处于拼音组合态（由 Rime 的 Status 消息驱动）。
     *
     * 回车键要靠它区分「组合中 → 交给方案处理」与「无组合 → 按输入框语义执行」，
     * 见 `KeyActionListener.handleReturn`。
     */
    val isComposingNow: Boolean get() = isComposing
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
    fun getCurrentKeyboardName(): String? = currentKeyboardName
    fun getActiveSlot(): KeyboardSlot = activeSlot

    /** 中文槽的平铺切换列表（含不可用项，UI 自己决定灰掉还是隐藏）。 */
    fun getChineseSlotItems(): List<SlotSwitchItem> = slotItems

    /**
     * 中文槽当前选中的那一项（可能是手写）。
     *
     * 不能用 [getCurrentKeyboardName] 代替：选到手写时并没有键盘实例，那个字段还停在
     * 「上一个键位」上，UI 高亮必须看槽里的选择。
     */
    fun getSelectedChineseItem(): SlotSwitchItem? = resolveChineseItem()

    fun get(name: String): IKeyboard? = keyboards[name]


    fun onAttach() {
        if (schemas.isEmpty()) refreshSchemas()
        if (currentKeyboardName == null) {
            // 首次挂载（或上一次停在手写、根本没建键盘）：按当前槽落实一次。
            applyActiveSlot()
            return
        }
        if (keyboardAttached) return
        val name = currentKeyboardName
        val keyboard = name?.let { keyboards[it] }
        if (keyboard == null) {
            // 注册表被 rebuild 清空后名字可能还留着：按当前槽重新创建，
            // 不要只把 keyboardAttached 置真却没有任何键盘可显示。
            applyActiveSlot()
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
        when (key) {
            // 可用方案集合变了（重新部署）：整份列表重建，槽选择再收敛一次。
            SchemaManager.KEY_ENABLED_IDS -> refreshSchemas()
            // 槽偏好被设置页改了：重新读入并落实（键盘收起时只更新状态，不强行建键盘）。
            KeyboardManager.Slot.KEY_ACTIVE,
            KeyboardManager.Slot.KEY_CHINESE_KEYBOARD,
            KeyboardManager.Slot.KEY_CHINESE_SCHEMA,
            -> reloadSlotPreferences()
        }
    }

    /** 中 / 英键（地球键）：在两个键盘槽之间来回切；返回切换后的槽。 */
    fun toggleSlot(): KeyboardSlot {
        activeSlot =
            if (activeSlot == KeyboardSlot.English) KeyboardSlot.Chinese else KeyboardSlot.English
        KeyboardManager.Slot.setActiveSlot(appContext, activeSlot)
        backStack.clear()
        applyActiveSlot()
        return activeSlot
    }

    /**
     * 选中中文槽平铺列表里的一项：必要时换方案，再换键盘（手写则打开面板）。
     *
     * 不可用项直接忽略 —— UI 已经把它们画成不可点。
     */
    fun selectSlotItem(item: SlotSwitchItem) {
        if (!item.available) return
        activeSlot = KeyboardSlot.Chinese
        KeyboardManager.Slot.setActiveSlot(appContext, KeyboardSlot.Chinese)
        KeyboardManager.Slot.setChineseSelection(appContext, item.keyboardName, item.schemaId)
        chineseKeyboardName = item.keyboardName
        chineseSchemaId = item.schemaId
        backStack.clear()
        applyChineseItem(item)
    }

    /**
     * 按方案 id 选中（兼容旧入口）：等价于选中中文槽里承载这个方案的那一项。
     *
     * 键盘**不再**由 `schema.layout` 决定 —— 那是槽的偏好；`layout` 只在找不到槽项时兜底。
     */
    fun selectSchema(schemaId: String): String {
        // 同一个方案可能同时挂在 26 键与 15 键下面（都发字母、都用 PinYin），
        // 优先选当前键位对应的那一项，避免「点一下方案键位却跳回 26 键」。
        val item = slotItems.find {
            it.available && it.schemaId == schemaId && it.keyboardName == chineseKeyboardName
        } ?: slotItems.find { it.available && it.schemaId == schemaId }
        if (item != null) {
            selectSlotItem(item)
            return schemaId
        }

        val schema = schemas.find { it.id == schemaId } ?: return ""
        if (currentSchema?.id != schema.id) {
            currentSchema = schema
            EngineFactory.current()?.selectSchema(schema.id)
        }
        backStack.clear()
        switchToKeyboard(schema.layout.ifEmpty { QwertyKeyboard.NAME })
        return schema.id
    }

    fun refreshSchemas() {
        schemas = EngineFactory.current()?.schemasList() ?: emptyList()
        slotItems = buildChineseSlotItems(appContext, schemas)
        backStack.clear()
        // 读入槽偏好并收敛当前选择；已挂载到窗口时由 reloadSlotPreferences 落实键位。
        reloadSlotPreferences()
    }

    /** 读入槽偏好；已经挂载到窗口时立刻落实，否则等 [onAttach] / 下次切换。 */
    private fun reloadSlotPreferences() {
        activeSlot = KeyboardManager.Slot.getActiveSlot(appContext)
        chineseKeyboardName =
            KeyboardManager.Slot.getChineseKeyboard(appContext) ?: chineseKeyboardName
        chineseSchemaId = KeyboardManager.Slot.getChineseSchemaId(appContext)
        ensureChineseSelection()
        if (keyboardAttached) applyActiveSlot()
    }

    /**
     * 偏好里存的 (键盘, 方案) 已经不在一项可用输入方式里时（方案被删、键盘不再支持），
     * 回落到第一项可用的并写回偏好 —— 否则会卡在「选中了一个用不了的输入方式」上。
     *
     * 方案列表还没加载出来（引擎未就绪）时什么都不做：那时所有项都是占位，
     * 拿它做回落会把「暂时读不到方案」误存成用户的永久选择。
     */
    private fun ensureChineseSelection() {
        if (schemas.isEmpty()) return
        if (resolveChineseItem() != null) return
        val fallback = slotItems.firstOrNull { it.available } ?: return
        chineseKeyboardName = fallback.keyboardName
        chineseSchemaId = fallback.schemaId
        KeyboardManager.Slot.setChineseSelection(appContext, fallback.keyboardName, fallback.schemaId)
    }

    private fun resolveChineseItem(): SlotSwitchItem? =
        KeyboardSlotPlan.resolve(slotItems, chineseKeyboardName, chineseSchemaId)

    /** 把「当前槽 + 槽内选择」落实到引擎与键盘。 */
    private fun applyActiveSlot() {
        when (activeSlot) {
            KeyboardSlot.English -> applyEnglishSlot()
            KeyboardSlot.Chinese -> {
                val item = resolveChineseItem()
                if (item != null) applyChineseItem(item) else switchToKeyboard(defaultKeyboardName)
            }
        }
    }

    private fun applyChineseItem(item: SlotSwitchItem) {
        val schema = item.schema
        if (schema != null && currentSchema?.id != schema.id) {
            currentSchema = schema
            EngineFactory.current()?.selectSchema(schema.id)
        }
        if (item.method == SlotInputMethod.Handwriting) {
            // 手写面板盖在键盘上：它自己不是键盘实例，但底下的键位得在
            // （窗口测量与「返回」路径都依赖它）。没有键盘时兜一个默认键位。
            if (currentKeyboardName == null) switchToKeyboard(defaultKeyboardName)
            callback?.onHandwritingRequested()
        } else {
            switchToKeyboard(item.keyboardName)
        }
    }

    /** 英文槽固定：`wanxiang_english` + Qwerty，ascii 输入留在英文方案内部。 */
    private fun applyEnglishSlot() {
        val english = schemas.find { it.id == KeyboardManager.Slot.ENGLISH_SCHEMA_ID }
        if (english != null && currentSchema?.id != english.id) {
            currentSchema = english
            EngineFactory.current()?.selectSchema(english.id)
        }
        switchToKeyboard(KeyboardManager.Slot.ENGLISH_KEYBOARD)
    }

    /**
     * 切到某个真实键盘。
     *
     * 手写面板要先让位 —— 它铺在键盘内容区上，不收起会把刚切出来的键位整个遮住。
     * 单独开一个入口而不是塞进 [switchTo]，是为了让「手写 → 键盘」的收起动作只发生一次，
     * 也避免影响 [pushTo] / [resume] 这些纯粹在键盘之间跳的路径。
     */
    private fun switchToKeyboard(name: String) {
        callback?.onHandwritingDismissed()
        switchTo(name)
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
        // 数字 / 电话输入框要的是数字键盘，覆盖槽的常规键位（含手写）。
        val start = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> NumberKeyboard.NAME
            else -> null
        }
        if (start != null) {
            switchToKeyboard(start)
            return
        }
        // 其余输入框回到「当前槽」的键位：中文槽可能是九键 / 26键 / 15键 / 手写。
        applyActiveSlot()
    }

    /** 返回上一个键盘（用户按「切换/返回」触发）；栈空时回到当前槽的键位。 */
    fun resume() {
        while (true) {
            val previous = backStack.removeLastOrNull() ?: break
            if (previous != currentKeyboardName) {
                switchTo(previous)
                return
            }
        }
        applyActiveSlot()
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
