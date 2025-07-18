package com.ziv.washingmachine

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.Toast
import android.util.Log
import android.widget.TextView
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Context.RECEIVER_NOT_EXPORTED

class MainActivity : AppCompatActivity() {

    private lateinit var waveformView: WaveformView
    
    companion object {
        const val ACTION_AUDIO_DATA = "com.ziv.washingmachine.AUDIO_DATA"
        const val EXTRA_AUDIO_DATA = "audio_data"
        const val EXTRA_AUDIO_DATA_LENGTH = "audio_data_length"
    }
    
    private val audioDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_AUDIO_DATA) {
                val audioDataLength = intent.getIntExtra(EXTRA_AUDIO_DATA_LENGTH, 0)
                if (audioDataLength > 0) {
                    val audioData = ShortArray(audioDataLength)
                    intent.getShortArrayExtra(EXTRA_AUDIO_DATA)?.let { data ->
                        data.copyInto(audioData, 0, 0, minOf(data.size, audioDataLength))
                        waveformView.updateAudioData(audioData)
                        waveformView.setListening(true)
                    }
                }
            }
        }
    }
    
    // Server configuration - now uses dynamic settings
    private fun getServerUrl(): String = SettingsActivity.getServerUrl(this)

    private val permissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Permissions denied: $denied", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            startAudioDetectionService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        waveformView = findViewById(R.id.waveformView)
        val serverStatusText = findViewById<TextView>(R.id.serverStatusText)
        val testServerButton = findViewById<Button>(R.id.testServerButton)
        val calibrationButton = findViewById<Button>(R.id.calibrationButton)
        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val exitAppButton = findViewById<ImageButton>(R.id.exitAppButton)

        // Start pulse animation for waveform
        waveformView.startPulseAnimation()
        
        // Load reference waveform
        loadReferenceWaveform()

        // Test server connection button
        testServerButton.setOnClickListener {
            testServerConnection()
        }

        // Calibration button
        calibrationButton.setOnClickListener {
            val intent = Intent(this, CalibrationActivity::class.java)
            startActivity(intent)
        }

        // Settings button
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Exit app button
        exitAppButton.setOnClickListener {
            showExitConfirmationDialog()
        }

        // Request permissions if not already granted
        if (!hasAllPermissions()) {
            requestPermissionsLauncher.launch(permissions)
        } else {
            startAudioDetectionService()
        }

        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "default_channel",
                "Default Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // Test server connection on startup
        testServerConnection()
        
        // Log which server URL is being used
        Log.d("MainActivity", "Using server URL: ${getServerUrl()}")
        
        // Register broadcast receiver for audio data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(audioDataReceiver, IntentFilter(ACTION_AUDIO_DATA), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(audioDataReceiver, IntentFilter(ACTION_AUDIO_DATA))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh server connection when returning from settings
        testServerConnection()
    }

    private fun startWaveformAnimation() {
        waveformView.startPulseAnimation()
    }

    private fun stopWaveformAnimation() {
        waveformView.stopPulseAnimation()
    }

    private fun loadReferenceWaveform() {
        try {
            // Load only completion_tune.m4a as the reference waveform
            val m4aPcm = AudioUtils.loadAudioResource(this, R.raw.completion_tune)
            waveformView.setReferenceWaveform(m4aPcm)
            Log.d("MainActivity", "Reference waveform loaded from completion_tune.m4a")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load reference waveform: ${e.message}")
            waveformView.clearReferenceWaveform() // Clear waveform if loading fails
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWaveformAnimation()
        unregisterReceiver(audioDataReceiver)
    }

    private fun testServerConnection() {
        val serverStatusText = findViewById<TextView>(R.id.serverStatusText)
        val serverUrl = getServerUrl()
        serverStatusText.text = "Server: Testing connection..."
        
        thread {
            try {
                // Wake up the server first
                wakeUpServer(serverUrl)
                
                val url = URL("$serverUrl/api/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                connection.disconnect()
                
                runOnUiThread {
                    if (responseCode == 200) {
                        serverStatusText.text = "Server: Connected ✓"
                        serverStatusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    } else {
                        serverStatusText.text = "Server: Error ($responseCode)"
                        serverStatusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Server connection failed: ${e.message}")
                runOnUiThread {
                    serverStatusText.text = "Server: Disconnected ✗"
                    serverStatusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
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
            
            Log.d("MainActivity", "Server wake-up call successful: $responseCode - $response")
        } catch (e: Exception) {
            Log.w("MainActivity", "Server wake-up call failed: ${e.message}")
            // Don't throw here - we'll still try to test the connection
        }
    }

    private fun hasAllPermissions(): Boolean =
        permissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    // Start the audio detection service
    private fun startAudioDetectionService() {
        val serviceIntent = android.content.Intent(this, AudioDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("This will stop the washing machine monitoring service and close the app completely. Are you sure?")
            .setPositiveButton("Exit") { _, _ ->
                exitApp()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exitApp() {
        // Stop the audio detection service
        val serviceIntent = Intent(this, AudioDetectionService::class.java)
        stopService(serviceIntent)
        
        // Stop animations
        stopWaveformAnimation()
        
        // Show toast
        Toast.makeText(this, "App closed. Service stopped.", Toast.LENGTH_SHORT).show()
        
        // Close the app
        finishAffinity() // This closes all activities in the task
        System.exit(0) // Force close the app process
    }
}