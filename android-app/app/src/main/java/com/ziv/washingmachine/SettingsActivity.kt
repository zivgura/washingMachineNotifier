package com.ziv.washingmachine

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var serverRadioGroup: RadioGroup
    private lateinit var localServerRadio: RadioButton
    private lateinit var productionServerRadio: RadioButton
    private lateinit var customServerRadio: RadioButton
    private lateinit var customServerInput: EditText
    private lateinit var saveButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var statusText: TextView
    
    private lateinit var prefs: SharedPreferences
    
    companion object {
        const val PREFS_NAME = "server_settings"
        const val KEY_SERVER_TYPE = "server_type"
        const val KEY_CUSTOM_SERVER_URL = "custom_server_url"
        
        const val SERVER_TYPE_LOCAL = "local"
        const val SERVER_TYPE_PRODUCTION = "production"
        const val SERVER_TYPE_CUSTOM = "custom"
        
        fun getServerUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return when (prefs.getString(KEY_SERVER_TYPE, SERVER_TYPE_PRODUCTION)) {
                SERVER_TYPE_LOCAL -> "http://192.168.1.119:3001"
                SERVER_TYPE_PRODUCTION -> "https://washing-machine-server.onrender.com"
                SERVER_TYPE_CUSTOM -> prefs.getString(KEY_CUSTOM_SERVER_URL, BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL
                else -> BuildConfig.SERVER_URL
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createSettingsLayout())
        
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        setupUI()
        loadSettings()
    }
    
    private fun createSettingsLayout(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // Title
        layout.addView(TextView(this).apply {
            text = "⚙️ Server Settings"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        })
        
        // Server selection
        layout.addView(TextView(this).apply {
            text = "Choose Server:"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        })
        
        serverRadioGroup = RadioGroup(this)
        
        localServerRadio = RadioButton(this).apply {
            text = "🏠 Local Server (192.168.1.119:3001)"
            id = 1
        }
        serverRadioGroup.addView(localServerRadio)
        
        productionServerRadio = RadioButton(this).apply {
            text = "☁️ Production Server (Render)"
            id = 2
        }
        serverRadioGroup.addView(productionServerRadio)
        
        customServerRadio = RadioButton(this).apply {
            text = "🔧 Custom Server"
            id = 3
        }
        serverRadioGroup.addView(customServerRadio)
        
        layout.addView(serverRadioGroup)
        
        // Custom server input
        layout.addView(TextView(this).apply {
            text = "Custom Server URL:"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })
        
        customServerInput = EditText(this).apply {
            hint = "https://your-server.com"
            setPadding(16, 16, 16, 16)
            background = resources.getDrawable(android.R.drawable.edit_text, null)
        }
        layout.addView(customServerInput)
        
        // Status
        statusText = TextView(this).apply {
            text = "Status: Ready"
            textSize = 14f
            setPadding(0, 24, 0, 0)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(statusText)
        
        // Buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }
        
        testConnectionButton = Button(this).apply {
            text = "Test Connection"
            setOnClickListener { testConnection() }
        }
        buttonLayout.addView(testConnectionButton)
        
        saveButton = Button(this).apply {
            text = "Save Settings"
            setOnClickListener { saveSettings() }
            setPadding(32, 0, 0, 0)
        }
        buttonLayout.addView(saveButton)
        
        layout.addView(buttonLayout)
        
        return layout
    }
    
    private fun setupUI() {
        serverRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            customServerInput.isEnabled = checkedId == 3
        }
    }
    
    private fun loadSettings() {
        val serverType = prefs.getString(KEY_SERVER_TYPE, SERVER_TYPE_PRODUCTION)
        val customUrl = prefs.getString(KEY_CUSTOM_SERVER_URL, "")
        
        when (serverType) {
            SERVER_TYPE_LOCAL -> localServerRadio.isChecked = true
            SERVER_TYPE_PRODUCTION -> productionServerRadio.isChecked = true
            SERVER_TYPE_CUSTOM -> customServerRadio.isChecked = true
        }
        
        customServerInput.setText(customUrl)
        customServerInput.isEnabled = serverType == SERVER_TYPE_CUSTOM
        
        updateStatus()
    }
    
    private fun saveSettings() {
        val serverType = when (serverRadioGroup.checkedRadioButtonId) {
            1 -> SERVER_TYPE_LOCAL
            2 -> SERVER_TYPE_PRODUCTION
            3 -> SERVER_TYPE_CUSTOM
            else -> SERVER_TYPE_PRODUCTION
        }
        
        val customUrl = customServerInput.text.toString().trim()
        
        prefs.edit().apply {
            putString(KEY_SERVER_TYPE, serverType)
            putString(KEY_CUSTOM_SERVER_URL, customUrl)
            apply()
        }
        
        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
        updateStatus()
        
        Log.d("Settings", "Server settings saved: type=$serverType, customUrl=$customUrl")
    }
    
    private fun testConnection() {
        statusText.text = "Status: Testing connection..."
        statusText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        
        val serverUrl = getCurrentServerUrl()
        
        Thread {
            try {
                // Wake up the server first
                wakeUpServer(serverUrl)
                
                val url = java.net.URL("$serverUrl/api/health")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                val response = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream)).use { it.readText() }
                connection.disconnect()
                
                runOnUiThread {
                    if (responseCode == 200) {
                        statusText.text = "Status: ✅ Connection successful!"
                        statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    } else {
                        statusText.text = "Status: ❌ Error ($responseCode)"
                        statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Status: ❌ Connection failed: ${e.message}"
                    statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                }
            }
        }.start()
    }
    
    private fun wakeUpServer(serverUrl: String) {
        try {
            val url = java.net.URL("$serverUrl/api/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            val response = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()
            
            Log.d("Settings", "Server wake-up call successful: $responseCode - $response")
        } catch (e: Exception) {
            Log.w("Settings", "Server wake-up call failed: ${e.message}")
            // Don't throw here - we'll still try to test the connection
        }
    }
    
    private fun getCurrentServerUrl(): String {
        return when (serverRadioGroup.checkedRadioButtonId) {
            1 -> "http://192.168.1.119:3001"
            2 -> "https://washing-machine-server.onrender.com"
            3 -> customServerInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: BuildConfig.SERVER_URL
            else -> BuildConfig.SERVER_URL
        }
    }
    
    private fun updateStatus() {
        val currentUrl = getServerUrl(this)
        statusText.text = "Current server: $currentUrl"
        statusText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
    }
} 