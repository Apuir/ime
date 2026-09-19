package com.ninthsoft.ime.data.manager

import android.content.Context
import androidx.core.content.edit

object CandidateManager {
    const val PREFS_NAME = "candidate_settings"
    const val KEY_TRADITIONAL_ENABLED = "traditional_chinese_enabled"
    const val KEY_EMOJI_ENABLED = "emoji_enabled"
    const val KEY_ASCII_MODE_ENABLED = "ascii_mode_enabled"
    const val KEY_PREDICTION_ENABLED = "prediction_enabled"
    const val KEY_RERANK_ENABLED = "rerank_enabled"
    const val KEY_SHOW_INDEX = "show_index"
    const val KEY_SHOW_COMMENT = "show_comment"
    const val KEY_BORDER = "show_border"

    /** 上屏模式：输入时不在输入框显示任何内容（默认，等同旧行为）。 */
    const val PREVIEW_MODE_NONE = 0

    /** 上屏模式：把原始输入（如 ni'hao）以 composing 形式显示在输入框。 */
    const val PREVIEW_MODE_RAW = 1

    /** 上屏模式：把当前第一个候选词以 composing 形式显示在输入框。 */
    const val PREVIEW_MODE_FIRST_CANDIDATE = 2

    const val KEY_PREVIEW_MODE = "preview_mode"
    const val KEY_COMMIT_PREVIEW_ON_SWITCH = "commit_preview_on_switch"

    fun isPredictionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREDICTION_ENABLED, true)
    }

    fun setPredictionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PREDICTION_ENABLED, enabled)
        }
    }

    fun isTraditionalChineseEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRADITIONAL_ENABLED, false)

    fun setTraditionalChineseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_TRADITIONAL_ENABLED, enabled)
        }
    }

    fun isEmojiEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EMOJI_ENABLED, false)

    fun setEmojiEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_EMOJI_ENABLED, enabled)
        }
    }

    fun isAsciiModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASCII_MODE_ENABLED, true)

    fun setAsciiModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ASCII_MODE_ENABLED, enabled)
        }
    }

    fun isRerankEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RERANK_ENABLED, true)
    }

    fun setRerankEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_RERANK_ENABLED, enabled)
        }
    }

    fun isShowIndex(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_INDEX, true)
    }

    fun setShowIndex(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_INDEX, enabled)
        }
    }

    fun isShowComment(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_COMMENT, false)
    }

    fun setShowComment(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_COMMENT, enabled)
        }
    }

    fun isBorderEnabled(context: Context): Boolean {
        return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BORDER, false)
    }

    fun setBorderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_BORDER, !enabled)
        }
    }

    /** 当前的上屏模式，取值见 [PREVIEW_MODE_NONE] / [PREVIEW_MODE_RAW] / [PREVIEW_MODE_FIRST_CANDIDATE]。 */
    fun getPreviewMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREVIEW_MODE, PREVIEW_MODE_NONE)
    }

    fun setPreviewMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_PREVIEW_MODE, mode)
        }
    }

    /**
     * 切换输入方案 / 收起键盘时，是否把已经上屏（预览）的内容正式留在输入框。
     * 关闭时预览内容会被清除。
     */
    fun isCommitPreviewOnSwitch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMMIT_PREVIEW_ON_SWITCH, false)
    }

    fun setCommitPreviewOnSwitch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_COMMIT_PREVIEW_ON_SWITCH, enabled)
        }
    }
}
