package com.ninthsoft.ime.input.handwriting

import android.content.Context
import androidx.core.content.edit
import com.ninthsoft.ime.data.manager.KeyboardManager

/**
 * 手写相关的偏好读写。
 *
 * 沿用 `KeyboardManager.PREFS_NAME`（`keyboard_settings`）而不是自己开一份：
 * 该文件已被 `ImeInputMethodService` 的偏好监听覆盖，任何写入都会转发到
 * `KeyboardWindowView.onConfigChanged`，因此「改完设置立刻生效」不需要额外注册监听。
 * 新增的 key 也要在那边补分支（见该类注释末尾）。
 */
object HandwritingManager {

    /**
     * 手写引擎：自动 / Google / 本地。见 [HwEngineMode]。
     *
     * 对外公开是因为 `KeyboardWindowView.onConfigChanged` 要按这个 key 挂分支，
     * 让设置页改动立刻生效（`keyboard_settings` 已被服务的偏好监听覆盖）。
     */
    const val KEY_ENGINE_MODE = "handwriting.engine_mode"

    /**
     * 抬笔后是否自动识别。
     *
     * 默认开。关掉后由用户点「识别」按钮触发 —— 给「一笔一笔慢慢写、想确认笔顺再识别」
     * 的用法留出口，也让探针式的单笔测试不被 debounce 干扰。
     */
    private const val KEY_RECOGNIZE_ON_LIFT = "handwriting.recognize_on_lift"

    /**
     * 抬笔后停手多久才识别（毫秒）。
     *
     * 设置页滑杆按 0.1 秒一档给值，范围 [RECOGNIZE_DELAY_MS_MIN]..[RECOGNIZE_DELAY_MS_MAX]：
     * 低于 0.2s 会把一个字拆成几段去识别，高于 2s 又会觉得「写完了没反应」。
     * 读写都夹取 —— 手改、迁移或未来的默认值变更留下的越界值，不该让面板陷入「一直不识别」。
     */
    const val KEY_RECOGNIZE_DELAY_MS = "handwriting.recognize_delay_ms"
    const val RECOGNIZE_DELAY_MS_MIN = 200
    const val RECOGNIZE_DELAY_MS_MAX = 2000
    const val RECOGNIZE_DELAY_MS_DEFAULT = 700

    /**
     * 手写范围：false = 只在键盘区域内写（半屏），true = 整个屏幕都能写。
     *
     * 要**记住**（下次进手写仍是上次选的），所以落盘而不是只放内存。
     * 面板上的「半/全」键切换的就是它。
     */
    const val KEY_FULL_SCREEN = "handwriting.full_screen"

    /**
     * 「全屏手写」的落地方式 —— 真机兜底开关。
     *
     * - [HwFullScreenImpl.OVERLAY]：把 IME 窗口铺满整屏（窗口高度给 `MATCH_PARENT`）、
     *   背景透明、可触摸区域给整窗，笔迹直接盖在应用内容上（默认）。
     * - [HwFullScreenImpl.GROW]：完全不动窗口，只把键盘内容临时长高到
     *   [FULL_SCREEN_GROW_PERCENT]，面板形态与半屏一致，只是能写的范围小一圈。
     *
     * 若 OVERLAY 在某些 ROM 上不稳定（窗口不变透明、触摸区域被裁、转屏后卡住），
     * **把这一行改成 [HwFullScreenImpl.GROW] 即可整体退化**，不需要动其它任何代码。
     */
    val FULL_SCREEN_IMPL = HwFullScreenImpl.OVERLAY

    /** GROW 兜底模式下键盘内容临时长到的高度（% 屏高）。 */
    const val FULL_SCREEN_GROW_PERCENT = 62

    /**
     * Google 引擎可用性探测结果的缓存。
     *
     * 取值语义（**这个区别很重要，不要压成 Boolean**）：
     * - `null` 未探测 —— 下次打开面板要走完整的三态探测；
     * - `false` **硬结论**不可用（例如没有 GMS）—— 这类结论不会自愈，可以直接跳过探测；
     * - `true` 确认可用（模型已下载**且自检通过**）。
     *
     * 注意「下载失败/超时」**不写入这里**：它属于临时故障，网络恢复后本该重试；
     * 写进去会让用户永远用不上 Google。这类失败由 [HandwritingEngineHolder] 的
     * 进程内标志兜住，避免同一次使用中反复触发下载。
     */
    private const val KEY_GOOGLE_USABLE = "handwriting.google_usable"

    /** 未探测。 */
    private const val PROBE_UNKNOWN = -1

    /** 确认不可用。 */
    private const val PROBE_UNUSABLE = 0

    /** 确认可用。 */
    private const val PROBE_USABLE = 1

    fun engineMode(context: Context): HwEngineMode {
        val raw = prefs(context).getInt(KEY_ENGINE_MODE, HwEngineMode.AUTO.ordinal)
        return HwEngineMode.fromValue(raw)
    }

    fun setEngineMode(context: Context, mode: HwEngineMode) {
        prefs(context).edit { putInt(KEY_ENGINE_MODE, mode.ordinal) }
    }

    /** 抬笔后是否自动识别，默认开。 */
    fun recognizeOnLift(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECOGNIZE_ON_LIFT, true)

    fun setRecognizeOnLift(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_RECOGNIZE_ON_LIFT, enabled) }
    }

    /** 停手识别时长（毫秒），读写都夹取到 [RECOGNIZE_DELAY_MS_MIN]..[RECOGNIZE_DELAY_MS_MAX]。 */
    fun recognizeDelayMs(context: Context): Int =
        prefs(context).getInt(KEY_RECOGNIZE_DELAY_MS, RECOGNIZE_DELAY_MS_DEFAULT)
            .coerceIn(RECOGNIZE_DELAY_MS_MIN, RECOGNIZE_DELAY_MS_MAX)

    fun setRecognizeDelayMs(context: Context, ms: Int) {
        prefs(context).edit {
            putInt(
                KEY_RECOGNIZE_DELAY_MS,
                ms.coerceIn(RECOGNIZE_DELAY_MS_MIN, RECOGNIZE_DELAY_MS_MAX),
            )
        }
    }

    /** 是否整屏手写，默认否（半屏：只在键盘区域内写）。 */
    fun fullScreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_SCREEN, false)

    fun setFullScreen(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FULL_SCREEN, enabled) }
    }

    /** Google 引擎可用性的缓存；`null` 表示未探测。语义见 [KEY_GOOGLE_USABLE]。 */
    fun googleUsable(context: Context): Boolean? =
        when (prefs(context).getInt(KEY_GOOGLE_USABLE, PROBE_UNKNOWN)) {
            PROBE_USABLE -> true
            PROBE_UNUSABLE -> false
            else -> null
        }

    /** 写入探测结果；传 `null` 表示清除缓存（设置页的「重新检测」走这条）。 */
    fun setGoogleUsable(context: Context, usable: Boolean?) {
        val value = when (usable) {
            true -> PROBE_USABLE
            false -> PROBE_UNUSABLE
            null -> PROBE_UNKNOWN
        }
        prefs(context).edit { putInt(KEY_GOOGLE_USABLE, value) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(KeyboardManager.PREFS_NAME, Context.MODE_PRIVATE)
}

/** 全屏手写的两种落地方式。见 [HandwritingManager.FULL_SCREEN_IMPL]。 */
enum class HwFullScreenImpl {
    OVERLAY,
    GROW,
}
