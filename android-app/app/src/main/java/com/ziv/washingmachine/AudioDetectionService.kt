package com.ziv.washingmachine

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
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
    private val detectionCooldownMs = 5000L // 5 seconds
    private var consecutiveMatches = 0
    private val requiredConsecutiveMatches = 3
    private var referenceSequence: List<List<Double>> = emptyList()
    private val liveSequence: ArrayDeque<List<Double>> by lazy { ArrayDeque<List<Double>>() }
    
    // Audio analysis parameters
    private val sampleRate = 44100
    private val windowSize = 2048
    private val stepSize = windowSize / 2
    
    // Server configuration
    private val serverUrl = "http://192.168.1.119:3001" // Physical device server
    private val deviceId = "android_sensor_${System.currentTimeMillis()}"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        loadAndAnalyzeReferenceSound()
        startAudioDetection()
        registerDeviceWithServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioDetection()
        unregisterDeviceFromServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Washing Machine Listener")
            .setContentText("Listening for washing machine completion...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun registerDeviceWithServer() {
        thread {
            try {
                val fcmToken = getFCMToken()
                if (fcmToken != null) {
                    val response = sendToServer("/api/devices/register", JSONObject().apply {
                        put("token", fcmToken)
                        put("deviceInfo", JSONObject().apply {
                            put("deviceId", deviceId)
                            put("platform", "android")
                            put("appVersion", "1.0.0")
                        })
                    })
                    Log.d("AudioDetection", "Device registered with server: $response")
                }
            } catch (e: Exception) {
                Log.e("AudioDetection", "Failed to register device with server: ${e.message}")
            }
        }
    }

    private fun unregisterDeviceFromServer() {
        thread {
            try {
                val fcmToken = getFCMToken()
                if (fcmToken != null) {
                    val response = sendToServer("/api/devices/unregister", JSONObject().apply {
                        put("token", fcmToken)
                    })
                    Log.d("AudioDetection", "Device unregistered from server: $response")
                }
            } catch (e: Exception) {
                Log.e("AudioDetection", "Failed to unregister device from server: ${e.message}")
            }
        }
    }

    private fun getFCMToken(): String? {
        // This would typically come from Firebase Messaging
        // For now, we'll use a placeholder
        return "fTn9bWBvSeujTlpDWMbCuy:APA91bGP15sG0HrD-LeNp4F-H2ge1iPP_OAhynwjSjx219_hyLMIq6pYxsFgLat1st7Uv8YJLMwJD3JyTdDSTNz1OhzIVa04UJIwHBD--QHKMJvl31CMpxk"
    }

    private fun sendWashingMachineNotification() {
        thread {
            try {
                val response = sendToServer("/api/notifications/washing-machine-done", JSONObject().apply {
                    put("detectedBy", deviceId)
                })
                Log.d("AudioDetection", "Washing machine notification sent: $response")
                
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AudioDetectionService, "Notification sent to server!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("AudioDetection", "Failed to send washing machine notification: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AudioDetectionService, "Failed to send notification: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendToServer(endpoint: String, data: JSONObject): String {
        val url = URL("$serverUrl$endpoint")
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
                Toast.makeText(this, "Reference sequence created successfully", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("AudioDetection", "Failed to load or analyze reference sound: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Reference sound error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAudioDetection() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioDetection", "RECORD_AUDIO permission not granted!")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Microphone permission not granted!", Toast.LENGTH_LONG).show()
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
                    if (amplitude > 500) { // Only analyze if there's enough sound
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
                            
                            Log.d("AudioDetection", "Reference: $referenceSequence, Live: $liveSignature")
                            Log.d("AudioDetection", "Live signature: $liveSignature")
                            liveSequence.addLast(liveSignature)
                            if (liveSequence.size > referenceSequence.size) {
                                liveSequence.removeFirst()
                            }
                            if (referenceSequence.isEmpty()) {
                                Log.d("AudioDetection", "Reference sequence is empty, skipping detection.")
                                continue
                            }
                            if (liveSequence.size == referenceSequence.size) {
                                if (isSequenceMatch(
                                        liveSequence.toList(),
                                        referenceSequence,
                                        toleranceHz = 100.0,
                                        minMatchesPerWindow = 4,
                                        requiredMatchingWindows = (referenceSequence.size * 0.7).toInt()
                                    )
                                ) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastDetectionTime > detectionCooldownMs) {
                                        lastDetectionTime = now
                                        Log.d("AudioDetection", "Tune detected! Sequence matches reference.")
                                        Handler(Looper.getMainLooper()).post {
                                            Toast.makeText(this@AudioDetectionService, "Tune detected!", Toast.LENGTH_LONG).show()
                                        }
                                        // Send notification to server
                                        sendWashingMachineNotification()
                                    }
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

    private fun isSignatureMatch(
        live: List<Double>,
        reference: List<Double>?,
        toleranceHz: Double = 200.0,
        minMatches: Int = 2
    ): Boolean {
        if (reference == null || live.isEmpty()) return false
        val matches = reference.count { refFreq ->
            live.any { liveFreq -> kotlin.math.abs(liveFreq - refFreq) < toleranceHz }
        }
        return matches >= minMatches // At least minMatches frequencies match
    }

    private fun isSequenceMatch(
        live: List<List<Double>>,
        reference: List<List<Double>>,
        toleranceHz: Double = 100.0,
        minMatchesPerWindow: Int = 4,
        requiredMatchingWindows: Int = 3
    ): Boolean {
        if (live.size != reference.size) return false
        var matchingWindows = 0
        for (i in reference.indices) {
            val refFreqs = reference[i]
            val liveFreqs = live[i]
            val matches = refFreqs.count { refFreq ->
                liveFreqs.any { liveFreq -> kotlin.math.abs(liveFreq - refFreq) < toleranceHz }
            }
            if (matches >= minMatchesPerWindow) matchingWindows++
        }
        return matchingWindows >= requiredMatchingWindows
    }
} 