package com.ninthsoft.ime.input.speech

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.withScale
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class SpectrumWaveView(context: Context) : View(context), ISpeechView {
    override val view: View get() = this

    private var bgColor = Color.TRANSPARENT
    private var waveformColor = Color.CYAN
    private var targetVolume = 0
    private var smoothVolume = 0f
    private val barCount = 9
    private val contentScale = 0.55f

    private val gaussianWeights = FloatArray(barCount) { i ->
        val center = (barCount - 1) / 2f
        val sigma = barCount / 4f
        val x = (i - center) / sigma
        exp((-x * x / 2).toDouble()).toFloat()
    }

    private val currentHeights = FloatArray(barCount) { 0.08f }
    private val targetHeights = FloatArray(barCount)

    private val noisePhase = FloatArray(barCount) {
        Random.nextFloat() * 6.28f
    }

    private val noiseSpeed = FloatArray(barCount) {
        0.015f + Random.nextFloat() * 0.025f
    }

    private val idlePhase = FloatArray(barCount) {
        Random.nextFloat() * 6.28f
    }

    private val idleSpeed = FloatArray(barCount) {
        0.01f + Random.nextFloat() * 0.02f
    }

    private val barColors = intArrayOf(
        0xFFFF6B6B.toInt(),
        0xFFFF9F43.toInt(),
        0xFFFFEAA7.toInt(),
        0xFF55EFC4.toInt(),
        0xFF74D7AE.toInt(),
        0xFF54A0FF.toInt(),
        0xFF5F27CD.toInt(),
        0xFF9B59B6.toInt(),
        0xFF3498DB.toInt()
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var globalPhase = 0f
    private var isAnimating = false

    private val frameCallback = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            updateFrame()
            postOnAnimation(this)
        }
    }

    private fun updateFrame() {
        val target = targetVolume.coerceIn(0, 100) / 100f
        smoothVolume += (target - smoothVolume) * 0.08f
        globalPhase += 0.035f

        val volume = smoothVolume.pow(1.6f)

        for (i in 0 until barCount) {
            noisePhase[i] += noiseSpeed[i]
            idlePhase[i] += idleSpeed[i]

            val base = gaussianWeights[i]
            val noise = sin(noisePhase[i].toDouble()).toFloat() * 0.12f
            val idle = sin(idlePhase[i].toDouble()).toFloat() * 0.015f
            val breathing = sin(
                (globalPhase + i * 0.25f).toDouble()
            ).toFloat() * 0.025f
            val height = 0.08f + base * volume * 0.4f + noise * volume * 0.08f + idle + breathing

            targetHeights[i] = height.coerceIn(
                0.05f, 0.6f
            )
            updateSmoothHeight(i)
        }

        invalidate()
    }

    private fun updateSmoothHeight(index: Int) {
        val speed = 0.12f + (index % 3) * 0.015f

        currentHeights[index] += (targetHeights[index] - currentHeights[index]) * speed
    }

    override fun updateColors(
        backgroundColor: Int, waveformColor: Int, barColor: Int
    ) {
        bgColor = backgroundColor
        this.waveformColor = waveformColor
        invalidate()
    }

    override fun setWaveformColor(color: Int) {
        waveformColor = color
        invalidate()
    }

    override fun setVolume(volume: Int) {
        targetVolume = volume.coerceIn(0, 100)
    }

    override fun startAnim() {
        if (!isAnimating) {
            isAnimating = true
            postOnAnimation(frameCallback)
        }
    }

    override fun stopAnim() {
        isAnimating = false
        removeCallbacks(frameCallback)
        targetVolume = 0
        smoothVolume = 0f
        currentHeights.fill(0.08f)
        invalidate()
    }

    override fun release() {
        stopAnim()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {
            startAnim()
        } else {
            stopAnim()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.drawColor(bgColor)
        canvas.withScale(
            contentScale, contentScale, width / 2f, height / 2f
        ) {
            val capsulePad = width * 0.08f
            val capsuleTop = capsulePad * 1.8f
            val capsuleBottom = height - capsulePad * 1.8f
            val capsuleRight = width - capsulePad
            val capsuleRadius = (capsuleBottom - capsuleTop) / 2f

            val capsuleAlpha = 30
            paint.color = Color.argb(
                capsuleAlpha,
                Color.red(waveformColor),
                Color.green(waveformColor),
                Color.blue(waveformColor)
            )
            drawRoundRect(
                capsulePad,
                capsuleTop,
                capsuleRight,
                capsuleBottom,
                capsuleRadius,
                capsuleRadius,
                paint
            )

            val barAreaPad = width * 0.15f
            val barWidth = (width - barAreaPad * 2) / (barCount * 2.2f)
            val spacing = barWidth * 0.6f
            val totalWidth = barCount * barWidth + (barCount - 1) * spacing
            val startX = (width - totalWidth) / 2f
            val alpha = (90 + smoothVolume * 165).toInt().coerceIn(80, 255)
            val barMaxHeight = height * 0.55f

            for (i in 0 until barCount) {
                val barHeight = barMaxHeight * currentHeights[i] / 0.6f
                val x = startX + i * (barWidth + spacing)
                val y = (height - barHeight) / 2f
                val color = barColors[i]
                paint.color = Color.argb(
                    alpha, Color.red(color), Color.green(color), Color.blue(color)
                )
                drawRoundRect(
                    x, y, x + barWidth, y + barHeight, barWidth / 2f, barWidth / 2f, paint
                )
            }
        }
    }
}