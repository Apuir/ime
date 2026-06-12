package com.ninthsoft.ime.engine.rime.core

import android.view.KeyEvent

public object RimeKeyMapping {
    public const val RimeKey_space: Int = 0x0020

    public const val RimeKey_numbersign: Int = 0x0023

    public const val RimeKey_apostrophe: Int = 0x0027

    public const val RimeKey_asterisk: Int = 0x002a

    public const val RimeKey_plus: Int = 0x002b

    public const val RimeKey_comma: Int = 0x002c

    public const val RimeKey_minus: Int = 0x002d

    public const val RimeKey_period: Int = 0x002e

    public const val RimeKey_slash: Int = 0x002f

    public const val RimeKey_0: Int = 0x0030

    public const val RimeKey_1: Int = 0x0031

    public const val RimeKey_2: Int = 0x0032

    public const val RimeKey_3: Int = 0x0033

    public const val RimeKey_4: Int = 0x0034

    public const val RimeKey_5: Int = 0x0035

    public const val RimeKey_6: Int = 0x0036

    public const val RimeKey_7: Int = 0x0037

    public const val RimeKey_8: Int = 0x0038

    public const val RimeKey_9: Int = 0x0039

    public const val RimeKey_semicolon: Int = 0x003b

    public const val RimeKey_equal: Int = 0x003d

    public const val RimeKey_at: Int = 0x0040

    public const val RimeKey_A: Int = 0x0041

    public const val RimeKey_B: Int = 0x0042

    public const val RimeKey_C: Int = 0x0043

    public const val RimeKey_D: Int = 0x0044

    public const val RimeKey_E: Int = 0x0045

    public const val RimeKey_F: Int = 0x0046

    public const val RimeKey_G: Int = 0x0047

    public const val RimeKey_H: Int = 0x0048

    public const val RimeKey_I: Int = 0x0049

    public const val RimeKey_J: Int = 0x004a

    public const val RimeKey_K: Int = 0x004b

    public const val RimeKey_L: Int = 0x004c

    public const val RimeKey_M: Int = 0x004d

    public const val RimeKey_N: Int = 0x004e

    public const val RimeKey_O: Int = 0x004f

    public const val RimeKey_P: Int = 0x0050

    public const val RimeKey_Q: Int = 0x0051

    public const val RimeKey_R: Int = 0x0052

    public const val RimeKey_S: Int = 0x0053

    public const val RimeKey_T: Int = 0x0054

    public const val RimeKey_U: Int = 0x0055

    public const val RimeKey_V: Int = 0x0056

    public const val RimeKey_W: Int = 0x0057

    public const val RimeKey_X: Int = 0x0058

    public const val RimeKey_Y: Int = 0x0059

    public const val RimeKey_Z: Int = 0x005a

    public const val RimeKey_bracketleft: Int = 0x005b

    public const val RimeKey_backslash: Int = 0x005c

    public const val RimeKey_bracketright: Int = 0x005d

    public const val RimeKey_grave: Int = 0x0060

    public const val RimeKey_a: Int = 0x0061

    public const val RimeKey_b: Int = 0x0062

    public const val RimeKey_c: Int = 0x0063

    public const val RimeKey_d: Int = 0x0064

    public const val RimeKey_e: Int = 0x0065

    public const val RimeKey_f: Int = 0x0066

    public const val RimeKey_g: Int = 0x0067

    public const val RimeKey_h: Int = 0x0068

    public const val RimeKey_i: Int = 0x0069

    public const val RimeKey_j: Int = 0x006a

    public const val RimeKey_k: Int = 0x006b

    public const val RimeKey_l: Int = 0x006c

    public const val RimeKey_m: Int = 0x006d

    public const val RimeKey_n: Int = 0x006e

    public const val RimeKey_o: Int = 0x006f

    public const val RimeKey_p: Int = 0x0070

    public const val RimeKey_q: Int = 0x0071

    public const val RimeKey_r: Int = 0x0072

    public const val RimeKey_s: Int = 0x0073

    public const val RimeKey_t: Int = 0x0074

    public const val RimeKey_u: Int = 0x0075

    public const val RimeKey_v: Int = 0x0076

    public const val RimeKey_w: Int = 0x0077

    public const val RimeKey_x: Int = 0x0078

    public const val RimeKey_y: Int = 0x0079

    public const val RimeKey_z: Int = 0x007a

    public const val RimeKey_F1: Int = 0xffbe

    public const val RimeKey_F2: Int = 0xffbf

    public const val RimeKey_F3: Int = 0xffc0

    public const val RimeKey_F4: Int = 0xffc1

    public const val RimeKey_F5: Int = 0xffc2

    public const val RimeKey_F6: Int = 0xffc3

    public const val RimeKey_F7: Int = 0xffc4

