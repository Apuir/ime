package com.ninthsoft.ime.input.keyboard.slot

import com.ninthsoft.ime.base.util.PinYinUtil
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.impl.T15Keyboard
import com.ninthsoft.ime.input.keyboard.impl.T9Keyboard

/**
 * 「手写」作为一种槽内输入方式使用的键盘名。
 *
 * 它不是 `KeyboardWindowView.createKeyboard` 认识的名字（手写面板由 WindowView 直接打开），
 * 但槽的可用性判定要和别的输入方式走同一条路，所以在这里也给它一个键盘名。
 */
const val HANDWRITING_KEYBOARD_NAME = "Handwriting"

/**
 * 键盘槽：一共两个。
 *
 * - [English] 固定为 `wanxiang_english` + Qwerty，用户不可改（ascii 输入留在英文方案内部）。
 * - [Chinese] 里换的是「输入方式（布局）」，可选项由 [KeyboardSlotPlan] 推出。
 */
enum class KeyboardSlot { Chinese, English }

/**
 * 中文槽里可切换的「输入方式」：一种键盘 + 它在方案里要求的布局与候选类型。
 *
 * 声明顺序就是平铺切换列表与设置页的展示顺序。
 *
 * [requiredCandidateKind] + [requiredLayout] 是输入方式与方案的耦合点：
 * 九键发数字，必须配 `T9PinYin` + `layout: T9` 的方案；26 键与 15 键发字母（T15 自己那套键位
 * 发 q/w/e/r…），配 `PinYin` + 各自的 layout；手写不走引擎，两项都为 null。
 *
 * **只看候选类型不够**：26 键与 15 键用同一种候选类型，只按它匹配会让方案里根本没有 15 键
 * 布局的设备也冒出一个「15键」选项。
 */
enum class SlotInputMethod(
    val keyboardName: String,
    val requiredCandidateKind: String?,
    /** 方案里必须声明的 `schema/layout`；null 表示这种输入方式不需要方案（手写）。 */
    val requiredLayout: String?,
) {
    T9(T9Keyboard.NAME, PinYinUtil.CandidateKindT9, "T9"),
    Qwerty(QwertyKeyboard.NAME, PinYinUtil.CandidateKindFull, "Qwerty"),
    T15(T15Keyboard.NAME, PinYinUtil.CandidateKindFull, "T15"),

    /** 手写：唯一不需要方案的输入方式，面板直接识别上屏。 */
    Handwriting(HANDWRITING_KEYBOARD_NAME, null, null),
}

/**
 * 平铺切换列表里的一项：一个「输入方式 × 方案」组合。
 *
 * [schema] 为 null 只有一种情况：[SlotInputMethod.Handwriting]（它本来就不需要方案）。
 */
data class SlotSwitchItem(
    val method: SlotInputMethod,
    val keyboardName: String,
    val schema: EngineMessage.Schema?,
    /** 平铺列表里的显示名，如「九键1」「手写」。 */
    val displayName: String,
) {
    val schemaId: String? get() = schema?.id
    val schemaName: String get() = schema?.name.orEmpty()
}

/**
 * 「两个键盘槽」的纯计算部分：由可用方案与应用支持的键盘名，推出中文槽的平铺切换列表。
 *
 * 这一层不依赖 Android，便于 JVM 单测；偏好存取与真正切换键盘的动作在 `KeyboardStateManager`。
 */
object KeyboardSlotPlan {

    /** 英文方案的 kind；中文槽不能接受它（英文输入留在英文槽自己的方案里）。 */
    const val SCHEMA_KIND_ENGLISH = "English"

    /**
     * 应用在切换槽时实际认识的键盘名，与 `KeyboardWindowView.createKeyboard` 保持一致。
     * 「手写」由面板接管，也算一种槽键盘。
     */
    val APP_SLOT_KEYBOARDS: Set<String> = setOf(
        T9Keyboard.NAME,
        QwertyKeyboard.NAME,
        T15Keyboard.NAME,
        HANDWRITING_KEYBOARD_NAME,
    )

    /**
     * 平铺：每个输入方式一项或多项。
     *
     * 可用性规则：*应用支持这个键盘、且方案里真的声明了这个布局与候选类型，才能切换成它*。
     * 「对应方案」按 `schema/layout` + `candidateKind` 一起匹配 —— 只按候选类型匹配会让
     * 26 键与 15 键互相借方案，冒出方案里并不存在的布局。
     *
     * **用不了的输入方式整项不列**（而不是列出来灰掉）：用户要的是「方案里没有就别显示」。
     * 方案一个都没读出来时返回空列表，调用方据此保持「先不落实键盘」，否则唯一的手写项会被
     * 当成回落目标，冷启动直接翻出手写面板。
     *
     * 同一输入方式匹配到多个方案时，按 [schemas] 里的顺序编号（九键1 / 九键2）；
     * 只有一个时不加编号 —— 否则会冒出「手写1」这种没意义的序号。
     *
     * @param displayLabelOf 输入方式的基础名（九键 / 26键 / 15键 / 手写），由 UI 层本地化。
     */
    fun plan(
        schemas: List<EngineMessage.Schema>,
        supportedKeyboards: Set<String>,
        displayLabelOf: (SlotInputMethod) -> String,
    ): List<SlotSwitchItem> {
        if (schemas.isEmpty()) return emptyList()
        // 英文方案属于英文槽。candidateKind 为空本来就不该被匹配到，但 kind 是显式标记，
        // 排除它比依赖「它恰好没有 candidateKind」更稳。
        val chineseSchemas = schemas.filterNot {
            it.kind.equals(SCHEMA_KIND_ENGLISH, ignoreCase = true)
        }

        val items = ArrayList<SlotSwitchItem>()
        for (method in SlotInputMethod.entries) {
            // 应用不认识这个键盘：这种输入方式不存在，列它没有意义
            if (method.keyboardName !in supportedKeyboards) continue
            val label = displayLabelOf(method)

            if (method.requiredCandidateKind == null) {
                // 手写：唯一不需要方案的输入方式，只要应用认识这个键盘就能用。
                items += SlotSwitchItem(
                    method = method,
                    keyboardName = method.keyboardName,
                    schema = null,
                    displayName = label,
                )
                continue
            }

            val matched = chineseSchemas.filter {
                it.candidateKind == method.requiredCandidateKind &&
                    it.layout == method.requiredLayout
            }
            // 布局或候选类型对不上：方案里没有这种输入方式，整项不列
            if (matched.isEmpty()) continue

            val numbered = matched.size > 1
            matched.forEachIndexed { index, schema ->
                items += SlotSwitchItem(
                    method = method,
                    keyboardName = method.keyboardName,
                    schema = schema,
                    displayName = if (numbered) "$label${index + 1}" else label,
                )
            }
        }
        return items
    }

    /**
     * 把偏好里存的 (键盘名, 方案 id) 收敛到 [items] 里的一项。
     *
     * 存的组合已经不可用时（方案被删、键盘不再支持）回落到第一项可用的；
     * 没有可用项时返回 null，由调用方决定怎么办。
     */
    fun resolve(
        items: List<SlotSwitchItem>,
        keyboardName: String?,
        schemaId: String?,
    ): SlotSwitchItem? = items.find {
        it.keyboardName == keyboardName && it.schemaId == schemaId
    } ?: items.firstOrNull()
}
