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
