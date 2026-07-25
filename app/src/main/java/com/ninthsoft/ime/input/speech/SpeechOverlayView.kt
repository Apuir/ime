package com.ninthsoft.ime.input.speech

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SpeechOverlayView(
    context: Context, private val waveView: ISpeechView = ParticleWaveView(context)
) : FrameLayout(context) {

    private var bgColor: Int = Color.TRANSPARENT

    fun applyColors(backgroundColor: Int, waveformColor: Int, barColor: Int = waveformColor) {
        bgColor = backgroundColor
        waveView.updateColors(Color.TRANSPARENT, waveformColor, barColor)
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = GONE
        isClickable = true
        isFocusable = true
        addView(waveView.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnTouchListener { _, _ -> true }
    }

    fun show() {
        animate().cancel()
        visibility = VISIBLE
        setBackgroundColor(
            Color.argb(
                245, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)
            )
        )
        alpha = 0f
        animate().alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        waveView.view.apply {
            animate().cancel()
            scaleX = 1.3f
            scaleY = 1.3f
            animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        waveView.startAnim()
    }

    fun hide() {
        animate().cancel()
        animate().alpha(0f).setDuration(100).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                visibility = GONE
                waveView.stopAnim()
            }.start()
    }

    fun updateAmplitude(amplitude: Float) {
        val a = (amplitude.coerceIn(0f, 1f) * 1.6).coerceAtMost(1.0)
        val mapped = ln(1.0 + 18.0 * a) / ln(1.0 + 18.0)
        val vol = (mapped * 100).toInt().coerceIn(0, 100)
        waveView.setVolume(vol)
    }

    private fun ln(d: Double): Double = kotlin.math.ln(d)
}