    public const val RimeKey_F8: Int = 0xffc5

    public const val RimeKey_F9: Int = 0xffc6

    public const val RimeKey_F10: Int = 0xffc7

    public const val RimeKey_F11: Int = 0xffc8

    public const val RimeKey_F12: Int = 0xffc9

    public const val RimeKey_Shift_L: Int = 0xffe1

    public const val RimeKey_Shift_R: Int = 0xffe2

    public const val RimeKey_Control_L: Int = 0xffe3

    public const val RimeKey_Control_R: Int = 0xffe4

    public const val RimeKey_Caps_Lock: Int = 0xffe5

    public const val RimeKey_Meta_L: Int = 0xffe7

    public const val RimeKey_Meta_R: Int = 0xffe8

    public const val RimeKey_Alt_L: Int = 0xffe9

    public const val RimeKey_Alt_R: Int = 0xffea

    public const val RimeKey_Insert: Int = 0xff63

    public const val RimeKey_Delete: Int = 0xffff

    public const val RimeKey_Home: Int = 0xff50

    public const val RimeKey_End: Int = 0xff57

    public const val RimeKey_Page_Down: Int = 0xff56

    public const val RimeKey_Page_Up: Int = 0xff55

    public const val RimeKey_Tab: Int = 0xff09

    public const val RimeKey_BackSpace: Int = 0xff08

    public const val RimeKey_Return: Int = 0xff0d

    public const val RimeKey_Escape: Int = 0xff1b

    public const val RimeKey_Up: Int = 0xff52

    public const val RimeKey_Down: Int = 0xff54

    public const val RimeKey_Left: Int = 0xff51

    public const val RimeKey_Right: Int = 0xff53

    public const val RimeKey_KP_Divide: Int = 0xffaf

    public const val RimeKey_KP_Multiply: Int = 0xffaa

    public const val RimeKey_KP_Subtract: Int = 0xffad

    public const val RimeKey_KP_7: Int = 0xffb7

    public const val RimeKey_KP_8: Int = 0xffb8

    public const val RimeKey_KP_9: Int = 0xffb9

    public const val RimeKey_KP_Add: Int = 0xffab

    public const val RimeKey_KP_4: Int = 0xffb4

    public const val RimeKey_KP_5: Int = 0xffb5

    public const val RimeKey_KP_6: Int = 0xffb6

    public const val RimeKey_KP_1: Int = 0xffb1

    public const val RimeKey_KP_2: Int = 0xffb2

    public const val RimeKey_KP_3: Int = 0xffb3

    public const val RimeKey_KP_Enter: Int = 0xff8d

    public const val RimeKey_KP_0: Int = 0xffb0

    public const val RimeKey_KP_Decimal: Int = 0xffae

    public const val RimeKey_Eisu_toggle: Int = 0xff30

    public const val RimeKey_Kana_Lock: Int = 0xff2d

    public const val RimeKey_Hiragana_Katakana: Int = 0xff27

    public const val RimeKey_Zenkaku_Hankaku: Int = 0xff2a

    public const val RimeKey_VoidSymbol: Int = 0xffffff

