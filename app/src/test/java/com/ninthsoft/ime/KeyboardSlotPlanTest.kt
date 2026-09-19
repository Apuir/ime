package com.ninthsoft.ime

import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.slot.HANDWRITING_KEYBOARD_NAME
import com.ninthsoft.ime.input.keyboard.slot.KeyboardSlotPlan
import com.ninthsoft.ime.input.keyboard.slot.SlotInputMethod
import com.ninthsoft.ime.input.keyboard.slot.SlotSwitchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyboardSlotPlan] 的回归测试。
 *
 * 这一层决定「中文槽里能选什么、选到的到底是什么键位」，错了不会崩，只会表现为
 * 「九键打不出候选」「15 键选了没反应」这类只能靠用户报障发现的问题，
 * 所以「方案里有什么 → 列表里出现什么」这条按各种组合逐一钉住。
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
        // 顺序按 SlotInputMethod 声明顺序（九键 → 26键 → 15键 → 手写），组内按方案顺序编号；
        // 这里没有声明 T15 布局的方案，15 键整项不出现
        assertEquals(
            listOf("九键1", "九键2", "26键1", "26键2", "手写"),
            items.names(),
        )
        assertEquals(
            listOf("t9_a", "t9_b"),
            items.filter { it.method == SlotInputMethod.T9 }.map { it.schemaId },
        )
    }

    @Test
    fun `同一输入方式只有一个方案时不加序号`() {
        val items = plan(listOf(schema("t9_a", "T9", "T9PinYin")))
        assertEquals(listOf("九键", "手写"), items.names())
    }

    // ---------------- 布局 + 候选类型 一起匹配 ----------------

    @Test
    fun `方案里没有 15 键布局时 15 键不出现`() {
        // 26 键与 15 键都用 PinYin，只按候选类型匹配会让方案里没有 15 键布局的设备也冒出
        // 一个「15键」选项 —— 用户看到的就是「方案里没有 15 键，却能切到 15 键」。
        val pinYin = schema("wanxiang", "Qwerty", "PinYin")
        val items = plan(listOf(pinYin))

        assertEquals(listOf("26键", "手写"), items.names())
        assertTrue(items.none { it.method == SlotInputMethod.T15 })
    }

    @Test
    fun `方案声明了 15 键布局时 15 键才出现`() {
        val t15 = schema("t15_a", "T15", "PinYin")
        val items = plan(listOf(schema("wanxiang", "Qwerty", "PinYin"), t15))

        val item = items.single { it.method == SlotInputMethod.T15 }
        assertEquals("T15", item.keyboardName)
        assertEquals("t15_a", item.schemaId)
    }

    @Test
    fun `T9PinYin 方案不能让 26 键出现`() {
        // 九键发数字，26 键发字母，候选类型不通用。
        val items = plan(listOf(schema("t9_a", "T9", "T9PinYin")))
        assertTrue(items.none { it.method == SlotInputMethod.Qwerty })
    }

    // ---------------- 手写是唯一例外 ----------------

    @Test
    fun `手写不需要方案`() {
        // 有方案时手写照样在列表里，只是排在最后。
        val items = plan(listOf(schema("qwerty_a", "Qwerty", "PinYin")))
        val handwriting = items.single { it.method == SlotInputMethod.Handwriting }
        assertNull(handwriting.schemaId)
        assertEquals(HANDWRITING_KEYBOARD_NAME, handwriting.keyboardName)
        assertEquals("手写", handwriting.displayName)
    }

    @Test
    fun `方案一个都没读出来时列表为空`() {
        // 引擎未就绪：返回空列表，调用方据此保持「先不落实键盘」。
        // 若这里还留着「手写」一项，冷启动会把它当成回落目标，直接翻出手写面板。
        assertTrue(plan(emptyList()).isEmpty())
    }

    // ---------------- 键盘支持集合 ----------------

    @Test
    fun `应用不支持某个键盘时这种输入方式不出现`() {
        val items = plan(
            schemas = listOf(schema("t9_a", "T9", "T9PinYin")),
            keyboards = allKeyboards - "T9",
        )
        assertTrue(items.none { it.method == SlotInputMethod.T9 })
        // 手写仍然在（应用支持它）
        assertTrue(items.any { it.method == SlotInputMethod.Handwriting })
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
    fun `偏好里的方案被删后回落到第一项`() {
        val items = plan(listOf(schema("qwerty_a", "Qwerty", "PinYin")))
        val resolved = KeyboardSlotPlan.resolve(items, "T9", "t9_gone")
        assertEquals(SlotInputMethod.Qwerty, resolved?.method)
    }

    @Test
    fun `没有任何项时收敛结果为空`() {
        assertNull(KeyboardSlotPlan.resolve(emptyList(), "T9", "t9_a"))
    }
}
