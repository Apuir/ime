package com.ninthsoft.ime

import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.slot.HANDWRITING_KEYBOARD_NAME
import com.ninthsoft.ime.input.keyboard.slot.KeyboardSlotPlan
import com.ninthsoft.ime.input.keyboard.slot.SlotInputMethod
import com.ninthsoft.ime.input.keyboard.slot.SlotSwitchItem
import com.ninthsoft.ime.input.keyboard.slot.SlotUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyboardSlotPlan] 的回归测试。
 *
 * 这一层决定「中文槽里能选什么、选到的到底是什么键位」，错了不会崩，只会表现为
 * 「九键打不出候选」「15 键选了没反应」这类只能靠用户报障发现的问题，
 * 所以按可用性的四种组合逐一钉住。
 */
class KeyboardSlotPlanTest {

    private fun schema(
        id: String,
        layout: String,
        candidateKind: String,
        kind: String = "PinYin",
        name: String = id,
    ) = EngineMessage.Schema(
        id = id,
        name = name,
        layout = layout,
        punctuation = "FullWidth",
        kind = kind,
        candidateKind = candidateKind,
    )

    /** 测试里用固定中文名，避免让纯逻辑依赖 Android 资源。 */
    private val labelOf: (SlotInputMethod) -> String = { method ->
        when (method) {
            SlotInputMethod.T9 -> "九键"
            SlotInputMethod.Qwerty -> "26键"
            SlotInputMethod.T15 -> "15键"
            SlotInputMethod.Handwriting -> "手写"
        }
    }

    private val allKeyboards = KeyboardSlotPlan.APP_SLOT_KEYBOARDS

    private fun plan(schemas: List<EngineMessage.Schema>, keyboards: Set<String> = allKeyboards) =
        KeyboardSlotPlan.plan(schemas, keyboards, labelOf)

    private fun List<SlotSwitchItem>.names() = map { it.displayName }

    // ---------------- 平铺与编号 ----------------

    @Test
    fun `同一输入方式的多个方案按方案顺序编号`() {
        val items = plan(
            listOf(
                schema("t9_a", "T9", "T9PinYin"),
                schema("qwerty_a", "Qwerty", "PinYin"),
                schema("t9_b", "T9", "T9PinYin"),
                schema("qwerty_b", "Qwerty", "PinYin"),
            )
        )
        // 顺序按 SlotInputMethod 声明顺序（九键 → 26键 → 15键 → 手写），组内按方案顺序编号
        assertEquals(
            listOf("九键1", "九键2", "26键1", "26键2", "15键1", "15键2", "手写"),
            items.names(),
        )
        assertEquals(listOf("t9_a", "t9_b"), items.filter { it.method == SlotInputMethod.T9 }.map { it.schemaId })
    }

    @Test
    fun `同一输入方式只有一个方案时不加序号`() {
        val items = plan(listOf(schema("t9_a", "T9", "T9PinYin")))
        assertEquals(listOf("九键", "26键", "15键", "手写"), items.names())
    }

    // ---------------- candidateKind 是唯一耦合点 ----------------

    @Test
    fun `15 键借 PinYin 方案`() {
        // 15 键发字母，与 26 键同用 PinYin；一个 PinYin 方案要让两个键位都可用。
        val pinYin = schema("wanxiang", "Qwerty", "PinYin")
        val items = plan(listOf(pinYin))

        val qwerty = items.single { it.method == SlotInputMethod.Qwerty }
        val t15 = items.single { it.method == SlotInputMethod.T15 }
        assertTrue("26 键应可用", qwerty.available)
        assertTrue("15 键应借用 PinYin 方案", t15.available)
        assertEquals("wanxiang", t15.schemaId)
        assertEquals("T15", t15.keyboardName)
    }

