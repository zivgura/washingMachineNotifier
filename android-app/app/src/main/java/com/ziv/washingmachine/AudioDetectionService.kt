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
    
    // Alternative detection methods
    private var useCrossCorrelation = true
    private var useSpectralSimilarity = true
    private var useAmplitudePattern = true
    private var detectionMethod = "hybrid" // "fft", "cross_correlation", "spectral", "amplitude", "hybrid"
    
    // Reference data for alternative methods
    private var referenceAmplitudePattern: List<Double> = emptyList()
    private var referenceSpectralProfile: List<Double> = emptyList()
    private var referenceCrossCorrelation: List<Double> = emptyList()

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
            
            // Analyze reference using multiple methods
            analyzeReferenceWithMultipleMethods(referencePcm)
            
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
    
    private fun analyzeReferenceWithMultipleMethods(referencePcm: ShortArray) {
        // Method 1: Traditional FFT frequency analysis
        val freqLists = mutableListOf<List<Double>>()
        var i = 0
        while (i + windowSize <= referencePcm.size) {
            val window = referencePcm.sliceArray(i until i + windowSize)
            val fft = AudioUtils.computeFFT(window)
            val domFreqs = AudioUtils.getDominantFrequencies(fft, sampleRate = sampleRate)
            freqLists.add(domFreqs)
            i += stepSize
        }
        referenceSequence = freqLists
        
        // Method 2: Amplitude pattern analysis
        referenceAmplitudePattern = analyzeAmplitudePattern(referencePcm)
        
        // Method 3: Spectral profile analysis
        referenceSpectralProfile = analyzeSpectralProfile(referencePcm)
        
        // Method 4: Cross-correlation template
        referenceCrossCorrelation = createCrossCorrelationTemplate(referencePcm)
        
        Log.d("AudioDetection", "Reference analysis complete:")
        Log.d("AudioDetection", "- FFT windows: ${referenceSequence.size}")
        Log.d("AudioDetection", "- Amplitude pattern length: ${referenceAmplitudePattern.size}")
        Log.d("AudioDetection", "- Spectral profile length: ${referenceSpectralProfile.size}")
        Log.d("AudioDetection", "- Cross-correlation template length: ${referenceCrossCorrelation.size}")
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
            // Create a spectral profile by averaging frequency bins
            val spectralAvg = fft.take(fft.size / 4).average() // Use first quarter of spectrum
            profile.add(spectralAvg)
            i += windowSize / 2
        }
        return profile
    }
    
    private fun createCrossCorrelationTemplate(pcm: ShortArray): List<Double> {
        // Create a template for cross-correlation matching
        val template = mutableListOf<Double>()
        val windowSize = 1024
        var i = 0
        while (i + windowSize <= pcm.size) {
            val window = pcm.sliceArray(i until i + windowSize)
            // Normalize the window
            val maxVal = window.map { it.toDouble().absoluteValue }.maxOrNull() ?: 1.0
            val normalized = window.map { it.toDouble() / maxVal }
            template.addAll(normalized)
            i += windowSize
        }
        return template
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
                        val window = buffer.take(windowSize.coerceAtMost(read)).toShortArray()
                        if (window.size == windowSize) {
                            // Try multiple detection methods
                            val detectionResults = mutableListOf<Boolean>()
                            
                            // Method 1: Traditional FFT frequency matching
                            if (detectionMethod == "fft" || detectionMethod == "hybrid") {
                                detectionResults.add(detectWithFFT(window))
                            }
                            
                            // Method 2: Cross-correlation
                            if (detectionMethod == "cross_correlation" || detectionMethod == "hybrid") {
                                detectionResults.add(detectWithCrossCorrelation(window))
                            }
                            
                            // Method 3: Spectral similarity
                            if (detectionMethod == "spectral" || detectionMethod == "hybrid") {
                                detectionResults.add(detectWithSpectralSimilarity(window))
                            }
                            
                            // Method 4: Amplitude pattern matching
                            if (detectionMethod == "amplitude" || detectionMethod == "hybrid") {
                                detectionResults.add(detectWithAmplitudePattern(window))
                            }
                            
                            // Combine results based on detection method
                            val isMatch = when (detectionMethod) {
                                "hybrid" -> detectionResults.count { it } >= 2 // At least 2 methods must agree
                                else -> detectionResults.isNotEmpty() && detectionResults.all { it }
                            }
                            
                            if (isMatch) {
                                consecutiveMatches++
                                Log.d("AudioDetection", "Consecutive matches: $consecutiveMatches/$requiredConsecutiveMatches")
                                
                                if (consecutiveMatches >= requiredConsecutiveMatches) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastDetectionTime > detectionCooldownMs) {
                                        lastDetectionTime = now
                                        consecutiveMatches = 0
                                        Log.d("AudioDetection", "🎵 Tune detected! Consecutive matches reached threshold.")
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
                                    Log.d("AudioDetection", "No match, resetting consecutive counter from $consecutiveMatches to 0")
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
    
    private fun detectWithFFT(window: ShortArray): Boolean {
        if (referenceSequence.isEmpty()) return false
        
        val liveFft = AudioUtils.computeFFT(window)
        val liveSignature = AudioUtils.getDominantFrequencies(liveFft, sampleRate = sampleRate)
        
        if (liveSignature.isEmpty()) return false
        
        val referenceWindow = referenceSequence.firstOrNull() ?: return false
        val matches = referenceWindow.count { refFreq ->
            liveSignature.any { liveFreq -> 
                kotlin.math.abs(liveFreq - refFreq) < frequencyTolerance 
            }
        }
        
        val isMatch = matches >= minMatchesPerWindow
        Log.d("AudioDetection", "FFT detection: $matches/${referenceWindow.size} frequencies matched. Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
        return isMatch
    }
    
    private fun detectWithCrossCorrelation(window: ShortArray): Boolean {
        if (referenceCrossCorrelation.isEmpty()) return false
        
        // Normalize the live window
        val maxVal = window.map { it.toDouble().absoluteValue }.maxOrNull() ?: 1.0
        val normalizedWindow = window.map { it.toDouble() / maxVal }
        
        // Compute cross-correlation
        val correlation = computeCrossCorrelation(normalizedWindow, referenceCrossCorrelation)
        val maxCorrelation = correlation.maxOrNull() ?: 0.0
        
        val isMatch = maxCorrelation > 0.7 // Threshold for correlation
        Log.d("AudioDetection", "Cross-correlation detection: max correlation = $maxCorrelation. Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
        return isMatch
    }
    
    private fun detectWithSpectralSimilarity(window: ShortArray): Boolean {
        if (referenceSpectralProfile.isEmpty()) return false
        
        val fft = AudioUtils.computeFFT(window)
        val spectralAvg = fft.take(fft.size / 4).average()
        
        // Compare with reference spectral profile
        val referenceAvg = referenceSpectralProfile.average()
        val similarity = 1.0 - kotlin.math.abs(spectralAvg - referenceAvg) / referenceAvg
        
        val isMatch = similarity > 0.6 // Threshold for spectral similarity
        Log.d("AudioDetection", "Spectral detection: similarity = $similarity. Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
        return isMatch
    }
    
    private fun detectWithAmplitudePattern(window: ShortArray): Boolean {
        if (referenceAmplitudePattern.isEmpty()) return false
        
        val rms = sqrt(window.map { it.toDouble() * it.toDouble() }.average())
        
        // Compare with reference amplitude pattern
        val referenceAvg = referenceAmplitudePattern.average()
        val similarity = 1.0 - kotlin.math.abs(rms - referenceAvg) / referenceAvg
        
        val isMatch = similarity > 0.5 // Threshold for amplitude similarity
        Log.d("AudioDetection", "Amplitude detection: similarity = $similarity. Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
        return isMatch
    }
    
    private fun computeCrossCorrelation(signal1: List<Double>, signal2: List<Double>): List<Double> {
        val result = mutableListOf<Double>()
        val n1 = signal1.size
        val n2 = signal2.size
        
        for (lag in -n1 + 1 until n2) {
            var sum = 0.0
            for (i in 0 until n1) {
                val j = i + lag
                if (j >= 0 && j < n2) {
                    sum += signal1[i] * signal2[j]
                }
            }
            result.add(sum)
        }
        
        return result
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
        detectionMethod = prefs.getString("detection_method", "hybrid") ?: "hybrid"
        
        Log.d("AudioDetection", "Loaded calibration settings: tolerance=$frequencyTolerance, minMatches=$minMatchesPerWindow, amplitude=$amplitudeThreshold, method=$detectionMethod")
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