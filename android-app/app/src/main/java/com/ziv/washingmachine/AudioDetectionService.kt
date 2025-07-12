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
    private val detectionCooldownMs = 10000L // 10 seconds
    private var consecutiveMatches = 0
    private val requiredConsecutiveMatches = 3 // Reduced for fingerprinting
    private var referenceFingerprint: AudioFingerprint? = null
    
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
            
            // Generate audio fingerprint from reference sound
            referenceFingerprint = AudioUtils.generateAudioFingerprint(referencePcm, sampleRate)
            
            Log.d("AudioDetection", "Reference fingerprint generated: ${referenceFingerprint?.fingerprints?.size} frames")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "✅ Reference fingerprint loaded successfully", Toast.LENGTH_LONG).show()
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
            if (referenceFingerprint == null) {
                Log.w("AudioDetection", "No reference fingerprint available")
                return false
            }
            
            val similarity = AudioUtils.compareFingerprints(referenceFingerprint, AudioUtils.generateAudioFingerprint(livePcm, sampleRate))
            val isMatch = similarity >= fingerprintMatchThreshold
            
            Log.d("AudioDetection", "Fingerprint similarity: $similarity (threshold: $fingerprintMatchThreshold). Result: ${if (isMatch) "MATCH ✅" else "NO MATCH ❌"}")
            return isMatch
        } catch (e: Exception) {
            Log.e("AudioDetection", "Fingerprint detection error: ${e.message}")
            return false
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
        
        Log.d("AudioDetection", "Loaded calibration settings: amplitude=$amplitudeThreshold, fingerprintThreshold=$fingerprintMatchThreshold")
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