    @Test
    fun `T9PinYin 方案不能让 26 键可用`() {
        // 九键发数字，26 键发字母，候选类型不通用。
        val items = plan(listOf(schema("t9_a", "T9", "T9PinYin")))
        val qwerty = items.single { it.method == SlotInputMethod.Qwerty }
        assertFalse(qwerty.available)
        assertEquals(SlotUnavailableReason.MissingSchema, qwerty.unavailableReason)
    }

    // ---------------- 手写是唯一例外 ----------------

    @Test
    fun `手写不需要方案`() {
        // 一个方案都没有时，手写依然可用。
        val items = plan(emptyList())
        val handwriting = items.single { it.method == SlotInputMethod.Handwriting }
        assertTrue(handwriting.available)
        assertNull(handwriting.schemaId)
        assertEquals(HANDWRITING_KEYBOARD_NAME, handwriting.keyboardName)
        assertEquals("手写", handwriting.displayName)
    }

    @Test
    fun `两个都缺时不可用并给出合并原因`() {
        // T15 方案没有、键盘也不在支持集合里 —— 设置页要写「缺键盘和方案」。
        val items = plan(emptyList(), keyboards = allKeyboards - "T15")
        val t15 = items.single { it.method == SlotInputMethod.T15 }
        assertFalse(t15.available)
        assertEquals(SlotUnavailableReason.MissingKeyboardAndSchema, t15.unavailableReason)
    }

    // ---------------- 缺键盘 / 缺方案 ----------------

    @Test
    fun `键盘不支持时方案还在也算不可用`() {
        val items = plan(
            schemas = listOf(schema("t9_a", "T9", "T9PinYin")),
            keyboards = allKeyboards - "T9",
        )
        val t9 = items.single { it.method == SlotInputMethod.T9 }
        assertFalse(t9.available)
        assertEquals(SlotUnavailableReason.MissingKeyboard, t9.unavailableReason)
        // 方案本身仍然挂在项上，UI 还能显示它匹配到了哪个方案
        assertEquals("t9_a", t9.schemaId)
    }

    @Test
    fun `键盘支持但没有匹配方案时给出缺方案`() {
        val items = plan(emptyList())
        val t9 = items.single { it.method == SlotInputMethod.T9 }
        assertFalse(t9.available)
        assertEquals(SlotUnavailableReason.MissingSchema, t9.unavailableReason)
        assertNull(t9.schemaId)
    }

    // ---------------- 英文方案不进中文槽 ----------------

    @Test
    fun `英文方案不会出现在中文槽里`() {
        val items = plan(
            listOf(
                schema("wanxiang_english", "Qwerty", candidateKind = "", kind = "English"),
                schema("wanxiang", "Qwerty", "PinYin"),
            )
        )
        val qwerty = items.single { it.method == SlotInputMethod.Qwerty }
        assertEquals("wanxiang", qwerty.schemaId)
    }

    // ---------------- 收敛偏好 ----------------

    @Test
    fun `偏好里的组合仍然可用时原样返回`() {
        val t9 = schema("t9_a", "T9", "T9PinYin")
        val items = plan(listOf(t9, schema("qwerty_a", "Qwerty", "PinYin")))
        val resolved = KeyboardSlotPlan.resolve(items, "T9", "t9_a")
        assertEquals("九键", resolved?.displayName)
    }

    @Test
    fun `偏好里的方案被删后回落到第一项可用`() {
        val items = plan(listOf(schema("qwerty_a", "Qwerty", "PinYin")))
        val resolved = KeyboardSlotPlan.resolve(items, "T9", "t9_gone")
        // 九键缺方案、26 键可用 → 应落到 26 键，而不是卡在一个用不了的输入方式上
        assertEquals(SlotInputMethod.Qwerty, resolved?.method)
    }

    @Test
    fun `没有任何可用项时收敛结果为空`() {
        val items = plan(emptyList(), keyboards = emptySet())
        assertNull(KeyboardSlotPlan.resolve(items, "T9", "t9_a"))
    }
}
