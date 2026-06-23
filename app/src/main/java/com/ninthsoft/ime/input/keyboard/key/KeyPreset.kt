package com.ninthsoft.ime.input.keyboard.key

import com.ninthsoft.ime.R
import com.ninthsoft.ime.engine.data.KeyEvent

fun alphabetKey(character: String, punctuation: String) = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = character, altText = punctuation, textSize = 23f,
    ), behaviors = setOf(
        KeyDef.Behavior.Press(
            KeyAction.PressKeyAction(
                code = KeyEvent.code(character = character), modifiers = 0
            )
        ),
    ), popups = arrayOf(
        KeyDef.Popup.Preview(character)
    )
)

fun capsLockKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_capslock_none,
        viewId = KeyView.button_caps,
        percentWidth = 0.15f,
        variant = KeyDef.Appearance.Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.CapsAction(false)),
        KeyDef.Behavior.LongPress(KeyAction.CapsAction(true)),
        KeyDef.Behavior.DoubleTap(KeyAction.CapsAction(true)),
    ),
)


fun backspaceKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_backspace,
        viewId = KeyView.button_backspace,
        percentWidth = 0.15f,
        variant = KeyDef.Appearance.Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.BackspaceAction),
        KeyDef.Behavior.Repeat(KeyAction.BackspaceAction),
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
        variant = KeyDef.Appearance.Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(target))),
)

fun spaceKey(percentWidth: Float = 0.4f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "拼音",
        textSize = 13f,
        percentWidth = percentWidth,
        border = KeyDef.Appearance.Border.Special,
        viewId = KeyView.button_space,
        variant = KeyDef.Appearance.Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyAction.SpaceAction)),
)