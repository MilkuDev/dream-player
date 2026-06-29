package org.milkdev.dreamplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.milkdev.dreamplayer.app.App


class MainActivity : ComponentActivity() {
    private var isPermissionGranted by mutableStateOf(false)

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

        if (granted) {
            isPermissionGranted = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        isPermissionGranted = hasAudioLibraryPermission()

        setContent {
            App(isPermissionGranted = isPermissionGranted)
        }

        if (!isPermissionGranted) {
            audioPermissionLauncher.launch(arrayOf(audioLibraryPermission()))
        }
    }
    private fun hasAudioLibraryPermission(): Boolean {
        return checkSelfPermission(audioLibraryPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun audioLibraryPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
}
