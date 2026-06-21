package com.ninthsoft.ime.input.keyboard.key

import com.ninthsoft.ime.engine.data.KeyEvent

fun alphabetKey(character: String, punctuation: String) = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = character, altText = punctuation, textSize = 23f,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(
            KeyAction.NormalKeyAction(
                code = KeyEvent.code(character = character), modifiers = 0
            )
        ),
    ),
)


fun backspaceKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = android.R.drawable.ic_menu_delete,
        viewId = KeyView.button_backspace,
        percentWidth = 0.15f,
        variant = KeyDef.Appearance.Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.BackspaceAction),
        KeyDef.Behavior.Repeat(KeyAction.BackspaceAction),
    ),
)
