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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.abs

class CalibrationActivity : AppCompatActivity() {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var amplitudeText: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var similarityText: TextView
    private lateinit var thresholdText: TextView
    private lateinit var thresholdSlider: android.widget.SeekBar
    private lateinit var saveButton: Button
    private lateinit var enhancedModeSwitch: android.widget.Switch
    private lateinit var highQualityModeSwitch: android.widget.Switch
    private lateinit var adaptiveThresholdSwitch: android.widget.Switch
    private lateinit var diagnosticText: TextView
    
    private var isListening = false
    private var audioRecord: AudioRecord? = null
    private var detectionThread: Thread? = null
    private var referenceFingerprint: AudioFingerprint? = null
    private var referenceEnhancedFingerprint: EnhancedAudioFingerprint? = null
    private var referenceHighQualityFingerprint: EnhancedAudioFingerprint? = null
    private var currentFingerprintMatchThreshold = 0.85f
    private var useEnhancedFingerprinting = true
    private var useHighQualityFingerprinting = true
    private var adaptiveThresholdEnabled = true
    
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        amplitudeText = findViewById(R.id.amplitudeText)
        fingerprintText = findViewById(R.id.fingerprintText)
        similarityText = findViewById(R.id.similarityText)
        thresholdText = findViewById(R.id.thresholdText)
        thresholdSlider = findViewById(R.id.thresholdSlider)
        saveButton = findViewById(R.id.saveButton)
        enhancedModeSwitch = findViewById(R.id.enhancedModeSwitch)
        highQualityModeSwitch = findViewById(R.id.highQualityModeSwitch)
        adaptiveThresholdSwitch = findViewById(R.id.adaptiveThresholdSwitch)
        diagnosticText = findViewById(R.id.diagnosticText)
        
        // Load reference fingerprints
        loadReferenceFingerprints()
        
        // Load current settings
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        currentFingerprintMatchThreshold = prefs.getFloat("fingerprint_match_threshold", 0.85f)
        useEnhancedFingerprinting = prefs.getBoolean("use_enhanced_fingerprinting", true)
        useHighQualityFingerprinting = prefs.getBoolean("use_high_quality_fingerprinting", true)
        adaptiveThresholdEnabled = prefs.getBoolean("adaptive_threshold_enabled", true)
        
        thresholdSlider.progress = ((currentFingerprintMatchThreshold * 100).toInt())
        enhancedModeSwitch.isChecked = useEnhancedFingerprinting
        highQualityModeSwitch.isChecked = useHighQualityFingerprinting
        adaptiveThresholdSwitch.isChecked = adaptiveThresholdEnabled
        
        updateThresholdText()
        
        startButton.setOnClickListener {
            if (checkPermission()) {
                startListening()
            } else {
                requestPermission()
            }
        }
        
        stopButton.setOnClickListener {
            stopListening()
        }
        
        saveButton.setOnClickListener {
            saveCalibrationSettings()
        }
        
        thresholdSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentFingerprintMatchThreshold = progress / 100.0f
                updateThresholdText()
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        enhancedModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            useEnhancedFingerprinting = isChecked
            if (!isChecked) {
                highQualityModeSwitch.isChecked = false
                useHighQualityFingerprinting = false
            }
            updateDiagnosticText()
        }
        
        highQualityModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            useHighQualityFingerprinting = isChecked
            if (isChecked) {
                enhancedModeSwitch.isChecked = true
                useEnhancedFingerprinting = true
            }
            updateDiagnosticText()
        }
        
        adaptiveThresholdSwitch.setOnCheckedChangeListener { _, isChecked ->
            adaptiveThresholdEnabled = isChecked
            updateDiagnosticText()
        }
        
        updateUI()
        updateDiagnosticText()
    }
    
    private fun loadReferenceFingerprints() {
        try {
            // Try to load multiple audio sources for better fingerprint quality
            val audioSources = mutableListOf<Int>()
            
            // Add available audio resources
            try {
                audioSources.add(R.raw.completion_sound) // WAV file
                Log.d("Calibration", "Added completion_sound.wav to audio sources")
            } catch (e: Exception) {
                Log.w("Calibration", "Could not add completion_sound.wav: ${e.message}")
            }
            
            try {
                audioSources.add(R.raw.completion_tune) // M4A file
                Log.d("Calibration", "Added completion_tune.m4a to audio sources")
            } catch (e: Exception) {
                Log.w("Calibration", "Could not add completion_tune.m4a: ${e.message}")
            }
            
            if (audioSources.isEmpty()) {
                throw IllegalStateException("No audio sources available")
            }
            
            Log.d("Calibration", "Loading ${audioSources.size} audio sources for fingerprint generation")
            
            // Generate fingerprints from all available sources
            if (useHighQualityFingerprinting && audioSources.size > 1) {
                // Use high-quality fingerprinting with multiple sources
                referenceHighQualityFingerprint = AudioUtils.generateHighQualityFingerprint(this, audioSources)
                Log.d("Calibration", "Generated high-quality composite fingerprint from ${audioSources.size} sources")
            } else {
                // Fallback to single source
                val referencePcm = AudioUtils.loadAudioResource(this, audioSources.first())
                val preprocessedPcm = AudioUtils.preprocessAudioForFingerprinting(referencePcm, sampleRate)
                
                referenceFingerprint = AudioUtils.generateAudioFingerprint(preprocessedPcm, sampleRate)
                referenceEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedPcm, sampleRate)
                
                Log.d("Calibration", "Generated fingerprints from single source: standard=${referenceFingerprint?.fingerprints?.size} frames, enhanced=${referenceEnhancedFingerprint?.fingerprints?.size} frames")
            }
            
            // Update fingerprint text
            val fingerprintInfo = if (useHighQualityFingerprinting && audioSources.size > 1) {
                "High-quality composite fingerprint from ${audioSources.size} sources"
            } else {
                "Reference fingerprints: standard=${referenceFingerprint?.fingerprints?.size} frames, enhanced=${referenceEnhancedFingerprint?.fingerprints?.size} frames"
            }
            fingerprintText.text = fingerprintInfo
            
            Handler(Looper.getMainLooper()).post {
                val message = if (useHighQualityFingerprinting && audioSources.size > 1) {
                    "✅ High-quality fingerprint loaded from ${audioSources.size} sources"
                } else {
                    "✅ Reference fingerprints loaded"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Calibration", "Failed to load reference fingerprints: ${e.message}")
            fingerprintText.text = "❌ Failed to load reference fingerprints"
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "❌ Failed to load reference: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun startListening() {
        if (isListening) return
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }
        
        isListening = true
        updateUI()
        
        detectionThread = thread {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                
                val buffer = ShortArray(bufferSize)
                audioRecord?.startRecording()
                
                Log.d("Calibration", "Started audio calibration listening")
                
                while (isListening) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val amplitude = buffer.take(read).map { it.toInt().absoluteValue }.average()
                        val livePcm = buffer.take(read).toShortArray()
                        
                        // Update UI on main thread
                        Handler(Looper.getMainLooper()).post {
                            updateAmplitudeDisplay(amplitude)
                            updateFingerprintAnalysis(livePcm)
                        }
                    }
                    Thread.sleep(100) // Update every 100ms
                }
                
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                
            } catch (e: Exception) {
                Log.e("Calibration", "Audio recording error: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@CalibrationActivity, "❌ Audio recording error: ${e.message}", Toast.LENGTH_LONG).show()
                    stopListening()
                }
            }
        }
    }
    
    private fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        detectionThread?.join(500)
        detectionThread = null
        updateUI()
        Log.d("Calibration", "Stopped audio calibration listening")
    }
    
    private fun updateAmplitudeDisplay(amplitude: Double) {
        amplitudeText.text = "Amplitude: ${String.format("%.1f", amplitude)}"
    }
    
    private fun updateFingerprintAnalysis(livePcm: ShortArray) {
        try {
            val referenceFingerprint = referenceFingerprint
            val referenceEnhancedFingerprint = referenceEnhancedFingerprint
            val referenceHighQualityFingerprint = referenceHighQualityFingerprint
            
            if (referenceFingerprint == null && referenceEnhancedFingerprint == null && referenceHighQualityFingerprint == null) {
                similarityText.text = "No reference fingerprints available"
                return
            }
            
            var similarity = 0.0
            var quality = 0.0
            var detectionMethod = ""
            var comparisonResult: FingerprintComparisonResult? = null
            
            // Preprocess live audio for better quality
            val preprocessedLivePcm = AudioUtils.preprocessAudioForFingerprinting(livePcm, sampleRate)
            
            if (useHighQualityFingerprinting && referenceHighQualityFingerprint != null) {
                // Use high-quality fingerprinting with quality assessment
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                val highQualityFingerprint = referenceHighQualityFingerprint!!
                comparisonResult = AudioUtils.compareFingerprintsWithQuality(highQualityFingerprint, liveEnhancedFingerprint)
                similarity = comparisonResult.similarity
                quality = comparisonResult.quality
                detectionMethod = "High-Quality"
            } else if (useEnhancedFingerprinting && referenceEnhancedFingerprint != null) {
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                val enhancedFingerprint = referenceEnhancedFingerprint!!
                similarity = AudioUtils.compareEnhancedFingerprints(enhancedFingerprint, liveEnhancedFingerprint)
                detectionMethod = "Enhanced"
            } else {
                val liveFingerprint = AudioUtils.generateAudioFingerprint(preprocessedLivePcm, sampleRate)
                similarity = AudioUtils.compareFingerprints(referenceFingerprint!!, liveFingerprint)
                detectionMethod = "Standard"
            }
            
            val isMatch = similarity >= currentFingerprintMatchThreshold
            
            val similarityText = if (useHighQualityFingerprinting && comparisonResult != null) {
                "$detectionMethod Similarity: ${String.format("%.3f", similarity)} (Quality: ${String.format("%.3f", quality)}) (${if (isMatch) "MATCH ✅" else "NO MATCH ❌"})"
            } else {
                "$detectionMethod Similarity: ${String.format("%.3f", similarity)} (${if (isMatch) "MATCH ✅" else "NO MATCH ❌"})"
            }
            
            this.similarityText.text = similarityText
            
            // Change text color based on match
            this.similarityText.setTextColor(
                if (isMatch) android.graphics.Color.GREEN 
                else android.graphics.Color.RED
            )
            
            // Update diagnostic information
            updateDiagnosticInfo(livePcm, similarity, quality, detectionMethod, comparisonResult)
            
        } catch (e: Exception) {
            Log.e("Calibration", "Fingerprint analysis error: ${e.message}")
            similarityText.text = "Analysis error: ${e.message}"
        }
    }
    
    private fun updateDiagnosticInfo(livePcm: ShortArray, similarity: Double, quality: Double, detectionMethod: String, comparisonResult: FingerprintComparisonResult?) {
        try {
            val preprocessedLivePcm = AudioUtils.preprocessAudioForFingerprinting(livePcm, sampleRate)
            
            if (useHighQualityFingerprinting && referenceHighQualityFingerprint != null) {
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                val highQualityFingerprint = referenceHighQualityFingerprint!!
                
                if (highQualityFingerprint.fingerprints.isNotEmpty() && liveEnhancedFingerprint.fingerprints.isNotEmpty()) {
                    val refFrame = highQualityFingerprint.fingerprints[0]
                    val liveFrame = liveEnhancedFingerprint.fingerprints[0]
                    
                    val diagnosticInfo = """
                        📊 High-Quality Fingerprint Diagnostics:
                        🎵 Spectral Centroid: ${String.format("%.1f", refFrame.spectralCentroid)}Hz → ${String.format("%.1f", liveFrame.spectralCentroid)}Hz
                        📈 Spectral Rolloff: ${String.format("%.1f", refFrame.spectralRolloff)}Hz → ${String.format("%.1f", liveFrame.spectralRolloff)}Hz
                        🔄 Spectral Flux: ${String.format("%.3f", refFrame.spectralFlux)} → ${String.format("%.3f", liveFrame.spectralFlux)}
                        ⚡ Energy Band Diff: ${String.format("%.2f", refFrame.energyBands.zip(liveFrame.energyBands).map { (first, second) -> kotlin.math.abs(first - second) }.average())} dB
                        🎯 Similarity Score: ${String.format("%.3f", similarity)}
                        🏆 Quality Score: ${String.format("%.3f", quality)}
                        🔧 Detection Method: $detectionMethod
                        ⚙️ Adaptive Threshold: ${if (adaptiveThresholdEnabled) "Enabled" else "Disabled"}
                        💎 Match Confidence: ${comparisonResult?.let { if (it.quality > 0.8) "HIGH" else if (it.quality > 0.6) "MEDIUM" else "LOW" } ?: "N/A"}
                    """.trimIndent()
                    
                    diagnosticText.text = diagnosticInfo
                }
            } else if (useEnhancedFingerprinting && referenceEnhancedFingerprint != null) {
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                val enhancedFingerprint = referenceEnhancedFingerprint!!
                
                if (enhancedFingerprint.fingerprints.isNotEmpty() && liveEnhancedFingerprint.fingerprints.isNotEmpty()) {
                    val refFrame = enhancedFingerprint.fingerprints[0]
                    val liveFrame = liveEnhancedFingerprint.fingerprints[0]
                    
                    val diagnosticInfo = """
                        📊 Enhanced Fingerprint Diagnostics:
                        🎵 Spectral Centroid: ${String.format("%.1f", refFrame.spectralCentroid)}Hz → ${String.format("%.1f", liveFrame.spectralCentroid)}Hz
                        📈 Spectral Rolloff: ${String.format("%.1f", refFrame.spectralRolloff)}Hz → ${String.format("%.1f", liveFrame.spectralRolloff)}Hz
                        🔄 Spectral Flux: ${String.format("%.3f", refFrame.spectralFlux)} → ${String.format("%.3f", liveFrame.spectralFlux)}
                        ⚡ Energy Band Diff: ${String.format("%.2f", refFrame.energyBands.zip(liveFrame.energyBands).map { (first, second) -> kotlin.math.abs(first - second) }.average())} dB
                        🎯 Similarity Score: ${String.format("%.3f", similarity)}
                        🔧 Detection Method: $detectionMethod
                        ⚙️ Adaptive Threshold: ${if (adaptiveThresholdEnabled) "Enabled" else "Disabled"}
                    """.trimIndent()
                    
                    diagnosticText.text = diagnosticInfo
                }
            } else {
                diagnosticText.text = "Standard fingerprinting mode - enhanced diagnostics not available"
            }
        } catch (e: Exception) {
            diagnosticText.text = "Diagnostic error: ${e.message}"
        }
    }
    
    private fun updateThresholdText() {
        thresholdText.text = "Fingerprint Threshold: ${String.format("%.2f", currentFingerprintMatchThreshold)}"
    }
    
    private fun updateUI() {
        startButton.isEnabled = !isListening
        stopButton.isEnabled = isListening
        statusText.text = if (isListening) "🔴 Listening..." else "⏸️ Stopped"
    }
    
    private fun updateDiagnosticText() {
        val diagnosticInfo = """
            🔧 Fingerprinting Configuration:
            📊 Enhanced Mode: ${if (useEnhancedFingerprinting) "✅ Enabled" else "❌ Disabled"}
            💎 High-Quality Mode: ${if (useHighQualityFingerprinting) "✅ Enabled" else "❌ Disabled"}
            🎯 Adaptive Threshold: ${if (adaptiveThresholdEnabled) "✅ Enabled" else "❌ Disabled"}
            📈 Current Threshold: ${String.format("%.2f", currentFingerprintMatchThreshold)}
            
            💡 High-Quality Mode Features:
            • Multiple audio source processing (WAV + M4A)
            • Advanced audio preprocessing (filtering, normalization)
            • Composite fingerprint generation
            • Quality assessment and confidence scoring
            • Enhanced noise reduction and spectral analysis
        """.trimIndent()
        
        diagnosticText.text = diagnosticInfo
    }
    
    private fun saveCalibrationSettings() {
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("fingerprint_match_threshold", currentFingerprintMatchThreshold)
            putBoolean("use_enhanced_fingerprinting", useEnhancedFingerprinting)
            putBoolean("use_high_quality_fingerprinting", useHighQualityFingerprinting)
            putBoolean("adaptive_threshold_enabled", adaptiveThresholdEnabled)
            apply()
        }
        
        Log.d("Calibration", "Saved settings: threshold=$currentFingerprintMatchThreshold, enhanced=$useEnhancedFingerprinting, highQuality=$useHighQualityFingerprinting, adaptive=$adaptiveThresholdEnabled")
        Toast.makeText(this, "✅ Calibration settings saved", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }
} 