package com.ninthsoft.ime.base.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardManager
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import timber.log.Timber

class InputFeedbacks private constructor() {
    enum class SoundEffect {
        Standard,
    }

    companion object {
        private var soundPool: SoundPool? = null
        private var popSoundId: Int = 0
        private var isPopLoaded = false
        private val lock = Any()
        /**
         * 振动归因标签，必须与 AndroidManifest 里 `<attribution android:tag=...>`
         * （字符串资源 `vibration_attribution_tag`）保持一致。
         */
        private const val VIBRATION_ATTRIBUTION_TAG = "keyboard_feedback"

        /** [VibrationEffect] 支持的最大振幅，框架里对应 @hide 常量。 */
        private const val MAX_AMPLITUDE = 255

        /**
         * 「自定义强度」模式下退回原始波形时的单次振动时长。
         *
         * 刻意取很短的值做出「一瞬间」的点击感；只要设备支持 primitive 就完全不看它，
         * 因为那条通路的时长由厂商调校的 primitive 自己决定。
         */
        private const val PRESS_DURATION_MS = 8L

        /** 长按触发时的振动时长，略长于普通点击以便区分（仅在退回原始波形的设备上生效）。 */
        private const val LONG_PRESS_DURATION_MS = 18L

        /**
         * [getVibrator] 的结果缓存：按键路径上不必每次重新 `createAttributionContext` +
         * `getSystemService`。默认振动器与 Context 无关，缓存一份即可。
         */
        @Volatile
        private var cachedVibrator: Vibrator? = null

        /**
         * `vibrate_on` 的缓存值。
         *
         * 这个开关以前是每次按键都通过 `Settings.System.getInt` 去读的 —— 那是一次
         * ContentResolver 查询（binder 到 system_server），排在触发振动之前，会直接把
         * 起振时机推后，手感上就是「不跟手 / 拖沓」。这里缓存 10 秒。
         */
        @Volatile
        private var cachedSystemVibrateEnabled: Boolean? = null

        @Volatile
        private var cachedSystemVibrateEnabledAt: Long = 0L
        private const val SYSTEM_VIBRATE_CACHE_MS = 10_000L

        /**
         * 临时全局抑制按键反馈。主题编辑器里嵌入真实键盘作预览时置为 true，
         * 避免在设置界面按键还震动/发声（编辑器退出时恢复）。
         */
        @Volatile
        var suppressFeedback: Boolean = false

        fun initSoundPool(context: Context) {
            if (soundPool != null && isPopLoaded) return
            synchronized(lock) {
                if (soundPool != null) return
                try {
                    val audioAttributes =
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    val pool =
                        SoundPool.Builder().setMaxStreams(5).setAudioAttributes(audioAttributes)
                            .build()
                    pool.setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0 && sampleId == popSoundId) {
                            isPopLoaded = true
                            Timber.d("Pop sound loaded successfully.")
                        } else {
                            Timber.e("Pop sound load failed with status: $status")
                        }
                    }
                    val appContext = context.applicationContext
                    popSoundId = pool.load(appContext, R.raw.pop, 1)
                    soundPool = pool
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize SoundPool")
                }
            }
        }

        fun soundEffect(context: Context, effect: SoundEffect) {
            if (suppressFeedback) return
            if (!KeyboardManager.Keyboard.Feedback.getSoundEnabled(context)) return
            when (effect) {
                SoundEffect.Standard -> {
                    if (isPopLoaded && popSoundId != 0) {
                        soundPool?.play(popSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                    }
                }
            }
        }

        fun release() {
            synchronized(lock) {
                soundPool?.release()
                soundPool = null
                popSoundId = 0
                isPopLoaded = false
            }
        }

        fun hapticFeedback(
            view: View,
            longPress: Boolean = false,
            keyUp: Boolean = false,
            pressDuration: Long = PRESS_DURATION_MS,
            longPressDuration: Long = LONG_PRESS_DURATION_MS,
        ) {
            if (suppressFeedback) return
            val context = view.context
            val level = KeyboardManager.Keyboard.Feedback.getVibrationLevel(context)
            if (level <= KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN) return

            // 系统触感模式：把常量原样交给系统，走厂商调校过的预置效果（最干脆）。
            if (KeyboardManager.Keyboard.Feedback.getVibrationEffect(context) ==
                KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_SYSTEM
            ) {
                performSystemHapticFeedback(view, longPress, keyUp)
                return
            }

            val duration = if (longPress) longPressDuration else pressDuration
            if (duration == 0L) {
                // 时长为 0 时回退到系统 HapticFeedback（此时强度由系统设置决定）。
                performSystemHapticFeedback(view, longPress, keyUp)
                return
            }
            vibrate(context, level, longPress, duration)
        }

        /**
         * 试振：供设置界面预览当前强度等级 / 效果使用（不受 [suppressFeedback] 影响）。
         *
         * 需要 [View] 是因为「系统触感」模式只能通过 `View.performHapticFeedback`
         * 才能拿到厂商预置的那条效果。
         */
        fun previewVibration(view: View, level: Int) {
            val context = view.context
            if (KeyboardManager.Keyboard.Feedback.getVibrationEffect(context) ==
                KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_SYSTEM
            ) {
                performSystemHapticFeedback(view, longPress = false, keyUp = false)
                return
            }
            if (level <= KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN) return
            vibrate(context, level, longPress = false, baseDuration = PRESS_DURATION_MS)
        }

        private fun performSystemHapticFeedback(view: View, longPress: Boolean, keyUp: Boolean) {
            try {
                val hfc = if (longPress) {
                    HapticFeedbackConstants.LONG_PRESS
                } else if (keyUp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    HapticFeedbackConstants.KEYBOARD_RELEASE
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    view.performHapticFeedback(hfc, flags)
                } else {
                    @Suppress("DEPRECATION") view.performHapticFeedback(hfc)
                }
            } catch (e: Exception) {
                Timber.e(e, "system haptic feedback failed")
            }
        }

        /**
         * 直接调用 [Vibrator] 触发振动，强度由应用内的等级决定：
         * 不经过 `View.performHapticFeedback`，因此不受系统「触摸时振动」开关影响。
         *
         * Android 11 起优先发送厂商调校过的 primitive —— 平台自己在
         * `HapticFeedbackVibrationProvider` 里给 `HapticFeedbackConstants.KEYBOARD_TAP`
         * （软键盘按键）用的就是 `PRIMITIVE_CLICK`，官方文档说 primitive 由设备厂商实现，
         * 提供「干脆、短促」的振感。只发原始波形（`createOneShot`）会绕开厂商的包络调校，
         * 在部分设备（尤其小米这类自带振动调校的 ROM）上会显得发闷、拖沓。
         *
         * 官方文档同时说明「Android 不会为不支持的 primitive 提供兜底」，所以必须先用
         * [Vibrator.areAllPrimitivesSupported] 探测，不支持时退回原始波形 + 振幅。
         */
        private fun vibrate(context: Context, level: Int, longPress: Boolean, baseDuration: Long) {
            val vibrator = getVibrator(context)
            if (vibrator == null || !vibrator.hasVibrator()) return

            val ratio = intensityRatioForLevel(level, longPress)
            if (ratio <= 0f) return

            // Android 8.0 以下既没有振幅控制也没有 primitive，只能用时长区分强弱。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                try {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(fallbackDurationForLevel(level, baseDuration))
                    Timber.d("haptic feedback success (level=$level, duration-only)")
                } catch (e: Exception) {
                    Timber.e(e, "haptic feedback failed")
                }
                return
            }

            val effect: VibrationEffect
            val detail: String
            val primitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                )
            ) {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            } else {
                null
            }
            if (primitive != null) {
                effect = VibrationEffect.startComposition()
                    .addPrimitive(primitive, ratio)
                    .compose()
                detail = "primitive=$primitive scale=$ratio"
            } else {
                // 退回原始波形：有振幅控制就用振幅区分强弱，否则只能用时长。
                val hasAmplitudeControl = vibrator.hasAmplitudeControl()
                val duration = if (hasAmplitudeControl) {
                    baseDuration
                } else {
                    fallbackDurationForLevel(level, baseDuration)
                }
                val amplitude = if (hasAmplitudeControl) {
                    (ratio * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE)
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                }
                effect = VibrationEffect.createOneShot(duration, amplitude)
                detail = "duration=$duration amplitude=$amplitude"
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, resolveVibrationAttributes(context))
                } else {
                    vibrator.vibrate(effect)
                }
                Timber.d(
                    "haptic feedback success (level=$level, longPress=$longPress, $detail)"
                )
            } catch (e: Exception) {
                Timber.e(e, "haptic feedback failed")
            }
        }

        /**
         * 选择振动通道（Android 13+）。
         *
         * - 默认「忽略系统设置」：使用媒体通道，不受系统「触摸时振动（触感）」开关影响；
         *   若系统振动总开关（`vibrate_on`）也被关闭，则退回无障碍通道 —— 在 AOSP 中它是
         *   唯一允许越过总开关的非特权用法（`FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF`
         *   对普通应用无效）。
         * - 关闭「忽略系统设置」：使用标准触摸反馈通道，完全跟随系统设置。
         *
         * 注：AOSP 为输入法专门加了 `USAGE_IME_FEEDBACK` 通道（自带
         * `KEYBOARD_VIBRATION_ENABLED` 开关，且**不**受「触摸时振动」影响），
         * 是这里最贴切的用法，但它目前还是 `@FlaggedApi`、没进公开 SDK，
         * 因此暂时仍走媒体通道。
         */
        private fun resolveVibrationAttributes(context: Context): VibrationAttributes {
            val ignoreSystem = KeyboardManager.Keyboard.Feedback.getIgnoreSystemSettings(context)
            val usage = when {
                !ignoreSystem -> VibrationAttributes.USAGE_TOUCH
                isSystemVibrateEnabled(context) -> VibrationAttributes.USAGE_MEDIA
                else -> VibrationAttributes.USAGE_ACCESSIBILITY
            }
            return VibrationAttributes.createForUsage(usage)
        }

        private fun isSystemVibrateEnabled(context: Context): Boolean {
            val now = SystemClock.elapsedRealtime()
            cachedSystemVibrateEnabled?.let {
                if (now - cachedSystemVibrateEnabledAt < SYSTEM_VIBRATE_CACHE_MS) return it
            }
            val enabled = try {
                // Settings.System.VIBRATE_ON，@Readable，无需权限。
                Settings.System.getInt(context.contentResolver, "vibrate_on", 1) != 0
            } catch (e: Exception) {
                true
            }
            cachedSystemVibrateEnabled = enabled
            cachedSystemVibrateEnabledAt = now
            return enabled
        }

        /**
         * 等级 0..10 对应的强度比例（0 = 关闭，10 = 最大）。
         *
         * 同一组比例同时驱动两条通路 —— primitive 的 `scale` 与退回原始波形时的振幅 ——
         * 因此换通路时手感不会跳变。
         *
         * 前 5 档是扩档时新增的更弱档位（最弱约为最大强度的 1.5%，在手上几乎只是「碰一下」）；
         * 6..10 与早期五档版本的 1..5 完全一致，因此存量用户升级后手感不变，无需重新调。
         */
        private val LEVEL_INTENSITY_RATIO = floatArrayOf(
            0f, 0.015f, 0.03f, 0.05f, 0.08f, 0.12f, 0.15f, 0.3f, 0.5f, 0.72f, 1f,
        )

        private fun intensityRatioForLevel(level: Int, longPress: Boolean): Float {
            val index = level.coerceIn(0, LEVEL_INTENSITY_RATIO.lastIndex)
            if (index == 0) return 0f
            val ratio = LEVEL_INTENSITY_RATIO[index] * if (longPress) 1.15f else 1f
            return ratio.coerceIn(0f, 1f)
        }

        /**
         * 没有振幅控制能力的设备上，只能靠时长区分强弱：最弱档约 0.6×，最强档约 1.6×。
         */
        private fun fallbackDurationForLevel(level: Int, baseDuration: Long): Long {
            val min = KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN
            val max = KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MAX
            val ratio = (level - min).toFloat() / (max - min).coerceAtLeast(1)
            return (baseDuration * (0.6f + ratio)).roundToLong().coerceAtLeast(5L)
        }

        private fun getVibrator(context: Context): Vibrator? {
            cachedVibrator?.let { return it }
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val attributionContext = context.createAttributionContext(
                    VIBRATION_ATTRIBUTION_TAG
                )
                val vibratorManager = attributionContext.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(
                    Context.VIBRATOR_SERVICE
                ) as? Vibrator
            }
            cachedVibrator = vibrator
            return vibrator
        }

        fun hapticFeedback(view: View, longPress: Boolean) {
            hapticFeedback(view, longPress, keyUp = false)
        }

        fun hapticFeedback(view: View) {
            hapticFeedback(view, false, keyUp = false)
        }
    }
}