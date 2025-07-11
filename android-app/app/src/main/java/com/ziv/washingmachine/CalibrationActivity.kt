package com.ziv.washingmachine

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.absoluteValue

class CalibrationActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var amplitudeText: TextView
    private lateinit var frequencyText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button
    private lateinit var saveButton: Button
    
    // Calibration parameters (editable)
    private lateinit var toleranceSlider: SeekBar
    private lateinit var toleranceText: TextView
    private lateinit var minMatchesSlider: SeekBar
    private lateinit var minMatchesText: TextView
    private lateinit var amplitudeThresholdSlider: SeekBar
    private lateinit var amplitudeThresholdText: TextView
    
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 44100
    private val windowSize = 2048
    
    // Calibration values
    private var frequencyTolerance = 100.0
    private var minMatches = 4
    private var amplitudeThreshold = 500.0
    
    // Reference data
    private var referenceSequence: List<List<Double>> = emptyList()
    private var currentLiveSignature: List<Double> = emptyList()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createCalibrationLayout())
        
        setupUI()
        loadReferenceSound()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
    
    private fun createCalibrationLayout(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // Title
        layout.addView(TextView(this).apply {
            text = "🔧 Audio Detection Calibration"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        })
        
        // Status
        statusText = TextView(this).apply {
            text = "Status: Ready"
            textSize = 16f
        }
        layout.addView(statusText)
        
        // Live audio info
        amplitudeText = TextView(this).apply {
            text = "Amplitude: --"
            textSize = 14f
        }
        layout.addView(amplitudeText)
        
        frequencyText = TextView(this).apply {
            text = "Frequencies: --"
            textSize = 14f
        }
        layout.addView(frequencyText)
        
        // Calibration controls
        layout.addView(TextView(this).apply {
            text = "\n🎛️ Calibration Parameters"
            textSize = 18f
        })
        
        // Frequency tolerance
        toleranceText = TextView(this).apply {
            text = "Frequency Tolerance: ${frequencyTolerance.toInt()} Hz"
        }
        layout.addView(toleranceText)
        
        toleranceSlider = SeekBar(this).apply {
            max = 500
            progress = frequencyTolerance.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    frequencyTolerance = progress.toDouble()
                    toleranceText.text = "Frequency Tolerance: $progress Hz"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(toleranceSlider)
        
        // Minimum matches
        minMatchesText = TextView(this).apply {
            text = "Min Matches Per Window: $minMatches"
        }
        layout.addView(minMatchesText)
        
        minMatchesSlider = SeekBar(this).apply {
            max = 10
            progress = minMatches
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    minMatches = progress
                    minMatchesText.text = "Min Matches Per Window: $progress"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(minMatchesSlider)
        
        // Amplitude threshold
        amplitudeThresholdText = TextView(this).apply {
            text = "Amplitude Threshold: ${amplitudeThreshold.toInt()}"
        }
        layout.addView(amplitudeThresholdText)
        
        amplitudeThresholdSlider = SeekBar(this).apply {
            max = 2000
            progress = amplitudeThreshold.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    amplitudeThreshold = progress.toDouble()
                    amplitudeThresholdText.text = "Amplitude Threshold: $progress"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(amplitudeThresholdSlider)
        
        // Buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }
        
        startButton = Button(this).apply {
            text = "Start Listening"
            setOnClickListener { startCalibration() }
        }
        buttonLayout.addView(startButton)
        
        stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopCalibration() }
            isEnabled = false
        }
        buttonLayout.addView(stopButton)
        
        testButton = Button(this).apply {
            text = "Test Match"
            setOnClickListener { testCurrentMatch() }
        }
        buttonLayout.addView(testButton)
        
        saveButton = Button(this).apply {
            text = "Save Settings"
            setOnClickListener { saveCalibrationSettings() }
        }
        buttonLayout.addView(saveButton)
        
        layout.addView(buttonLayout)
        
        return layout
    }
    
    private fun setupUI() {
        // UI is already set up in createCalibrationLayout()
    }
    
    private fun loadReferenceSound() {
        try {
            val referencePcm = AudioUtils.loadWavResource(this, R.raw.completion_sound)
            val freqLists = mutableListOf<List<Double>>()
            
            var i = 0
            while (i + windowSize <= referencePcm.size) {
                val window = referencePcm.sliceArray(i until i + windowSize)
                val fft = AudioUtils.computeFFT(window)
                val domFreqs = AudioUtils.getDominantFrequencies(fft, sampleRate)
                freqLists.add(domFreqs)
                i += windowSize / 2
            }
            
            referenceSequence = freqLists
            statusText.text = "Status: Reference loaded (${referenceSequence.size} windows)"
        } catch (e: Exception) {
            statusText.text = "Status: Error loading reference - ${e.message}"
        }
    }
    
    private fun startCalibration() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            return
        }
        
        isRecording = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Status: Listening..."
        
        recordingThread = thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            val buffer = ShortArray(bufferSize)
            audioRecord?.startRecording()
            
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val amplitude = buffer.take(read).map { it.toInt().absoluteValue }.average()
                    
                    Handler(Looper.getMainLooper()).post {
                        amplitudeText.text = "Amplitude: ${amplitude.toInt()}"
                    }
                    
                    if (amplitude > amplitudeThreshold) {
                        val window = buffer.take(windowSize.coerceAtMost(read)).toShortArray()
                        if (window.size == windowSize) {
                            val fft = AudioUtils.computeFFT(window)
                            val signature = AudioUtils.getDominantFrequencies(fft, sampleRate)
                            
                            currentLiveSignature = signature
                            
                            Handler(Looper.getMainLooper()).post {
                                val freqText = signature.take(5).joinToString(", ") { 
                                    "${it.toInt()} Hz" 
                                }
                                frequencyText.text = "Frequencies: $freqText"
                            }
                        }
                    }
                }
                Thread.sleep(100)
            }
            
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }
    
    private fun stopCalibration() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "Status: Stopped"
    }
    
    private fun testCurrentMatch() {
        if (currentLiveSignature.isEmpty()) {
            Toast.makeText(this, "No live audio data - start listening first", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (referenceSequence.isEmpty()) {
            Toast.makeText(this, "No reference data loaded", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Test against first window of reference
        val referenceWindow = referenceSequence.firstOrNull()
        if (referenceWindow != null) {
            val matches = referenceWindow.count { refFreq ->
                currentLiveSignature.any { liveFreq -> 
                    kotlin.math.abs(liveFreq - refFreq) < frequencyTolerance 
                }
            }
            
            val isMatch = matches >= minMatches
            val message = "Match test: $matches/${referenceWindow.size} frequencies matched " +
                         "(need $minMatches). Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}"
            
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            statusText.text = "Status: $message"
        }
    }
    
    private fun saveCalibrationSettings() {
        // Save to SharedPreferences
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("frequency_tolerance", frequencyTolerance.toFloat())
            putInt("min_matches", minMatches)
            putFloat("amplitude_threshold", amplitudeThreshold.toFloat())
            apply()
        }
        
        Toast.makeText(this, "Calibration settings saved!", Toast.LENGTH_SHORT).show()
        
        // Log the settings for debugging
        Log.d("Calibration", "Saved settings: tolerance=$frequencyTolerance, minMatches=$minMatches, amplitude=$amplitudeThreshold")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopCalibration()
        }
    }
} 