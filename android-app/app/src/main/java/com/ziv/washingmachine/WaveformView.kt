package com.ziv.washingmachine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import androidx.core.graphics.toColorInt

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
        alpha = 100
    }

    private var audioData: ShortArray = ShortArray(0)
    private var maxAmplitude: Short = 1
    private var isListening = false
    private var isPulsing = false
    
    // Reference waveform data
    private var referenceAudioData: ShortArray = ShortArray(0)
    private var referenceMaxAmplitude: Short = 1
    private var showReferenceWaveform = false

    // Animation properties - optimized for performance
    private var animationOffset = 0f
    private val animationSpeed = 1f // Reduced speed
    private var pulseScale = 1f
    private var lastInvalidateTime = 0L
    private val minInvalidateInterval = 50L // Minimum 50ms between invalidates (20 FPS max)
    
    // Animation runnables for cleanup
    private var animationRunnable: Runnable? = null
    private var pulseRunnable: Runnable? = null

    fun updateAudioData(data: ShortArray) {
        audioData = data
        maxAmplitude = if (data.isNotEmpty()) {
            data.maxOfOrNull { abs(it.toInt()) }?.toShort() ?: 1
        } else {
            1
        }
        // Only invalidate if enough time has passed
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastInvalidateTime >= minInvalidateInterval) {
            invalidate()
            lastInvalidateTime = currentTime
        }
    }

    fun setListening(listening: Boolean) {
        isListening = listening
        if (listening) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    fun setReferenceWaveform(data: ShortArray) {
        referenceAudioData = data
        referenceMaxAmplitude = if (data.isNotEmpty()) {
            data.maxOfOrNull { abs(it.toInt()) }?.toShort() ?: 1
        } else {
            1
        }
        showReferenceWaveform = true
        invalidate()
    }

    fun clearReferenceWaveform() {
        showReferenceWaveform = false
        referenceAudioData = ShortArray(0)
        invalidate()
    }

    private fun startAnimation() {
        // Use a more efficient animation approach
        animationRunnable = object : Runnable {
            override fun run() {
                if (isListening) {
                    animationOffset += animationSpeed
                    // Only invalidate if enough time has passed
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastInvalidateTime >= minInvalidateInterval) {
                        invalidate()
                        lastInvalidateTime = currentTime
                    }
                    postDelayed(this, 50) // Reduced to 50ms (20 FPS)
                }
            }
        }
        post(animationRunnable)
    }

    private fun stopAnimation() {
        animationRunnable?.let { removeCallbacks(it) }
        animationRunnable = null
        // Animation will stop when isListening becomes false
    }

    fun startPulseAnimation() {
        isPulsing = true
        startPulse()
    }

    fun stopPulseAnimation() {
        isPulsing = false
        pulseRunnable?.let { removeCallbacks(it) }
        pulseRunnable = null
    }

    private fun startPulse() {
        pulseRunnable = object : Runnable {
            override fun run() {
                if (isPulsing) {
                    pulseScale = 1f + 0.05f * kotlin.math.sin(animationOffset * 0.05f) // Reduced amplitude
                    // Only invalidate if enough time has passed
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastInvalidateTime >= minInvalidateInterval) {
                        invalidate()
                        lastInvalidateTime = currentTime
                    }
                    postDelayed(this, 50) // Reduced to 50ms (20 FPS)
                }
            }
        }
        post(pulseRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Enable hardware acceleration for this view
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean up animations when view is detached
        isListening = false
        isPulsing = false
        // Clear any pending callbacks
        removeCallbacks(null)
        animationRunnable?.let { removeCallbacks(it) }
        pulseRunnable?.let { removeCallbacks(it) }
        animationRunnable = null
        pulseRunnable = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Skip drawing if view is not properly sized
        if (width <= 0 || height <= 0) return

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Apply pulse scale if pulsing
        if (isPulsing) {
            canvas.save()
            canvas.scale(pulseScale, pulseScale, width / 2, height / 2)
        }

        // Draw background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        // Draw grid lines
        drawGrid(canvas, width, height)

        // Draw reference waveform if available
        if (showReferenceWaveform && referenceAudioData.isNotEmpty()) {
            drawReferenceWaveform(canvas, width, height, centerY)
        }

        // Draw waveform
        if (audioData.isNotEmpty()) {
            drawWaveform(canvas, width, height, centerY)
        } else {
            // Draw placeholder when no audio data
            drawPlaceholder(canvas, width, height, centerY)
        }

        // Restore canvas if pulsing
        if (isPulsing) {
            canvas.restore()
        }
    }

    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        // Simplified grid - fewer lines to reduce drawing complexity
        // Vertical grid lines (reduced from 11 to 6)
        val verticalSpacing = width / 5
        for (i in 0..5) {
            val x = i * verticalSpacing
            canvas.drawLine(x, 0f, x, height, gridPaint)
        }

        // Horizontal grid lines (reduced from 5 to 3)
        val horizontalSpacing = height / 2
        for (i in 0..2) {
            val y = i * horizontalSpacing
            canvas.drawLine(0f, y, width, y, gridPaint)
        }

        // Center line
        val centerLinePaint = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 2f
            alpha = 150
        }
        canvas.drawLine(0f, height / 2, width, height / 2, centerLinePaint)
    }

    private fun drawWaveform(canvas: Canvas, width: Float, height: Float, centerY: Float) {
        if (audioData.isEmpty()) return

        val barWidth = width / audioData.size
        val maxBarHeight = height * 0.8f / 2 // Use 80% of height, split for top/bottom

        // Limit the number of bars to prevent excessive drawing
        val maxBars = minOf(audioData.size, 100) // Maximum 100 bars
        val step = if (audioData.size > maxBars) audioData.size / maxBars else 1

        for (i in 0 until maxBars) {
            val dataIndex = i * step
            if (dataIndex >= audioData.size) break
            
            val amplitude = audioData[dataIndex].toFloat()
            val normalizedAmplitude = if (maxAmplitude > 0) {
                abs(amplitude) / maxAmplitude
            } else {
                0f
            }

            val barHeight = normalizedAmplitude * maxBarHeight
            val x = i * barWidth

            // Draw bar from center
            val topY = centerY - barHeight
            val bottomY = centerY + barHeight

            // Use different colors based on amplitude
            val color = when {
                normalizedAmplitude > 0.8f -> Color.RED
                normalizedAmplitude > 0.6f -> "#FF8C00".toColorInt() // Dark Orange
                normalizedAmplitude > 0.4f -> Color.YELLOW
                normalizedAmplitude > 0.2f -> Color.GREEN
                else -> Color.GRAY
            }

            paint.color = color
            canvas.drawLine(x, topY, x, bottomY, paint)
        }
    }

    private fun drawPlaceholder(canvas: Canvas, width: Float, height: Float, centerY: Float) {
        val placeholderPaint = Paint().apply {
            color = Color.GRAY
            textSize = 16f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("No Audio Data", width / 2, centerY, placeholderPaint)
    }

    private fun drawReferenceWaveform(canvas: Canvas, width: Float, height: Float, centerY: Float) {
        val referencePaint = Paint().apply {
            color = Color.parseColor("#007BFF") // Blue for reference
            strokeWidth = 1f
            alpha = 80 // Semi-transparent
            isAntiAlias = true
        }

        if (referenceAudioData.isEmpty()) return

        val barWidth = width / referenceAudioData.size
        val maxBarHeight = height * 0.3f / 2 // Use 30% of height for reference, split for top/bottom

        // Limit the number of bars to prevent excessive drawing
        val maxBars = minOf(referenceAudioData.size, 50) // Maximum 50 bars for reference
        val step = if (referenceAudioData.size > maxBars) referenceAudioData.size / maxBars else 1

        for (i in 0 until maxBars) {
            val dataIndex = i * step
            if (dataIndex >= referenceAudioData.size) break
            
            val amplitude = referenceAudioData[dataIndex].toFloat()
            val normalizedAmplitude = if (referenceMaxAmplitude > 0) {
                abs(amplitude) / referenceMaxAmplitude
            } else {
                0f
            }

            val barHeight = normalizedAmplitude * maxBarHeight
            val x = i * barWidth

            // Draw bar from center (top half of the view)
            val topY = centerY - barHeight - height * 0.1f // Offset to top half
            val bottomY = centerY + barHeight - height * 0.1f

            referencePaint.color = Color.parseColor("#007BFF") // Blue for reference
            canvas.drawLine(x, topY, x, bottomY, referencePaint)
        }

        // Draw reference label
        val labelPaint = Paint().apply {
            color = Color.parseColor("#007BFF")
            textSize = 12f
            textAlign = Paint.Align.LEFT
            alpha = 150
        }
        val labelText = "Reference Pattern (M4A)"
        val labelY = 20f // Stack labels vertically
        canvas.drawText(labelText, 10f, labelY, labelPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = 200 // Default height in dp
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(widthMeasureSpec, height)
    }
} 