    @JvmStatic
    public fun valToKeyCode(v: Int): Int {
        return when (v) {
            RimeKey_space -> KeyEvent.KEYCODE_SPACE
            RimeKey_numbersign -> KeyEvent.KEYCODE_POUND
            RimeKey_apostrophe -> KeyEvent.KEYCODE_APOSTROPHE
            RimeKey_asterisk -> KeyEvent.KEYCODE_STAR
            RimeKey_plus -> KeyEvent.KEYCODE_PLUS
            RimeKey_comma -> KeyEvent.KEYCODE_COMMA
            RimeKey_minus -> KeyEvent.KEYCODE_MINUS
            RimeKey_period -> KeyEvent.KEYCODE_PERIOD
            RimeKey_slash -> KeyEvent.KEYCODE_SLASH
            RimeKey_0 -> KeyEvent.KEYCODE_0
            RimeKey_1 -> KeyEvent.KEYCODE_1
            RimeKey_2 -> KeyEvent.KEYCODE_2
            RimeKey_3 -> KeyEvent.KEYCODE_3
            RimeKey_4 -> KeyEvent.KEYCODE_4
            RimeKey_5 -> KeyEvent.KEYCODE_5
            RimeKey_6 -> KeyEvent.KEYCODE_6
            RimeKey_7 -> KeyEvent.KEYCODE_7
            RimeKey_8 -> KeyEvent.KEYCODE_8
            RimeKey_9 -> KeyEvent.KEYCODE_9
            RimeKey_semicolon -> KeyEvent.KEYCODE_SEMICOLON
            RimeKey_equal -> KeyEvent.KEYCODE_EQUALS
            RimeKey_at -> KeyEvent.KEYCODE_AT
            RimeKey_A -> KeyEvent.KEYCODE_A
            RimeKey_B -> KeyEvent.KEYCODE_B
            RimeKey_C -> KeyEvent.KEYCODE_C
            RimeKey_D -> KeyEvent.KEYCODE_D
            RimeKey_E -> KeyEvent.KEYCODE_E
            RimeKey_F -> KeyEvent.KEYCODE_F
            RimeKey_G -> KeyEvent.KEYCODE_G
            RimeKey_H -> KeyEvent.KEYCODE_H
            RimeKey_I -> KeyEvent.KEYCODE_I
            RimeKey_J -> KeyEvent.KEYCODE_J
            RimeKey_K -> KeyEvent.KEYCODE_K
            RimeKey_L -> KeyEvent.KEYCODE_L
            RimeKey_M -> KeyEvent.KEYCODE_M
            RimeKey_N -> KeyEvent.KEYCODE_N
            RimeKey_O -> KeyEvent.KEYCODE_O
            RimeKey_P -> KeyEvent.KEYCODE_P
            RimeKey_Q -> KeyEvent.KEYCODE_Q
            RimeKey_R -> KeyEvent.KEYCODE_R
            RimeKey_S -> KeyEvent.KEYCODE_S
            RimeKey_T -> KeyEvent.KEYCODE_T
            RimeKey_U -> KeyEvent.KEYCODE_U
            RimeKey_V -> KeyEvent.KEYCODE_V
            RimeKey_W -> KeyEvent.KEYCODE_W
            RimeKey_X -> KeyEvent.KEYCODE_X
            RimeKey_Y -> KeyEvent.KEYCODE_Y
            RimeKey_Z -> KeyEvent.KEYCODE_Z
            RimeKey_bracketleft -> KeyEvent.KEYCODE_LEFT_BRACKET
            RimeKey_backslash -> KeyEvent.KEYCODE_BACKSLASH
            RimeKey_bracketright -> KeyEvent.KEYCODE_RIGHT_BRACKET
            RimeKey_grave -> KeyEvent.KEYCODE_GRAVE
            RimeKey_a -> KeyEvent.KEYCODE_A
            RimeKey_b -> KeyEvent.KEYCODE_B
            RimeKey_c -> KeyEvent.KEYCODE_C
            RimeKey_d -> KeyEvent.KEYCODE_D
            RimeKey_e -> KeyEvent.KEYCODE_E
            RimeKey_f -> KeyEvent.KEYCODE_F
            RimeKey_g -> KeyEvent.KEYCODE_G
            RimeKey_h -> KeyEvent.KEYCODE_H
            RimeKey_i -> KeyEvent.KEYCODE_I
            RimeKey_j -> KeyEvent.KEYCODE_J
            RimeKey_k -> KeyEvent.KEYCODE_K
            RimeKey_l -> KeyEvent.KEYCODE_L
            RimeKey_m -> KeyEvent.KEYCODE_M
            RimeKey_n -> KeyEvent.KEYCODE_N
            RimeKey_o -> KeyEvent.KEYCODE_O
            RimeKey_p -> KeyEvent.KEYCODE_P
            RimeKey_q -> KeyEvent.KEYCODE_Q
            RimeKey_r -> KeyEvent.KEYCODE_R
            RimeKey_s -> KeyEvent.KEYCODE_S
            RimeKey_t -> KeyEvent.KEYCODE_T
            RimeKey_u -> KeyEvent.KEYCODE_U
            RimeKey_v -> KeyEvent.KEYCODE_V
            RimeKey_w -> KeyEvent.KEYCODE_W
            RimeKey_x -> KeyEvent.KEYCODE_X
            RimeKey_y -> KeyEvent.KEYCODE_Y
            RimeKey_z -> KeyEvent.KEYCODE_Z
            RimeKey_F1 -> KeyEvent.KEYCODE_F1
            RimeKey_F2 -> KeyEvent.KEYCODE_F2
            RimeKey_F3 -> KeyEvent.KEYCODE_F3
            RimeKey_F4 -> KeyEvent.KEYCODE_F4
            RimeKey_F5 -> KeyEvent.KEYCODE_F5
            RimeKey_F6 -> KeyEvent.KEYCODE_F6
            RimeKey_F7 -> KeyEvent.KEYCODE_F7
            RimeKey_F8 -> KeyEvent.KEYCODE_F8
            RimeKey_F9 -> KeyEvent.KEYCODE_F9
            RimeKey_F10 -> KeyEvent.KEYCODE_F10
            RimeKey_F11 -> KeyEvent.KEYCODE_F11
            RimeKey_F12 -> KeyEvent.KEYCODE_F12
            RimeKey_Shift_L -> KeyEvent.KEYCODE_SHIFT_LEFT
            RimeKey_Shift_R -> KeyEvent.KEYCODE_SHIFT_RIGHT
            RimeKey_Control_L -> KeyEvent.KEYCODE_CTRL_LEFT
            RimeKey_Control_R -> KeyEvent.KEYCODE_CTRL_RIGHT
            RimeKey_Caps_Lock -> KeyEvent.KEYCODE_CAPS_LOCK
            RimeKey_Meta_L -> KeyEvent.KEYCODE_META_LEFT
            RimeKey_Meta_R -> KeyEvent.KEYCODE_META_RIGHT
            RimeKey_Alt_L -> KeyEvent.KEYCODE_ALT_LEFT
            RimeKey_Alt_R -> KeyEvent.KEYCODE_ALT_RIGHT
            RimeKey_Insert -> KeyEvent.KEYCODE_INSERT
            RimeKey_Delete -> KeyEvent.KEYCODE_FORWARD_DEL
            RimeKey_Home -> KeyEvent.KEYCODE_MOVE_HOME
            RimeKey_End -> KeyEvent.KEYCODE_MOVE_END
            RimeKey_Page_Down -> KeyEvent.KEYCODE_PAGE_DOWN
            RimeKey_Page_Up -> KeyEvent.KEYCODE_PAGE_UP
            RimeKey_Tab -> KeyEvent.KEYCODE_TAB
            RimeKey_BackSpace -> KeyEvent.KEYCODE_DEL
            RimeKey_Return -> KeyEvent.KEYCODE_ENTER
            RimeKey_Escape -> KeyEvent.KEYCODE_ESCAPE
            RimeKey_Up -> KeyEvent.KEYCODE_DPAD_UP
            RimeKey_Down -> KeyEvent.KEYCODE_DPAD_DOWN
            RimeKey_Left -> KeyEvent.KEYCODE_DPAD_LEFT
            RimeKey_Right -> KeyEvent.KEYCODE_DPAD_RIGHT
            RimeKey_KP_Divide -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
            RimeKey_KP_Multiply -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            RimeKey_KP_Subtract -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            RimeKey_KP_7 -> KeyEvent.KEYCODE_NUMPAD_7
            RimeKey_KP_8 -> KeyEvent.KEYCODE_NUMPAD_8
            RimeKey_KP_9 -> KeyEvent.KEYCODE_NUMPAD_9
            RimeKey_KP_Add -> KeyEvent.KEYCODE_NUMPAD_ADD
            RimeKey_KP_4 -> KeyEvent.KEYCODE_NUMPAD_4
            RimeKey_KP_5 -> KeyEvent.KEYCODE_NUMPAD_5
            RimeKey_KP_6 -> KeyEvent.KEYCODE_NUMPAD_6
            RimeKey_KP_1 -> KeyEvent.KEYCODE_NUMPAD_1
            RimeKey_KP_2 -> KeyEvent.KEYCODE_NUMPAD_2
            RimeKey_KP_3 -> KeyEvent.KEYCODE_NUMPAD_3
            RimeKey_KP_Enter -> KeyEvent.KEYCODE_NUMPAD_ENTER
            RimeKey_KP_0 -> KeyEvent.KEYCODE_NUMPAD_0
            RimeKey_KP_Decimal -> KeyEvent.KEYCODE_NUMPAD_DOT
            RimeKey_Eisu_toggle -> KeyEvent.KEYCODE_EISU
            RimeKey_Kana_Lock -> KeyEvent.KEYCODE_KANA
            RimeKey_Hiragana_Katakana -> KeyEvent.KEYCODE_KATAKANA_HIRAGANA
            RimeKey_Zenkaku_Hankaku -> KeyEvent.KEYCODE_ZENKAKU_HANKAKU
            RimeKey_VoidSymbol -> KeyEvent.KEYCODE_UNKNOWN
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    /**
     * Duplicate labels are expected, as the mapping is not one-to-one
     */
    @JvmStatic
    public fun keyCodeToVal(code: Int): Int {
        return when (code) {
            KeyEvent.KEYCODE_SPACE -> RimeKey_space
            KeyEvent.KEYCODE_POUND -> RimeKey_numbersign
            KeyEvent.KEYCODE_APOSTROPHE -> RimeKey_apostrophe
            KeyEvent.KEYCODE_STAR -> RimeKey_asterisk
            KeyEvent.KEYCODE_PLUS -> RimeKey_plus
            KeyEvent.KEYCODE_COMMA -> RimeKey_comma
            KeyEvent.KEYCODE_MINUS -> RimeKey_minus
            KeyEvent.KEYCODE_PERIOD -> RimeKey_period
            KeyEvent.KEYCODE_SLASH -> RimeKey_slash
            KeyEvent.KEYCODE_0 -> RimeKey_0
            KeyEvent.KEYCODE_1 -> RimeKey_1
            KeyEvent.KEYCODE_2 -> RimeKey_2
            KeyEvent.KEYCODE_3 -> RimeKey_3
            KeyEvent.KEYCODE_4 -> RimeKey_4
            KeyEvent.KEYCODE_5 -> RimeKey_5
            KeyEvent.KEYCODE_6 -> RimeKey_6
            KeyEvent.KEYCODE_7 -> RimeKey_7
            KeyEvent.KEYCODE_8 -> RimeKey_8
            KeyEvent.KEYCODE_9 -> RimeKey_9
            KeyEvent.KEYCODE_SEMICOLON -> RimeKey_semicolon
            KeyEvent.KEYCODE_EQUALS -> RimeKey_equal
            KeyEvent.KEYCODE_AT -> RimeKey_at
            KeyEvent.KEYCODE_LEFT_BRACKET -> RimeKey_bracketleft
            KeyEvent.KEYCODE_BACKSLASH -> RimeKey_backslash
            KeyEvent.KEYCODE_RIGHT_BRACKET -> RimeKey_bracketright
            KeyEvent.KEYCODE_GRAVE -> RimeKey_grave
            KeyEvent.KEYCODE_A -> RimeKey_a
            KeyEvent.KEYCODE_B -> RimeKey_b
            KeyEvent.KEYCODE_C -> RimeKey_c
            KeyEvent.KEYCODE_D -> RimeKey_d
            KeyEvent.KEYCODE_E -> RimeKey_e
            KeyEvent.KEYCODE_F -> RimeKey_f
            KeyEvent.KEYCODE_G -> RimeKey_g
            KeyEvent.KEYCODE_H -> RimeKey_h
            KeyEvent.KEYCODE_I -> RimeKey_i
            KeyEvent.KEYCODE_J -> RimeKey_j
            KeyEvent.KEYCODE_K -> RimeKey_k
            KeyEvent.KEYCODE_L -> RimeKey_l
            KeyEvent.KEYCODE_M -> RimeKey_m
            KeyEvent.KEYCODE_N -> RimeKey_n
            KeyEvent.KEYCODE_O -> RimeKey_o
            KeyEvent.KEYCODE_P -> RimeKey_p
            KeyEvent.KEYCODE_Q -> RimeKey_q
            KeyEvent.KEYCODE_R -> RimeKey_r
            KeyEvent.KEYCODE_S -> RimeKey_s
            KeyEvent.KEYCODE_T -> RimeKey_t
            KeyEvent.KEYCODE_U -> RimeKey_u
            KeyEvent.KEYCODE_V -> RimeKey_v
            KeyEvent.KEYCODE_W -> RimeKey_w
            KeyEvent.KEYCODE_X -> RimeKey_x
            KeyEvent.KEYCODE_Y -> RimeKey_y
            KeyEvent.KEYCODE_Z -> RimeKey_z
            KeyEvent.KEYCODE_F1 -> RimeKey_F1
            KeyEvent.KEYCODE_F2 -> RimeKey_F2
            KeyEvent.KEYCODE_F3 -> RimeKey_F3
            KeyEvent.KEYCODE_F4 -> RimeKey_F4
            KeyEvent.KEYCODE_F5 -> RimeKey_F5
            KeyEvent.KEYCODE_F6 -> RimeKey_F6
            KeyEvent.KEYCODE_F7 -> RimeKey_F7
            KeyEvent.KEYCODE_F8 -> RimeKey_F8
            KeyEvent.KEYCODE_F9 -> RimeKey_F9
            KeyEvent.KEYCODE_F10 -> RimeKey_F10
            KeyEvent.KEYCODE_F11 -> RimeKey_F11
            KeyEvent.KEYCODE_F12 -> RimeKey_F12
            KeyEvent.KEYCODE_SHIFT_LEFT -> RimeKey_Shift_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> RimeKey_Shift_R
            KeyEvent.KEYCODE_CTRL_LEFT -> RimeKey_Control_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> RimeKey_Control_R
            KeyEvent.KEYCODE_CAPS_LOCK -> RimeKey_Caps_Lock
            KeyEvent.KEYCODE_META_LEFT -> RimeKey_Meta_L
            KeyEvent.KEYCODE_META_RIGHT -> RimeKey_Meta_R
            KeyEvent.KEYCODE_ALT_LEFT -> RimeKey_Alt_L
            KeyEvent.KEYCODE_ALT_RIGHT -> RimeKey_Alt_R
            KeyEvent.KEYCODE_INSERT -> RimeKey_Insert
            KeyEvent.KEYCODE_FORWARD_DEL -> RimeKey_Delete
            KeyEvent.KEYCODE_MOVE_HOME -> RimeKey_Home
            KeyEvent.KEYCODE_MOVE_END -> RimeKey_End
            KeyEvent.KEYCODE_PAGE_DOWN -> RimeKey_Page_Down
            KeyEvent.KEYCODE_PAGE_UP -> RimeKey_Page_Up
            KeyEvent.KEYCODE_TAB -> RimeKey_Tab
            KeyEvent.KEYCODE_DEL -> RimeKey_BackSpace
            KeyEvent.KEYCODE_ENTER -> RimeKey_Return
            KeyEvent.KEYCODE_ESCAPE -> RimeKey_Escape
            KeyEvent.KEYCODE_DPAD_UP -> RimeKey_Up
            KeyEvent.KEYCODE_DPAD_DOWN -> RimeKey_Down
            KeyEvent.KEYCODE_DPAD_LEFT -> RimeKey_Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> RimeKey_Right
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> RimeKey_KP_Divide
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> RimeKey_KP_Multiply
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> RimeKey_KP_Subtract
            KeyEvent.KEYCODE_NUMPAD_7 -> RimeKey_KP_7
            KeyEvent.KEYCODE_NUMPAD_8 -> RimeKey_KP_8
            KeyEvent.KEYCODE_NUMPAD_9 -> RimeKey_KP_9
            KeyEvent.KEYCODE_NUMPAD_ADD -> RimeKey_KP_Add
            KeyEvent.KEYCODE_NUMPAD_4 -> RimeKey_KP_4
            KeyEvent.KEYCODE_NUMPAD_5 -> RimeKey_KP_5
            KeyEvent.KEYCODE_NUMPAD_6 -> RimeKey_KP_6
            KeyEvent.KEYCODE_NUMPAD_1 -> RimeKey_KP_1
            KeyEvent.KEYCODE_NUMPAD_2 -> RimeKey_KP_2
            KeyEvent.KEYCODE_NUMPAD_3 -> RimeKey_KP_3
            KeyEvent.KEYCODE_NUMPAD_ENTER -> RimeKey_KP_Enter
            KeyEvent.KEYCODE_NUMPAD_0 -> RimeKey_KP_0
            KeyEvent.KEYCODE_NUMPAD_DOT -> RimeKey_KP_Decimal
            KeyEvent.KEYCODE_EISU -> RimeKey_Eisu_toggle
            KeyEvent.KEYCODE_KANA -> RimeKey_Kana_Lock
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> RimeKey_Hiragana_Katakana
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> RimeKey_Zenkaku_Hankaku
            KeyEvent.KEYCODE_UNKNOWN -> RimeKey_VoidSymbol
            else -> RimeKey_VoidSymbol
        }
    }

    @JvmStatic
    public fun nameToKeyVal(name: String): Int {
        return when (name) {
            "space" -> RimeKey_space
            "numbersign" -> RimeKey_numbersign
            "apostrophe" -> RimeKey_apostrophe
            "asterisk" -> RimeKey_asterisk
            "plus" -> RimeKey_plus
            "comma" -> RimeKey_comma
            "minus" -> RimeKey_minus
            "period" -> RimeKey_period
            "slash" -> RimeKey_slash
            "0" -> RimeKey_0
            "1" -> RimeKey_1
            "2" -> RimeKey_2
            "3" -> RimeKey_3
            "4" -> RimeKey_4
            "5" -> RimeKey_5
            "6" -> RimeKey_6
            "7" -> RimeKey_7
            "8" -> RimeKey_8
            "9" -> RimeKey_9
            "semicolon" -> RimeKey_semicolon
            "equal" -> RimeKey_equal
            "at" -> RimeKey_at
            "A" -> RimeKey_A
            "B" -> RimeKey_B
            "C" -> RimeKey_C
            "D" -> RimeKey_D
            "E" -> RimeKey_E
            "F" -> RimeKey_F
            "G" -> RimeKey_G
            "H" -> RimeKey_H
            "I" -> RimeKey_I
            "J" -> RimeKey_J
            "K" -> RimeKey_K
            "L" -> RimeKey_L
            "M" -> RimeKey_M
            "N" -> RimeKey_N
            "O" -> RimeKey_O
            "P" -> RimeKey_P
            "Q" -> RimeKey_Q
            "R" -> RimeKey_R
            "S" -> RimeKey_S
            "T" -> RimeKey_T
            "U" -> RimeKey_U
            "V" -> RimeKey_V
            "W" -> RimeKey_W
            "X" -> RimeKey_X
            "Y" -> RimeKey_Y
            "Z" -> RimeKey_Z
            "bracketleft" -> RimeKey_bracketleft
            "backslash" -> RimeKey_backslash
            "bracketright" -> RimeKey_bracketright
            "grave" -> RimeKey_grave
            "a" -> RimeKey_a
            "b" -> RimeKey_b
            "c" -> RimeKey_c
            "d" -> RimeKey_d
            "e" -> RimeKey_e
            "f" -> RimeKey_f
            "g" -> RimeKey_g
            "h" -> RimeKey_h
            "i" -> RimeKey_i
            "j" -> RimeKey_j
            "k" -> RimeKey_k
            "l" -> RimeKey_l
            "m" -> RimeKey_m
            "n" -> RimeKey_n
            "o" -> RimeKey_o
            "p" -> RimeKey_p
            "q" -> RimeKey_q
            "r" -> RimeKey_r
            "s" -> RimeKey_s
            "t" -> RimeKey_t
            "u" -> RimeKey_u
            "v" -> RimeKey_v
            "w" -> RimeKey_w
            "x" -> RimeKey_x
            "y" -> RimeKey_y
            "z" -> RimeKey_z
            "F1" -> RimeKey_F1
            "F2" -> RimeKey_F2
            "F3" -> RimeKey_F3
            "F4" -> RimeKey_F4
            "F5" -> RimeKey_F5
            "F6" -> RimeKey_F6
            "F7" -> RimeKey_F7
            "F8" -> RimeKey_F8
            "F9" -> RimeKey_F9
            "F10" -> RimeKey_F10
            "F11" -> RimeKey_F11
            "F12" -> RimeKey_F12
            "Shift_L" -> RimeKey_Shift_L
            "Shift_R" -> RimeKey_Shift_R
            "Control_L" -> RimeKey_Control_L
            "Control_R" -> RimeKey_Control_R
            "Caps_Lock" -> RimeKey_Caps_Lock
            "Meta_L" -> RimeKey_Meta_L
            "Meta_R" -> RimeKey_Meta_R
            "Alt_L" -> RimeKey_Alt_L
            "Alt_R" -> RimeKey_Alt_R
            "Insert" -> RimeKey_Insert
            "Delete" -> RimeKey_Delete
            "Home" -> RimeKey_Home
            "End" -> RimeKey_End
            "Page_Down" -> RimeKey_Page_Down
            "Page_Up" -> RimeKey_Page_Up
            "Tab" -> RimeKey_Tab
            "BackSpace" -> RimeKey_BackSpace
            "Return" -> RimeKey_Return
            "Escape" -> RimeKey_Escape
            "Up" -> RimeKey_Up
            "Down" -> RimeKey_Down
            "Left" -> RimeKey_Left
            "Right" -> RimeKey_Right
            "KP_Divide" -> RimeKey_KP_Divide
            "KP_Multiply" -> RimeKey_KP_Multiply
            "KP_Subtract" -> RimeKey_KP_Subtract
            "KP_7" -> RimeKey_KP_7
            "KP_8" -> RimeKey_KP_8
            "KP_9" -> RimeKey_KP_9
            "KP_Add" -> RimeKey_KP_Add
            "KP_4" -> RimeKey_KP_4
            "KP_5" -> RimeKey_KP_5
            "KP_6" -> RimeKey_KP_6
            "KP_1" -> RimeKey_KP_1
            "KP_2" -> RimeKey_KP_2
            "KP_3" -> RimeKey_KP_3
            "KP_Enter" -> RimeKey_KP_Enter
            "KP_0" -> RimeKey_KP_0
            "KP_Decimal" -> RimeKey_KP_Decimal
            "Eisu_toggle" -> RimeKey_Eisu_toggle
            "Kana_Lock" -> RimeKey_Kana_Lock
            "Hiragana_Katakana" -> RimeKey_Hiragana_Katakana
            "Zenkaku_Hankaku" -> RimeKey_Zenkaku_Hankaku
            "VoidSymbol" -> RimeKey_VoidSymbol
            else -> RimeKey_VoidSymbol
        }
    }

    @JvmStatic
    public fun keyValToName(`val`: Int): String {
        return when (`val`) {
            RimeKey_space -> "space"
            RimeKey_numbersign -> "numbersign"
            RimeKey_apostrophe -> "apostrophe"
            RimeKey_asterisk -> "asterisk"
            RimeKey_plus -> "plus"
            RimeKey_comma -> "comma"
            RimeKey_minus -> "minus"
            RimeKey_period -> "period"
            RimeKey_slash -> "slash"
            RimeKey_0 -> "0"
            RimeKey_1 -> "1"
            RimeKey_2 -> "2"
            RimeKey_3 -> "3"
            RimeKey_4 -> "4"
            RimeKey_5 -> "5"
            RimeKey_6 -> "6"
            RimeKey_7 -> "7"
            RimeKey_8 -> "8"
            RimeKey_9 -> "9"
            RimeKey_semicolon -> "semicolon"
            RimeKey_equal -> "equal"
            RimeKey_at -> "at"
            RimeKey_A -> "A"
            RimeKey_B -> "B"
            RimeKey_C -> "C"
            RimeKey_D -> "D"
            RimeKey_E -> "E"
            RimeKey_F -> "F"
            RimeKey_G -> "G"
            RimeKey_H -> "H"
            RimeKey_I -> "I"
            RimeKey_J -> "J"
            RimeKey_K -> "K"
            RimeKey_L -> "L"
            RimeKey_M -> "M"
            RimeKey_N -> "N"
            RimeKey_O -> "O"
            RimeKey_P -> "P"
            RimeKey_Q -> "Q"
            RimeKey_R -> "R"
            RimeKey_S -> "S"
            RimeKey_T -> "T"
            RimeKey_U -> "U"
            RimeKey_V -> "V"
            RimeKey_W -> "W"
            RimeKey_X -> "X"
            RimeKey_Y -> "Y"
            RimeKey_Z -> "Z"
            RimeKey_bracketleft -> "bracketleft"
            RimeKey_backslash -> "backslash"
            RimeKey_bracketright -> "bracketright"
            RimeKey_grave -> "grave"
            RimeKey_a -> "a"
            RimeKey_b -> "b"
            RimeKey_c -> "c"
            RimeKey_d -> "d"
            RimeKey_e -> "e"
            RimeKey_f -> "f"
            RimeKey_g -> "g"
            RimeKey_h -> "h"
            RimeKey_i -> "i"
            RimeKey_j -> "j"
            RimeKey_k -> "k"
            RimeKey_l -> "l"
            RimeKey_m -> "m"
            RimeKey_n -> "n"
            RimeKey_o -> "o"
            RimeKey_p -> "p"
            RimeKey_q -> "q"
            RimeKey_r -> "r"
            RimeKey_s -> "s"
            RimeKey_t -> "t"
            RimeKey_u -> "u"
            RimeKey_v -> "v"
            RimeKey_w -> "w"
            RimeKey_x -> "x"
            RimeKey_y -> "y"
            RimeKey_z -> "z"
            RimeKey_F1 -> "F1"
            RimeKey_F2 -> "F2"
            RimeKey_F3 -> "F3"
            RimeKey_F4 -> "F4"
            RimeKey_F5 -> "F5"
            RimeKey_F6 -> "F6"
            RimeKey_F7 -> "F7"
            RimeKey_F8 -> "F8"
            RimeKey_F9 -> "F9"
            RimeKey_F10 -> "F10"
            RimeKey_F11 -> "F11"
            RimeKey_F12 -> "F12"
            RimeKey_Shift_L -> "Shift_L"
            RimeKey_Shift_R -> "Shift_R"
            RimeKey_Control_L -> "Control_L"
            RimeKey_Control_R -> "Control_R"
            RimeKey_Caps_Lock -> "Caps_Lock"
            RimeKey_Meta_L -> "Meta_L"
            RimeKey_Meta_R -> "Meta_R"
            RimeKey_Alt_L -> "Alt_L"
            RimeKey_Alt_R -> "Alt_R"
            RimeKey_Insert -> "Insert"
            RimeKey_Delete -> "Delete"
            RimeKey_Home -> "Home"
            RimeKey_End -> "End"
            RimeKey_Page_Down -> "Page_Down"
            RimeKey_Page_Up -> "Page_Up"
            RimeKey_Tab -> "Tab"
            RimeKey_BackSpace -> "BackSpace"
            RimeKey_Return -> "Return"
            RimeKey_Escape -> "Escape"
            RimeKey_Up -> "Up"
            RimeKey_Down -> "Down"
            RimeKey_Left -> "Left"
            RimeKey_Right -> "Right"
            RimeKey_KP_Divide -> "KP_Divide"
            RimeKey_KP_Multiply -> "KP_Multiply"
            RimeKey_KP_Subtract -> "KP_Subtract"
            RimeKey_KP_7 -> "KP_7"
            RimeKey_KP_8 -> "KP_8"
            RimeKey_KP_9 -> "KP_9"
            RimeKey_KP_Add -> "KP_Add"
            RimeKey_KP_4 -> "KP_4"
            RimeKey_KP_5 -> "KP_5"
            RimeKey_KP_6 -> "KP_6"
            RimeKey_KP_1 -> "KP_1"
            RimeKey_KP_2 -> "KP_2"
            RimeKey_KP_3 -> "KP_3"
            RimeKey_KP_Enter -> "KP_Enter"
            RimeKey_KP_0 -> "KP_0"
            RimeKey_KP_Decimal -> "KP_Decimal"
            RimeKey_Eisu_toggle -> "Eisu_toggle"
            RimeKey_Kana_Lock -> "Kana_Lock"
            RimeKey_Hiragana_Katakana -> "Hiragana_Katakana"
            RimeKey_Zenkaku_Hankaku -> "Zenkaku_Hankaku"
            RimeKey_VoidSymbol -> "VoidSymbol"
            else -> "VoidSymbol"
        }
    }
}