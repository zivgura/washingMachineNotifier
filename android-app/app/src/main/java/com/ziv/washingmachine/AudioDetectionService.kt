package com.ziv.washingmachine

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject

class AudioDetectionService : Service() {

    private val CHANNEL_ID = "audio_detection_channel"
    private var isDetecting = false
    private var detectionThread: Thread? = null
    private var lastDetectionTime = 0L
    private val detectionCooldownMs = 10000L // 10 seconds (increased from 5)
    private var consecutiveMatches = 0
    private val requiredConsecutiveMatches = 5 // Increased from 3 to be more strict
    private var referenceSequence: List<List<Double>> = emptyList()
    
    // WakeLock to keep CPU running when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Audio analysis parameters
    private val sampleRate = 44100
    private val windowSize = 2048
    private val stepSize = windowSize / 2
    
    // Server configuration - now uses dynamic settings
    private fun getServerUrl(): String = SettingsActivity.getServerUrl(this)
    private val deviceId = "android_sensor_${System.currentTimeMillis()}"
    
    // Calibration settings
    private var frequencyTolerance = 100.0
    private var minMatchesPerWindow = 3
    private var amplitudeThreshold = 140.0

    override fun onCreate() {
        super.onCreate()
        
        // Acquire WakeLock to keep CPU running when screen is off
        acquireWakeLock()
        
        createNotificationChannel()
        startForeground(1, createNotification())
        loadCalibrationSettings()
        loadAndAnalyzeReferenceSound()
        startAudioDetection()
        
        // Log which server URL is being used
        Log.d("AudioDetection", "Using server URL: ${getServerUrl()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reload calibration settings in case they were updated
        loadCalibrationSettings()
        
        // If service was killed and restarted, restart audio detection
        if (!isDetecting) {
            Log.d("AudioDetection", "Service restarted, restarting audio detection")
            startAudioDetection()
        }
        
        return START_STICKY
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("AudioDetection", "App removed from recent tasks, but service continues running")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioDetection()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for washing machine detection"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Washing Machine Listener")
            .setContentText("Listening for washing machine completion...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true) // Make notification persistent
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun sendWashingMachineNotification() {
        thread {
            try {
                val serverUrl = getServerUrl()
                
                // Wake up the server first
                wakeUpServer(serverUrl)
                
                val response = sendToServer("$serverUrl/api/notifications/washing-machine-done", JSONObject().apply {
                    put("detectedBy", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("message", "Washing machine cycle completed")
                })
                Log.d("AudioDetection", "Washing machine notification sent: $response")
                
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AudioDetectionService, "📧 Email notification sent!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("AudioDetection", "Failed to send washing machine notification: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AudioDetectionService, "❌ Failed to send notification: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun wakeUpServer(serverUrl: String) {
        try {
            val url = URL("$serverUrl/api/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()
            
            Log.d("AudioDetection", "Server wake-up call successful: $responseCode - $response")
        } catch (e: Exception) {
            Log.w("AudioDetection", "Server wake-up call failed: ${e.message}")
            // Don't throw here - we'll still try to send the notification
        }
    }

    private fun sendToServer(fullUrl: String, data: JSONObject): String {
        val url = URL(fullUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        
        val outputStream = connection.outputStream
        outputStream.write(data.toString().toByteArray())
        outputStream.flush()
        outputStream.close()
        
        val responseCode = connection.responseCode
        val inputStream = if (responseCode >= 200 && responseCode < 300) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        
        val response = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
        connection.disconnect()
        
        if (responseCode >= 200 && responseCode < 300) {
            return response
        } else {
            throw Exception("Server error: $responseCode - $response")
        }
    }

    private fun loadAndAnalyzeReferenceSound() {
        try {
            val referencePcm = AudioUtils.loadWavResource(this, R.raw.completion_sound)
            Log.d("AudioDetection", "Reference PCM loaded: ${referencePcm.size} samples")
            
            // Check if the reference file has any significant audio content
            val maxAmplitude = referencePcm.maxOrNull()?.toDouble() ?: 0.0
            val minAmplitude = referencePcm.minOrNull()?.toDouble() ?: 0.0
            val avgAmplitude = referencePcm.map { it.toDouble().absoluteValue }.average()
            Log.d("AudioDetection", "Reference file stats: max=$maxAmplitude, min=$minAmplitude, avg=$avgAmplitude")
            
            if (avgAmplitude < 100) {
                Log.w("AudioDetection", "WARNING: Reference file has very low amplitude - may be silent or corrupted!")
            }
            
            val freqLists = mutableListOf<List<Double>>()

            var i = 0
            while (i + windowSize <= referencePcm.size) {
                val window = referencePcm.sliceArray(i until i + windowSize)
                val fft = AudioUtils.computeFFT(window)
                val domFreqs = AudioUtils.getDominantFrequencies(fft, sampleRate = sampleRate)
                
                // Log the first few windows to debug
                if (i < windowSize * 3) {
                    Log.d("AudioDetection", "Window $i: dominant frequencies = $domFreqs")
                }
                
                freqLists.add(domFreqs)
                i += stepSize
            }

            referenceSequence = freqLists
            Log.d("AudioDetection", "Reference sequence set: $referenceSequence")
            Log.d("AudioDetection", "Reference sequence size: ${referenceSequence.size} windows")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "✅ Reference sequence loaded successfully", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("AudioDetection", "Failed to load or analyze reference sound: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "❌ Reference sound error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAudioDetection() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioDetection", "RECORD_AUDIO permission not granted!")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "❌ Microphone permission not granted!", Toast.LENGTH_LONG).show()
            }
            return
        }
        isDetecting = true
        detectionThread = thread(start = true) {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            val buffer = ShortArray(bufferSize)
            audioRecord.startRecording()
            Log.d("AudioDetection", "Started audio detection")

            while (isDetecting) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val amplitude = buffer.take(read).map { it.toInt().absoluteValue }.average()
                    if (amplitude > amplitudeThreshold) { // Use loaded amplitude threshold
                        // Use the same window size as reference analysis
                        val window = buffer.take(windowSize.coerceAtMost(read)).toShortArray()
                        if (window.size == windowSize) {
                            val liveFft = AudioUtils.computeFFT(window)
                            val liveSignature = AudioUtils.getDominantFrequencies(liveFft, sampleRate = sampleRate)
                            
                            // Add debugging for live audio analysis
                            if (liveSignature.isNotEmpty()) {
                                val avgFreq = liveSignature.average()
                                val maxAmplitude = window.maxOrNull()?.toDouble() ?: 0.0
                                val avgAmplitude = window.map { it.toDouble().absoluteValue }.average()
                                Log.d("AudioDetection", "Live audio: avg freq = $avgFreq Hz, avg amplitude = $avgAmplitude, max amplitude = $maxAmplitude, signature = $liveSignature")
                            }
                            
                            // Only proceed if we have significant frequencies (not just noise) and sufficient amplitude
                            val avgAmplitude = window.map { it.toDouble().absoluteValue }.average()
                            if (liveSignature.isNotEmpty() && liveSignature.any { it > 100 } && avgAmplitude > amplitudeThreshold * 0.5) { // Only frequencies above 100Hz and sufficient amplitude
                                Log.d("AudioDetection", "Reference: $referenceSequence, Live: $liveSignature")
                                Log.d("AudioDetection", "Live signature: $liveSignature")
                                
                                // Use simple single-window matching like calibration test
                                if (referenceSequence.isNotEmpty()) {
                                    val referenceWindow = referenceSequence.firstOrNull()
                                    if (referenceWindow != null) {
                                        val matches = referenceWindow.count { refFreq ->
                                            liveSignature.any { liveFreq -> 
                                                kotlin.math.abs(liveFreq - refFreq) < frequencyTolerance 
                                            }
                                        }
                                        
                                        val isMatch = matches >= minMatchesPerWindow
                                        Log.d("AudioDetection", "Match test: $matches/${referenceWindow.size} frequencies matched (need $minMatchesPerWindow). Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
                                        
                                        if (isMatch) {
                                            consecutiveMatches++
                                            Log.d("AudioDetection", "Consecutive matches: $consecutiveMatches/$requiredConsecutiveMatches")
                                            
                                            if (consecutiveMatches >= requiredConsecutiveMatches) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastDetectionTime > detectionCooldownMs) {
                                                    lastDetectionTime = now
                                                    consecutiveMatches = 0 // Reset counter
                                                    Log.d("AudioDetection", "🎵 Tune detected! Consecutive matches reached threshold.")
                                                    Handler(Looper.getMainLooper()).post {
                                                        Toast.makeText(this@AudioDetectionService, "🎵 Washing machine tune detected!", Toast.LENGTH_LONG).show()
                                                    }
                                                    // Send notification to server
                                                    sendWashingMachineNotification()
                                                } else {
                                                    Log.d("AudioDetection", "Detection blocked by cooldown. Time since last: ${now - lastDetectionTime}ms")
                                                }
                                            }
                                        } else {
                                            // Reset consecutive matches if no match
                                            if (consecutiveMatches > 0) {
                                                Log.d("AudioDetection", "No match, resetting consecutive counter from $consecutiveMatches to 0")
                                                consecutiveMatches = 0
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Reset consecutive matches for low-quality audio
                                if (consecutiveMatches > 0) {
                                    Log.d("AudioDetection", "Low-quality audio detected, resetting consecutive counter from $consecutiveMatches to 0")
                                    consecutiveMatches = 0
                                }
                            }
                        }
                    }
                }
                Thread.sleep(100)
            }
            audioRecord.stop()
            audioRecord.release()
            Log.d("AudioDetection", "Stopped audio detection")
        }
    }

    private fun stopAudioDetection() {
        isDetecting = false
        detectionThread?.join(500)
        detectionThread = null
    }
    
    private fun loadCalibrationSettings() {
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        frequencyTolerance = prefs.getFloat("frequency_tolerance", 100.0f).toDouble()
        minMatchesPerWindow = prefs.getInt("min_matches", 3)
        amplitudeThreshold = prefs.getFloat("amplitude_threshold", 140.0f).toDouble()
        
        Log.d("AudioDetection", "Loaded calibration settings: tolerance=$frequencyTolerance, minMatches=$minMatchesPerWindow, amplitude=$amplitudeThreshold")
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WashingMachine::AudioDetectionWakeLock"
        )
        wakeLock?.acquire(10*60*1000L) // 10 minutes timeout
        Log.d("AudioDetection", "WakeLock acquired")
        
        // Set up periodic renewal
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if (isDetecting && wakeLock?.isHeld == true) {
                    // Renew WakeLock every 9 minutes
                    wakeLock?.acquire(10*60*1000L)
                    Log.d("AudioDetection", "WakeLock renewed")
                    Handler(Looper.getMainLooper()).postDelayed(this, 9*60*1000L)
                }
            }
        }, 9*60*1000L)
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d("AudioDetection", "WakeLock released")
            }
        }
        wakeLock = null
    }
} 