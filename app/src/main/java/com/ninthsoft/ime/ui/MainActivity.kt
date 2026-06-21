package com.ninthsoft.ime.ui

import android.content.ComponentName
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.ui.screen.MainScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private val setupImeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) finish()
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (!isImeConfigured()) {
            setupImeLauncher.launch(Intent(this, SetupActivity::class.java))
        }

        setContent {
            var themeMode by remember { mutableIntStateOf(ThemeManager.Theme.getMode(this@MainActivity)) }

            ImeTheme(themeMode = themeMode) {
                MainScreen(
                    currentThemeMode = themeMode,
                    onThemeModeChanged = { mode ->
                        ThemeManager.Theme.setMode(this@MainActivity, mode)
                        themeMode = mode
                    },
                    onOpenImeSetup = {
                        setupImeLauncher.launch(
                            Intent(
                                this@MainActivity,
                                SetupActivity::class.java
                            )
                        )
                    },
                    onOpenKeyboardSettings = {
                        startActivity(
                            Intent(
                                this@MainActivity,
                                KeyboardSettingsActivity::class.java
                            )
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val dx = Math.abs(x - lastX)
        val dy = Math.abs(y - lastY)
        val dz = Math.abs(z - lastZ)

        lastX = x
        lastY = y
        lastZ = z

        val delta = dx + dy + dz
        if (delta > 30f) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > 800) {
                lastShakeTime = now
                openFileBrowser()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun openFileBrowser() {
        val uri = DocumentsContract.buildRootUri("$packageName.provider", "files")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun isImeConfigured(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val ourComponent = ComponentName(this, ImeInputMethodService::class.java)
        val isEnabled = imm.enabledInputMethodList.any {
            it.packageName == ourComponent.packageName && it.serviceName == ourComponent.className
        }
        val currentId =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ourId = ourComponent.flattenToShortString()
        return isEnabled && currentId == ourId
    }
}
