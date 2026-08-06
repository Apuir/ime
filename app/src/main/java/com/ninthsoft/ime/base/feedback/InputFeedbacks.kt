package com.ninthsoft.ime.base.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardManager
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
         * 初始化 SoundPool（使用 Application Context 防止内存泄漏，并加入线程锁）
         */
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

                    // 使用 applicationContext 避免持有外部 Activity/Service 引用
                    val appContext = context.applicationContext
                    popSoundId = pool.load(appContext, R.raw.pop, 1)
                    soundPool = pool

                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize SoundPool")
                }
            }
        }

        /**
         * 播放音效（高频安全，移除冗余的 MediaPlayer 降级，依靠 SoundPool 自身并发）
         */
        fun soundEffect(context: Context, effect: SoundEffect) {
            if (!KeyboardManager.Keyboard.Feedback.getSoundEnabled(context)) return
            when (effect) {
                SoundEffect.Standard -> {
                    if (isPopLoaded && popSoundId != 0) {
                        soundPool?.play(popSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                    }
                }
            }
        }

        /**
         * 建议在输入法销毁时调用此方法释放资源
         */
        fun release() {
            synchronized(lock) {
                soundPool?.release()
                soundPool = null
                popSoundId = 0
                isPopLoaded = false
            }
        }

        /**
         * 触觉反馈代码保持不变
         */
        fun hapticFeedback(
            view: View,
            longPress: Boolean = false,
            keyUp: Boolean = false,
            pressDuration: Long = 20L,
            longPressDuration: Long = 40L,
            pressAmplitude: Int = 255,
            longPressAmplitude: Int = 255
        ) {
            val context = view.context

            val vibrationEnabled = KeyboardManager.Keyboard.Feedback.getVibrationEnabled(context)
            if (!vibrationEnabled) return

            val contextVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (contextVibrator == null || !contextVibrator.hasVibrator()) return

            val duration = if (longPress) longPressDuration else pressDuration
            val amplitude = if (longPress) longPressAmplitude else pressAmplitude
            val hasAmplitudeControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && contextVibrator.hasAmplitudeControl()

            try {
                if (duration != 0L) {
                    if (hasAmplitudeControl && amplitude != 0) {
                        contextVibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        contextVibrator.vibrate(
                            VibrationEffect.createOneShot(
                                duration, VibrationEffect.DEFAULT_AMPLITUDE
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION") contextVibrator.vibrate(duration)
                    }
                } else {
                    val hfc: Int = if (longPress) {
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
                }
                Timber.d("haptic feedback success (longPress=$longPress, duration=$duration)")
            } catch (e: Exception) {
                Timber.e(e, "haptic feedback failed")
            }
        }

        fun hapticFeedback(view: View, longPress: Boolean) {
            hapticFeedback(view, longPress, keyUp = false)
        }

        fun hapticFeedback(view: View) {
            hapticFeedback(view, false, keyUp = false)
        }
    }
}