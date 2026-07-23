package com.ninthsoft.ime.input.keyboard.key

import com.ninthsoft.ime.R
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant

fun alphabetKey(
    character: String,
    punctuation: String,
    altTextTranslationY: Int = 0,
    mainTextTranslationY: Int = 0,
) = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = character,
        altText = punctuation,
        textSize = 23f,
        altTextTranslationY = altTextTranslationY,
        mainTextTranslationY = mainTextTranslationY,
        variant = Variant.Normal
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(character)),
    ), popups = arrayOf(
        KeyDef.Popup.Preview(character)
    )
)

fun mixedAlphabetKey(digit: String, letters: String, percentWidth: Float = 0.23333f) = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = letters,
        altText = digit,
        textSize = 18f,
        percentWidth = percentWidth,
        mainTextTranslationY = 4,
        altTextTranslationY = 4
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(
            KeyboardAction.KeySequenceAction(digit)
        )
    ),
)

fun commitKey(
    character: String, percentWidth: Float = 0.23333f
) = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = character,
        textSize = 23f,
        variant = Variant.Normal,
        percentWidth = percentWidth
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(character)),
    ), popups = arrayOf(
        KeyDef.Popup.Preview(character)
    )
)

fun sidePannelKey(percentWidth: Float = 0.15f, rowSpan: Int = 3, visableRow: Int = 4) = KeyDef(
    appearance = KeyDef.Appearance.SidePannel(
        rowSpan = rowSpan,
        visableRow = visableRow,
        percentWidth = percentWidth,
        variant = Variant.Alternative
    ),
    behaviors = setOf(),
)

fun sidePannelNormalItem(character: String, percentWidth: Float = 0.15f) = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = character,
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative
    ),
    behaviors = setOf(),
)

fun capsLockKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_capslock_none,
        viewId = KeyView.button_caps,
        percentWidth = 0.15f,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CapsAction),
    ),
)


fun backspaceKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_backspace,
        viewId = KeyView.button_backspace,
        percentWidth = 0.15f,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.BackspaceAction),
        KeyDef.Behavior.Repeat(KeyboardAction.BackspaceAction),
    ),
)

fun layoutSwitchKey(
    displayText: String,
    target: String,
    percentWidth: Float = 0.15f,
): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = displayText, textSize = 15f,
        textStyle = android.graphics.Typeface.BOLD,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.LayoutSwitchAction(target))),
)

fun spaceKey(percentWidth: Float = 0.44f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "",
        textSize = 13f,
        percentWidth = percentWidth,
        border = Border.Special,
        viewId = KeyView.button_space,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.SpaceAction)),
)


fun peroidKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.ImageText(
        displayText = ".",
        textSize = 18f,
        src = R.drawable.ic_keyboard_emoticon,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        viewId = KeyView.button_peroid
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeyCodeAction(android.view.KeyEvent.KEYCODE_PERIOD)),
        KeyDef.Behavior.LongPress(KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME))
    ),
)


fun schemaSwitchKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_language,
        viewId = KeyView.button_lang,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.RotateSchema)),
)

fun returnKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_return,
        viewId = KeyView.button_return,
        percentWidth = percentWidth,
        variant = Variant.Accent,
        border = Border.Special,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ReturnAction)),
)

fun prevPageKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_arrow_up,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        border = Border.On,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ReturnAction)),
)

fun nextPageKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_arrow_down,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        border = Border.On,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ReturnAction)),
)

fun segmentKey(percentWidth: Float = 0.23333f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = "分词",
        altText = "1",
        textSize = 16f,
        percentWidth = percentWidth,
        mainTextTranslationY = 4,
        altTextTranslationY = 6
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(
            KeyboardAction.KeyCodeAction(android.view.KeyEvent.KEYCODE_APOSTROPHE)
        )
    ),
)

fun clearKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "清空",
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.ClearAction)
    ),
)

fun infiniteKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_infinite,
        viewId = KeyView.button_lang,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.LangSwitchAction)),
)