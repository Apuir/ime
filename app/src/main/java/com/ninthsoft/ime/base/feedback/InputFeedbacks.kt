package com.ninthsoft.ime.base.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
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
        private const val VIBRATION_ATTRIBUTION_TAG = "keyboard_feedback"

        /** [VibrationEffect] 支持的最大振幅，框架里对应 @hide 常量。 */
        private const val MAX_AMPLITUDE = 255

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
            pressDuration: Long = 15L,
            longPressDuration: Long = 30L,
        ) {
            if (suppressFeedback) return
            val context = view.context
            val level = KeyboardManager.Keyboard.Feedback.getVibrationLevel(context)
            if (level <= KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN) return

            val duration = if (longPress) longPressDuration else pressDuration
            if (duration == 0L) {
                // 时长为 0 时回退到系统 HapticFeedback（此时强度由系统设置决定）。
                performSystemHapticFeedback(view, longPress, keyUp)
                return
            }
            vibrate(context, level, longPress, duration)
        }

        /**
         * 试振：供设置界面预览当前强度等级使用（不受 [suppressFeedback] 影响）。
         */
        fun previewVibration(context: Context, level: Int) {
            if (level <= KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN) return
            vibrate(context, level, longPress = false, baseDuration = 15L)
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
         * 直接调用 [Vibrator] 触发振动，振幅由应用内的强度等级决定：
         * 不经过 `View.performHapticFeedback`，因此不受系统「触摸时振动」开关影响。
         */
        private fun vibrate(context: Context, level: Int, longPress: Boolean, baseDuration: Long) {
            val vibrator = getVibrator(context)
            if (vibrator == null || !vibrator.hasVibrator()) return

            val amplitude = amplitudeForLevel(level, longPress)
            val hasAmplitudeControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
            // 没有振幅控制能力的设备只能用时长来区分强弱。
            val duration = if (hasAmplitudeControl) {
                baseDuration
            } else {
                ((baseDuration + 5L) * level /
                    KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MAX).coerceAtLeast(6L)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createOneShot(
                        duration,
                        if (hasAmplitudeControl) amplitude else VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        vibrator.vibrate(effect, resolveVibrationAttributes(context))
                    } else {
                        vibrator.vibrate(effect)
                    }
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(duration)
                }

                Timber.d(
                    "haptic feedback success (level=$level, longPress=$longPress, " +
                        "duration=$duration, amplitude=$amplitude)"
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
            return try {
                // Settings.System.VIBRATE_ON，@Readable，无需权限。
                Settings.System.getInt(context.contentResolver, "vibrate_on", 1) != 0
            } catch (e: Exception) {
                true
            }
        }

        /** 等级 0..5 对应的振幅比例（0 = 关闭，5 = 最大）。 */
        private val LEVEL_AMPLITUDE_RATIO = floatArrayOf(0f, 0.15f, 0.3f, 0.5f, 0.72f, 1f)

        private fun amplitudeForLevel(level: Int, longPress: Boolean): Int {
            val index = level.coerceIn(0, LEVEL_AMPLITUDE_RATIO.lastIndex)
            if (index == 0) return 0
            val ratio = LEVEL_AMPLITUDE_RATIO[index] * if (longPress) 1.15f else 1f
            return (ratio * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE)
        }

        private fun getVibrator(context: Context): Vibrator? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val attributionContext = context.createAttributionContext(
                    VIBRATION_ATTRIBUTION_TAG
                )
                val vibratorManager = attributionContext.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as? VibratorManager
                return vibratorManager?.defaultVibrator
            }

            @Suppress("DEPRECATION") return context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as? Vibrator
        }

        fun hapticFeedback(view: View, longPress: Boolean) {
            hapticFeedback(view, longPress, keyUp = false)
        }

        fun hapticFeedback(view: View) {
            hapticFeedback(view, false, keyUp = false)
        }
    }
}