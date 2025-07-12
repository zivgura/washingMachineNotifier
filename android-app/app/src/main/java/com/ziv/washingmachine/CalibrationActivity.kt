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
import kotlin.math.sqrt

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
    
    // Detection method selection
    private lateinit var detectionMethodSpinner: Spinner
    private lateinit var methodResultsText: TextView
    
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 44100
    private val windowSize = 2048
    
    // Calibration values
    private var frequencyTolerance = 100.0
    private var minMatches = 3
    private var amplitudeThreshold = 140.0
    private var detectionMethod = "hybrid"
    
    // Reference data
    private var referenceSequence: List<List<Double>> = emptyList()
    private var referenceAmplitudePattern: List<Double> = emptyList()
    private var referenceSpectralProfile: List<Double> = emptyList()
    private var referenceCrossCorrelation: List<Double> = emptyList()
    private var currentLiveSignature: List<Double> = emptyList()
    
    // Detection results
    private var fftResult = false
    private var crossCorrelationResult = false
    private var spectralResult = false
    private var amplitudeResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        
        setupUI()
        loadCalibrationSettings()
        loadReferenceSound()
    }
    
    private fun setupUI() {
        statusText = findViewById(R.id.statusText)
        amplitudeText = findViewById(R.id.amplitudeText)
        frequencyText = findViewById(R.id.frequencyText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        testButton = findViewById(R.id.testButton)
        saveButton = findViewById(R.id.saveButton)
        
        toleranceSlider = findViewById(R.id.toleranceSlider)
        toleranceText = findViewById(R.id.toleranceText)
        minMatchesSlider = findViewById(R.id.minMatchesSlider)
        minMatchesText = findViewById(R.id.minMatchesText)
        amplitudeThresholdSlider = findViewById(R.id.amplitudeThresholdSlider)
        amplitudeThresholdText = findViewById(R.id.amplitudeThresholdText)
        
        detectionMethodSpinner = findViewById(R.id.detectionMethodSpinner)
        methodResultsText = findViewById(R.id.methodResultsText)
        
        // Set up sliders
        toleranceSlider.max = 200
        toleranceSlider.progress = frequencyTolerance.toInt()
        toleranceText.text = "Frequency Tolerance: ${frequencyTolerance.toInt()} Hz"
        
        minMatchesSlider.max = 10
        minMatchesSlider.progress = minMatches
        minMatchesText.text = "Min Matches: $minMatches"
        
        amplitudeThresholdSlider.max = 500
        amplitudeThresholdSlider.progress = amplitudeThreshold.toInt()
        amplitudeThresholdText.text = "Amplitude Threshold: ${amplitudeThreshold.toInt()}"
        
        // Set up detection method spinner
        val methods = arrayOf("FFT Only", "Cross-Correlation", "Spectral", "Amplitude", "Hybrid")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, methods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        detectionMethodSpinner.adapter = adapter
        
        // Set initial selection based on current method
        val methodIndex = when (detectionMethod) {
            "fft" -> 0
            "cross_correlation" -> 1
            "spectral" -> 2
            "amplitude" -> 3
            "hybrid" -> 4
            else -> 4
        }
        detectionMethodSpinner.setSelection(methodIndex)
        
        // Set up button listeners
        startButton.setOnClickListener { startCalibration() }
        stopButton.setOnClickListener { stopCalibration() }
        testButton.setOnClickListener { testDetection() }
        saveButton.setOnClickListener { saveSettings() }
        
        // Set up slider listeners
        toleranceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                frequencyTolerance = progress.toDouble()
                toleranceText.text = "Frequency Tolerance: $progress Hz"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        minMatchesSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                minMatches = progress
                minMatchesText.text = "Min Matches: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        amplitudeThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                amplitudeThreshold = progress.toDouble()
                amplitudeThresholdText.text = "Amplitude Threshold: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        detectionMethodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                detectionMethod = when (position) {
                    0 -> "fft"
                    1 -> "cross_correlation"
                    2 -> "spectral"
                    3 -> "amplitude"
                    4 -> "hybrid"
                    else -> "hybrid"
                }
                updateMethodResults()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        stopButton.isEnabled = false
    }
    
    private fun loadCalibrationSettings() {
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        frequencyTolerance = prefs.getFloat("frequency_tolerance", 100.0f).toDouble()
        minMatches = prefs.getInt("min_matches", 3)
        amplitudeThreshold = prefs.getFloat("amplitude_threshold", 140.0f).toDouble()
        detectionMethod = prefs.getString("detection_method", "hybrid") ?: "hybrid"
        
        // Update UI
        toleranceSlider.progress = frequencyTolerance.toInt()
        minMatchesSlider.progress = minMatches
        amplitudeThresholdSlider.progress = amplitudeThreshold.toInt()
        
        Log.d("Calibration", "Loaded settings: tolerance=$frequencyTolerance, minMatches=$minMatches, amplitude=$amplitudeThreshold, method=$detectionMethod")
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
            
            // Analyze reference with multiple methods
            referenceAmplitudePattern = analyzeAmplitudePattern(referencePcm)
            referenceSpectralProfile = analyzeSpectralProfile(referencePcm)
            referenceCrossCorrelation = createCrossCorrelationTemplate(referencePcm)
            
            statusText.text = "Status: Reference loaded (${referenceSequence.size} windows)"
        } catch (e: Exception) {
            statusText.text = "Status: Error loading reference - ${e.message}"
        }
    }
    
    private fun analyzeAmplitudePattern(pcm: ShortArray): List<Double> {
        val pattern = mutableListOf<Double>()
        val windowSize = 1024
        var i = 0
        while (i + windowSize <= pcm.size) {
            val window = pcm.sliceArray(i until i + windowSize)
            val rms = sqrt(window.map { it.toDouble() * it.toDouble() }.average())
            pattern.add(rms)
            i += windowSize / 2
        }
        return pattern
    }
    
    private fun analyzeSpectralProfile(pcm: ShortArray): List<Double> {
        val profile = mutableListOf<Double>()
        val windowSize = 2048
        var i = 0
        while (i + windowSize <= pcm.size) {
            val window = pcm.sliceArray(i until i + windowSize)
            val fft = AudioUtils.computeFFT(window)
            val spectralAvg = fft.take(fft.size / 4).average()
            profile.add(spectralAvg)
            i += windowSize / 2
        }
        return profile
    }
    
    private fun createCrossCorrelationTemplate(pcm: ShortArray): List<Double> {
        val template = mutableListOf<Double>()
        val windowSize = 1024
        var i = 0
        while (i + windowSize <= pcm.size) {
            val window = pcm.sliceArray(i until i + windowSize)
            val maxVal = window.map { it.toDouble().absoluteValue }.maxOrNull() ?: 1.0
            val normalized = window.map { it.toDouble() / maxVal }
            template.addAll(normalized)
            i += windowSize
        }
        return template
    }
    
    private fun startCalibration() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        
        isRecording = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Status: Recording..."
        
        recordingThread = thread {
            try {
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
                                // Perform heavy computation in background
                                val fft = AudioUtils.computeFFT(window)
                                val signature = AudioUtils.getDominantFrequencies(fft, sampleRate)
                                
                                currentLiveSignature = signature
                                
                                Handler(Looper.getMainLooper()).post {
                                    val freqText = signature.take(5).joinToString(", ") { 
                                        "${it.toInt()} Hz" 
                                    }
                                    frequencyText.text = "Frequencies: $freqText"
                                    
                                    // Test detection methods in background thread
                                    thread {
                                        testDetectionMethods(window)
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(100)
                }
                
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.e("Calibration", "Error in recording thread: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    statusText.text = "Status: Error - ${e.message}"
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                }
            }
        }
    }
    
    private fun testDetectionMethods(window: ShortArray) {
        try {
            // Test FFT method
            fftResult = testFFTDetection(window)
            
            // Test Cross-correlation method
            crossCorrelationResult = testCrossCorrelationDetection(window)
            
            // Test Spectral method
            spectralResult = testSpectralDetection(window)
            
            // Test Amplitude method
            amplitudeResult = testAmplitudeDetection(window)
            
            // Update UI on main thread
            Handler(Looper.getMainLooper()).post {
                updateMethodResults()
            }
        } catch (e: Exception) {
            Log.e("Calibration", "Error in detection methods: ${e.message}")
        }
    }
    
    private fun testFFTDetection(window: ShortArray): Boolean {
        return try {
            if (referenceSequence.isEmpty()) return false
            
            val liveFft = AudioUtils.computeFFT(window)
            val liveSignature = AudioUtils.getDominantFrequencies(liveFft, sampleRate)
            
            if (liveSignature.isEmpty()) return false
            
            val referenceWindow = referenceSequence.firstOrNull() ?: return false
            val matches = referenceWindow.count { refFreq ->
                liveSignature.any { liveFreq -> 
                    kotlin.math.abs(liveFreq - refFreq) < frequencyTolerance 
                }
            }
            
            matches >= minMatches
        } catch (e: Exception) {
            Log.e("Calibration", "FFT detection error: ${e.message}")
            false
        }
    }
    
    private fun testCrossCorrelationDetection(window: ShortArray): Boolean {
        return try {
            if (referenceCrossCorrelation.isEmpty()) return false
            
            val maxVal = window.map { it.toDouble().absoluteValue }.maxOrNull() ?: 1.0
            val normalizedWindow = window.map { it.toDouble() / maxVal }
            
            val correlation = computeCrossCorrelation(normalizedWindow, referenceCrossCorrelation)
            val maxCorrelation = correlation.maxOrNull() ?: 0.0
            
            maxCorrelation > 0.7
        } catch (e: Exception) {
            Log.e("Calibration", "Cross-correlation detection error: ${e.message}")
            false
        }
    }
    
    private fun testSpectralDetection(window: ShortArray): Boolean {
        return try {
            if (referenceSpectralProfile.isEmpty()) return false
            
            val fft = AudioUtils.computeFFT(window)
            val spectralAvg = fft.take(fft.size / 4).average()
            
            val referenceAvg = referenceSpectralProfile.average()
            val similarity = 1.0 - kotlin.math.abs(spectralAvg - referenceAvg) / referenceAvg
            
            similarity > 0.6
        } catch (e: Exception) {
            Log.e("Calibration", "Spectral detection error: ${e.message}")
            false
        }
    }
    
    private fun testAmplitudeDetection(window: ShortArray): Boolean {
        return try {
            if (referenceAmplitudePattern.isEmpty()) return false
            
            val rms = sqrt(window.map { it.toDouble() * it.toDouble() }.average())
            
            val referenceAvg = referenceAmplitudePattern.average()
            val similarity = 1.0 - kotlin.math.abs(rms - referenceAvg) / referenceAvg
            
            similarity > 0.5
        } catch (e: Exception) {
            Log.e("Calibration", "Amplitude detection error: ${e.message}")
            false
        }
    }
    
    private fun computeCrossCorrelation(signal1: List<Double>, signal2: List<Double>): List<Double> {
        return try {
            val result = mutableListOf<Double>()
            val n1 = signal1.size
            val n2 = signal2.size
            
            // Limit computation to prevent blocking
            val maxLag = minOf(n1, n2, 1000) // Limit to prevent excessive computation
            
            for (lag in -maxLag + 1 until maxLag) {
                var sum = 0.0
                for (i in 0 until n1) {
                    val j = i + lag
                    if (j >= 0 && j < n2) {
                        sum += signal1[i] * signal2[j]
                    }
                }
                result.add(sum)
            }
            
            result
        } catch (e: Exception) {
            Log.e("Calibration", "Cross-correlation computation error: ${e.message}")
            emptyList()
        }
    }
    
    private fun updateMethodResults() {
        try {
            val results = mutableListOf<String>()
            
            if (detectionMethod == "fft" || detectionMethod == "hybrid") {
                results.add("FFT: ${if (fftResult) "✅" else "❌"}")
            }
            if (detectionMethod == "cross_correlation" || detectionMethod == "hybrid") {
                results.add("Cross-Corr: ${if (crossCorrelationResult) "✅" else "❌"}")
            }
            if (detectionMethod == "spectral" || detectionMethod == "hybrid") {
                results.add("Spectral: ${if (spectralResult) "✅" else "❌"}")
            }
            if (detectionMethod == "amplitude" || detectionMethod == "hybrid") {
                results.add("Amplitude: ${if (amplitudeResult) "✅" else "❌"}")
            }
            
            val overallResult = when (detectionMethod) {
                "hybrid" -> results.count { it.contains("✅") } >= 2
                else -> results.isNotEmpty() && results.all { it.contains("✅") }
            }
            
            methodResultsText.text = "Results: ${results.joinToString(", ")}\nOverall: ${if (overallResult) "✅ MATCH" else "❌ NO MATCH"}"
        } catch (e: Exception) {
            Log.e("Calibration", "Error updating method results: ${e.message}")
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
    
    private fun testDetection() {
        // This button can be used for manual testing
        statusText.text = "Status: Manual test triggered"
        Toast.makeText(this, "Test detection triggered", Toast.LENGTH_SHORT).show()
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("frequency_tolerance", frequencyTolerance.toFloat())
            putInt("min_matches", minMatches)
            putFloat("amplitude_threshold", amplitudeThreshold.toFloat())
            putString("detection_method", detectionMethod)
        }.apply()
        
        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        statusText.text = "Status: Settings saved"
    }
} 