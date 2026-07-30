package com.ninthsoft.ime.input.keyboard.window

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.ninthsoft.ime.data.Punctuation
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.EmojiKeyboard
import com.ninthsoft.ime.input.keyboard.impl.IKeyboard
import com.ninthsoft.ime.input.keyboard.impl.ISidePanelKeyboard
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.impl.T9Keyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import timber.log.Timber

class KeyboardManager(private val context: Context, val parent: ViewGroup) {

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    private val keyboards: MutableMap<String, IKeyboard> = hashMapOf()

    private var currentKeyboard: IKeyboard? = null

    private var schemas: List<EngineMessage.Schema> = emptyList()

    private var currentSchema: EngineMessage.Schema? = null

    fun getSchemas(): List<EngineMessage.Schema> {
        return schemas
    }

    fun getCurrentSchema(): EngineMessage.Schema? = currentSchema

    init {
        refreshSchemas()
    }

    var keyActionListener: KeyActionListener = KeyActionListener.Empty
        set(value) {
            field = value
            currentKeyboard?.keyActionListener = value
        }

    fun onConfigChanged(key: String) {
        if (key == SchemaManager.KEY_ENABLED_IDS) refreshSchemas()
    }

    fun onPossibleCandidatePinYin(data: Array<CandidatePinYin>) {
        (currentKeyboard as? ISidePanelKeyboard)?.onPossibleCandidatePinYin(data)
    }


    fun rotateSchema(): String {
        if (schemas.isEmpty()) return ""
        val index = schemas.indexOf(currentSchema)
        currentSchema = if (index >= 0) schemas[(index + 1) % schemas.size] else schemas.first()
        switchTo(currentSchema?.layout ?: QwertyKeyboard.NAME)
        return currentSchema?.id.orEmpty()
    }

    fun selectSchema(schemaId: String): String {
        val schema = schemas.find { it.id == schemaId } ?: return ""
        if (currentSchema?.id == schemaId) return schemaId
        currentSchema = schema
        EngineFactory.current()?.selectSchema(schema.id)
        switchTo(schema.layout.ifEmpty { QwertyKeyboard.NAME })
        return schema.id
    }

    private fun refreshSchemas() {
        val prefs = context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE)
        val schemaIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
            ?.filter { it.isNotBlank() } ?: emptyList()
        val schemaList = EngineFactory.current()?.schemasList() ?: emptyList()
        val byId = schemaList.associateBy { it.id }
        schemas = schemaIds.mapNotNull { byId[it] }
        currentSchema = null
        rotateSchema().takeIf { it.isNotEmpty() }?.let { EngineFactory.current()?.selectSchema(it) }
    }

    fun get(name: String): IKeyboard? = keyboards[name]

    private fun create(name: String): IKeyboard {
        val b = when (name) {
            T9Keyboard.NAME -> T9Keyboard(context, cachedColors)
            SymbolKeyboard.NAME -> SymbolKeyboard(context, cachedColors)
            EmojiKeyboard.NAME -> EmojiKeyboard(context, cachedColors)
            NumberKeyboard.NAME -> NumberKeyboard(context, cachedColors)
            else -> QwertyKeyboard(context, cachedColors)
        }
        b.setRippleEnabled(KeyboardManager.Keyboard.RippleEffect.isEnabled(context))
        return b
    }

    private fun attach(name: String, index: Int = -1) {
        currentKeyboard = keyboards.getOrPut(name) { create(name) }
        val view = currentKeyboard as View
        (view.parent as? ViewGroup)?.removeView(view)
        currentKeyboard?.keyActionListener = keyActionListener
        parent.addView(
            view, if (index >= 0) index else parent.childCount, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        currentKeyboard?.onAttach()
    }

    fun switchTo(name: String, index: Int = -1, info: EditorInfo? = null) {
        if (name !== currentKeyboard?.name()) {
            detachCurrent()
            attach(name, index)
        }
        currentKeyboard?.updateSpaceKeyText(currentSchema?.name.orEmpty())
        currentKeyboard?.updatePunctuation(Punctuation.from(currentSchema?.punctuation.orEmpty()))
    }

    fun detachCurrent() {
        currentKeyboard?.let { kb ->
            kb.keyActionListener = null
            kb.onDetach()
            val view = kb as View
            (view.parent as? ViewGroup)?.removeView(view)
            currentKeyboard = null
        }
    }

    fun rebuild(colors: KeyboardColors.ColorScheme) {
        keyboards.clear()
        cachedColors = colors
        if (currentKeyboard != null) {
            val currentName = currentKeyboard?.name().orEmpty()
            detachCurrent()
            attach(currentName)
        }
    }

    fun setRippleEnabled(enabled: Boolean) {
        for (kb in keyboards.values) {
            kb.setRippleEnabled(enabled)
        }
    }

    fun startInput(info: EditorInfo) {
        val start = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> NumberKeyboard.NAME
            else -> currentSchema?.layout ?: QwertyKeyboard.NAME
        }
        switchTo(start)
    }

    fun resume() {
        switchTo(currentSchema?.layout ?: QwertyKeyboard.NAME)
    }

    fun onInputChanged(info: EditorInfo?, text: String) {
        if (info != null) {
            currentKeyboard?.updateEditorInfo(info, text.isEmpty())
        }
    }
}
