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
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.math.log10
import org.jtransforms.fft.DoubleFFT_1D
import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.TransformType

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
    
    private var isListening = false
    private var audioRecord: AudioRecord? = null
    private var detectionThread: Thread? = null
    private var referenceFingerprint: AudioFingerprint? = null
    private var currentFingerprintMatchThreshold = 0.85f
    
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
        
        // Load reference fingerprint
        loadReferenceFingerprint()
        
        // Load current threshold
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        currentFingerprintMatchThreshold = prefs.getFloat("fingerprint_match_threshold", 0.85f)
        thresholdSlider.progress = ((currentFingerprintMatchThreshold * 100).toInt())
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
        
        updateUI()
    }
    
    private fun loadReferenceFingerprint() {
        try {
            val referencePcm = AudioUtils.loadWavResource(this, R.raw.completion_sound)
            referenceFingerprint = AudioUtils.generateAudioFingerprint(referencePcm, sampleRate)
            
            Log.d("Calibration", "Reference fingerprint loaded: ${referenceFingerprint?.fingerprints?.size} frames")
            fingerprintText.text = "Reference fingerprint: ${referenceFingerprint?.fingerprints?.size} frames"
            
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "✅ Reference fingerprint loaded", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Calibration", "Failed to load reference fingerprint: ${e.message}")
            fingerprintText.text = "❌ Failed to load reference fingerprint"
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
            if (referenceFingerprint == null) {
                similarityText.text = "No reference fingerprint available"
                return
            }
            
            val liveFingerprint = AudioUtils.generateAudioFingerprint(livePcm, sampleRate)
            val similarity = AudioUtils.compareFingerprints(referenceFingerprint, liveFingerprint)
            val isMatch = similarity >= currentFingerprintMatchThreshold
            
            similarityText.text = "Similarity: ${String.format("%.3f", similarity)} (${if (isMatch) "MATCH ✅" else "NO MATCH ❌"})"
            
            // Change text color based on match
            similarityText.setTextColor(
                if (isMatch) android.graphics.Color.GREEN 
                else android.graphics.Color.RED
            )
            
        } catch (e: Exception) {
            Log.e("Calibration", "Fingerprint analysis error: ${e.message}")
            similarityText.text = "Analysis error: ${e.message}"
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
    
    private fun saveCalibrationSettings() {
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("fingerprint_match_threshold", currentFingerprintMatchThreshold)
            apply()
        }
        
        Log.d("Calibration", "Saved fingerprint threshold: $currentFingerprintMatchThreshold")
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