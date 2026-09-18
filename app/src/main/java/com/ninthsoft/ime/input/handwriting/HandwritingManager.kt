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
