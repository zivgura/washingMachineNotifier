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
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.abs

class AudioDetectionService : Service() {

    private val CHANNEL_ID = "audio_detection_channel"
    private var isDetecting = false
    private var detectionThread: Thread? = null
    private var lastDetectionTime = 0L
    private val detectionCooldownMs = 10000L // 10 seconds
    private var consecutiveMatches = 0
    private val requiredConsecutiveMatches = 3 // Reduced for fingerprinting
    private var referenceFingerprint: AudioFingerprint? = null
    private var referenceEnhancedFingerprint: EnhancedAudioFingerprint? = null
    private var referenceHighQualityFingerprint: EnhancedAudioFingerprint? = null
    
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
    private var amplitudeThreshold = 140.0
    private var fingerprintMatchThreshold = 0.85
    
    // Enhanced detection parameters
    private var useEnhancedFingerprinting = true
    private var useHighQualityFingerprinting = true
    private var adaptiveThresholdEnabled = true
    private val recentSimilarities = mutableListOf<Double>()
    private val maxRecentSimilarities = 50

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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAudioDetection()
        releaseWakeLock()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart service if app is removed from recent tasks
        val restartServiceIntent = Intent(applicationContext, AudioDetectionService::class.java)
        startService(restartServiceIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Washing machine audio detection service"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
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
                    put("detectionMethod", "audio_fingerprinting")
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
            // Don't throw here - we'll still try to test the connection
        }
    }

    private fun sendToServer(url: String, data: JSONObject): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        
        val jsonString = data.toString()
        connection.setRequestProperty("Content-Length", jsonString.length.toString())
        
        connection.outputStream.use { os: OutputStream ->
            os.write(jsonString.toByteArray())
        }
        
        val responseCode = connection.responseCode
        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        connection.disconnect()
        
        Log.d("AudioDetection", "Server response: $responseCode - $response")
        return response
    }

    private fun loadAndAnalyzeReferenceSound() {
        try {
            // Try to load multiple audio sources for better fingerprint quality
            val audioSources = mutableListOf<Int>()
            
            // Add available audio resources
            try {
                audioSources.add(R.raw.completion_sound) // WAV file
                Log.d("AudioDetection", "Added completion_sound.wav to audio sources")
            } catch (e: Exception) {
                Log.w("AudioDetection", "Could not add completion_sound.wav: ${e.message}")
            }
            
            try {
                audioSources.add(R.raw.completion_tune) // M4A file
                Log.d("AudioDetection", "Added completion_tune.m4a to audio sources")
            } catch (e: Exception) {
                Log.w("AudioDetection", "Could not add completion_tune.m4a: ${e.message}")
            }
            
            if (audioSources.isEmpty()) {
                throw IllegalStateException("No audio sources available")
            }
            
            Log.d("AudioDetection", "Loading ${audioSources.size} audio sources for fingerprint generation")
            
            // Generate fingerprints from all available sources
            if (useHighQualityFingerprinting && audioSources.size > 1) {
                // Use high-quality fingerprinting with multiple sources
                referenceHighQualityFingerprint = AudioUtils.generateHighQualityFingerprint(this, audioSources)
                Log.d("AudioDetection", "Generated high-quality composite fingerprint from ${audioSources.size} sources")
            } else {
                // Fallback to single source
                val referencePcm = AudioUtils.loadAudioResource(this, audioSources.first())
                val preprocessedPcm = AudioUtils.preprocessAudioForFingerprinting(referencePcm, sampleRate)
                
                referenceFingerprint = AudioUtils.generateAudioFingerprint(preprocessedPcm, sampleRate)
                referenceEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedPcm, sampleRate)
                
                Log.d("AudioDetection", "Generated fingerprints from single source: standard=${referenceFingerprint?.fingerprints?.size} frames, enhanced=${referenceEnhancedFingerprint?.fingerprints?.size} frames")
            }
            
            // Log audio quality metrics
            logAudioQualityMetrics(audioSources)
            
            Handler(Looper.getMainLooper()).post {
                val message = if (useHighQualityFingerprinting && audioSources.size > 1) {
                    "✅ High-quality fingerprint loaded from ${audioSources.size} sources"
                } else {
                    "✅ Reference fingerprints loaded successfully"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("AudioDetection", "Failed to load or analyze reference sound: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "❌ Reference sound error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Log audio quality metrics for debugging
    private fun logAudioQualityMetrics(audioSources: List<Int>) {
        for (resId in audioSources) {
            try {
                val pcm = AudioUtils.loadAudioResource(this, resId)
                val resourceName = resources.getResourceEntryName(resId)
                
                val maxAmplitude = pcm.maxOrNull()?.toDouble() ?: 0.0
                val minAmplitude = pcm.minOrNull()?.toDouble() ?: 0.0
                val avgAmplitude = pcm.map { it.toDouble().absoluteValue }.average()
                val duration = pcm.size.toDouble() / sampleRate
                
                Log.d("AudioDetection", "Audio quality metrics for $resourceName:")
                Log.d("AudioDetection", "  Duration: ${String.format("%.2f", duration)}s")
                Log.d("AudioDetection", "  Max amplitude: $maxAmplitude")
                Log.d("AudioDetection", "  Min amplitude: $minAmplitude")
                Log.d("AudioDetection", "  Avg amplitude: $avgAmplitude")
                Log.d("AudioDetection", "  Sample count: ${pcm.size}")
                
                if (avgAmplitude < 100) {
                    Log.w("AudioDetection", "  WARNING: Low amplitude detected - may affect fingerprint quality")
                }
                
                if (duration < 0.5) {
                    Log.w("AudioDetection", "  WARNING: Short duration detected - may affect fingerprint quality")
                }
                
            } catch (e: Exception) {
                Log.e("AudioDetection", "Failed to analyze audio quality for resource $resId: ${e.message}")
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
                    if (amplitude > amplitudeThreshold) {
                        // Use fingerprinting for detection
                        val livePcm = buffer.take(read).toShortArray()
                        val isMatch = detectWithFingerprinting(livePcm)
                        
                        if (isMatch) {
                            consecutiveMatches++
                            Log.d("AudioDetection", "Fingerprint match! Consecutive matches: $consecutiveMatches/$requiredConsecutiveMatches")
                            
                            if (consecutiveMatches >= requiredConsecutiveMatches) {
                                val now = System.currentTimeMillis()
                                if (now - lastDetectionTime > detectionCooldownMs) {
                                    lastDetectionTime = now
                                    consecutiveMatches = 0
                                    Log.d("AudioDetection", "🎵 Washing machine completion detected via fingerprinting!")
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(this@AudioDetectionService, "🎵 Washing machine tune detected!", Toast.LENGTH_LONG).show()
                                    }
                                    sendWashingMachineNotification()
                                } else {
                                    Log.d("AudioDetection", "Detection blocked by cooldown. Time since last: ${now - lastDetectionTime}ms")
                                }
                            }
                        } else {
                            if (consecutiveMatches > 0) {
                                Log.d("AudioDetection", "No fingerprint match, resetting consecutive counter from $consecutiveMatches to 0")
                                consecutiveMatches = 0
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
    
    private fun detectWithFingerprinting(livePcm: ShortArray): Boolean {
        return try {
            val referenceFingerprint = referenceFingerprint
            val referenceEnhancedFingerprint = referenceEnhancedFingerprint
            val referenceHighQualityFingerprint = referenceHighQualityFingerprint
            
            if (referenceFingerprint == null && referenceEnhancedFingerprint == null && referenceHighQualityFingerprint == null) {
                Log.w("AudioDetection", "No reference fingerprints available")
                return false
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
                comparisonResult = AudioUtils.compareFingerprintsWithQuality(referenceHighQualityFingerprint, liveEnhancedFingerprint)
                similarity = comparisonResult.similarity
                quality = comparisonResult.quality
                detectionMethod = "High-Quality"
            } else if (useEnhancedFingerprinting && referenceEnhancedFingerprint != null) {
                // Use enhanced fingerprinting with multiple features
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                similarity = AudioUtils.compareEnhancedFingerprints(referenceEnhancedFingerprint, liveEnhancedFingerprint)
                detectionMethod = "Enhanced"
            } else {
                // Fallback to standard fingerprinting
                val liveFingerprint = AudioUtils.generateAudioFingerprint(preprocessedLivePcm, sampleRate)
                similarity = AudioUtils.compareFingerprints(referenceFingerprint!!, liveFingerprint)
                detectionMethod = "Standard"
            }
            
            // Update recent similarities for adaptive threshold
            recentSimilarities.add(similarity)
            if (recentSimilarities.size > maxRecentSimilarities) {
                recentSimilarities.removeAt(0)
            }
            
            // Calculate adaptive threshold if enabled
            val currentThreshold = if (adaptiveThresholdEnabled && recentSimilarities.size >= 10) {
                AudioUtils.calculateAdaptiveThreshold(recentSimilarities)
            } else {
                fingerprintMatchThreshold
            }
            
            val isMatch = similarity >= currentThreshold
            
            Log.d("AudioDetection", "$detectionMethod fingerprint similarity: $similarity (threshold: $currentThreshold, quality: ${String.format("%.3f", quality)}). Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
            
            // Log additional diagnostic information
            if (useHighQualityFingerprinting && referenceHighQualityFingerprint != null) {
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                logHighQualityFingerprintDiagnostics(referenceHighQualityFingerprint, liveEnhancedFingerprint, comparisonResult)
            } else if (useEnhancedFingerprinting && referenceEnhancedFingerprint != null) {
                val liveEnhancedFingerprint = AudioUtils.generateEnhancedAudioFingerprint(preprocessedLivePcm, sampleRate)
                logEnhancedFingerprintDiagnostics(referenceEnhancedFingerprint, liveEnhancedFingerprint)
            }
            
            return isMatch
        } catch (e: Exception) {
            Log.e("AudioDetection", "Fingerprint detection error: ${e.message}")
            return false
        }
    }
    
    // Enhanced diagnostic logging for high-quality fingerprinting
    private fun logHighQualityFingerprintDiagnostics(reference: EnhancedAudioFingerprint, live: EnhancedAudioFingerprint, comparisonResult: FingerprintComparisonResult?) {
        if (reference.fingerprints.isNotEmpty() && live.fingerprints.isNotEmpty()) {
            val refFrame = reference.fingerprints[0]
            val liveFrame = live.fingerprints[0]
            
            Log.d("AudioDetection", "High-quality fingerprint diagnostics:")
            Log.d("AudioDetection", "  Spectral Centroid: ref=${String.format("%.1f", refFrame.spectralCentroid)}Hz, live=${String.format("%.1f", liveFrame.spectralCentroid)}Hz")
            Log.d("AudioDetection", "  Spectral Rolloff: ref=${String.format("%.1f", refFrame.spectralRolloff)}Hz, live=${String.format("%.1f", liveFrame.spectralRolloff)}Hz")
            Log.d("AudioDetection", "  Spectral Flux: ref=${String.format("%.3f", refFrame.spectralFlux)}, live=${String.format("%.3f", liveFrame.spectralFlux)}")
            
            // Log energy band differences
            val energyDiff = refFrame.energyBands.zip(liveFrame.energyBands).map { (ref, live) -> abs(ref - live) }.average()
            Log.d("AudioDetection", "  Average energy band difference: ${String.format("%.2f", energyDiff)} dB")
            
            // Log quality metrics
            comparisonResult?.let { result ->
                Log.d("AudioDetection", "  Similarity Score: ${String.format("%.3f", result.similarity)}")
                Log.d("AudioDetection", "  Quality Score: ${String.format("%.3f", result.quality)}")
                Log.d("AudioDetection", "  Match Confidence: ${if (result.quality > 0.8) "HIGH" else if (result.quality > 0.6) "MEDIUM" else "LOW"}")
            }
        }
    }
    
    // Enhanced diagnostic logging for better debugging
    private fun logEnhancedFingerprintDiagnostics(reference: EnhancedAudioFingerprint, live: EnhancedAudioFingerprint) {
        if (reference.fingerprints.isNotEmpty() && live.fingerprints.isNotEmpty()) {
            val refFrame = reference.fingerprints[0]
            val liveFrame = live.fingerprints[0]
            
            Log.d("AudioDetection", "Enhanced fingerprint diagnostics:")
            Log.d("AudioDetection", "  Spectral Centroid: ref=${String.format("%.1f", refFrame.spectralCentroid)}Hz, live=${String.format("%.1f", liveFrame.spectralCentroid)}Hz")
            Log.d("AudioDetection", "  Spectral Rolloff: ref=${String.format("%.1f", refFrame.spectralRolloff)}Hz, live=${String.format("%.1f", liveFrame.spectralRolloff)}Hz")
            Log.d("AudioDetection", "  Spectral Flux: ref=${String.format("%.3f", refFrame.spectralFlux)}, live=${String.format("%.3f", liveFrame.spectralFlux)}")
            
            // Log energy band differences
            val energyDiff = refFrame.energyBands.zip(liveFrame.energyBands).map { (ref, live) -> abs(ref - live) }.average()
            Log.d("AudioDetection", "  Average energy band difference: ${String.format("%.2f", energyDiff)} dB")
        }
    }

    private fun stopAudioDetection() {
        isDetecting = false
        detectionThread?.join(500)
        detectionThread = null
    }
    
    private fun loadCalibrationSettings() {
        val prefs = getSharedPreferences("calibration", MODE_PRIVATE)
        amplitudeThreshold = prefs.getFloat("amplitude_threshold", 140.0f).toDouble()
        fingerprintMatchThreshold = prefs.getFloat("fingerprint_match_threshold", 0.85f).toDouble()
        useEnhancedFingerprinting = prefs.getBoolean("use_enhanced_fingerprinting", true)
        useHighQualityFingerprinting = prefs.getBoolean("use_high_quality_fingerprinting", true)
        adaptiveThresholdEnabled = prefs.getBoolean("adaptive_threshold_enabled", true)
        
        Log.d("AudioDetection", "Loaded calibration settings: amplitude=$amplitudeThreshold, fingerprintThreshold=$fingerprintMatchThreshold, enhanced=$useEnhancedFingerprinting, highQuality=$useHighQualityFingerprinting, adaptive=$adaptiveThresholdEnabled")
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