package com.example.screenrec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var micEnabled by mutableStateOf(true)
    private var isRecording by mutableStateOf(false)

    private val projectionLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_CODE, result.resultCode)
                    putExtra(RecordingService.EXTRA_DATA, data)
                    putExtra(RecordingService.EXTRA_MIC, micEnabled)
                }
                startForegroundService(intent)
                isRecording = true
            }
        }

    private val permissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                launchProjection()
            } else {
                toast(getString(R.string.audio_permission_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        micEnabled = micEnabled,
                        isRecording = isRecording,
                        onMicToggle = { micEnabled = it },
                        onStart = ::onStartClick,
                        onStop = ::onStopClick,
                    )
                }
            }
        }
    }

    private fun onStartClick() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            launchProjection()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun onStopClick() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

@androidx.compose.runtime.Composable
private fun RecorderScreen(
    micEnabled: Boolean,
    isRecording: Boolean,
    onMicToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mic_label), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Switch(checked = micEnabled, onCheckedChange = onMicToggle, enabled = !isRecording)
        }
        Spacer(Modifier.height(48.dp))
        if (!isRecording) {
            Button(onClick = onStart) { Text(stringResource(R.string.start_recording)) }
        } else {
            Text(stringResource(R.string.recording_hint))
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStop) { Text(stringResource(R.string.stop)) }
        }
    }
}
