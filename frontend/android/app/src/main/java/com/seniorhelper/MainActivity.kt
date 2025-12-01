package com.seniorhelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_REQ_CODE = 1001
        private const val MICROPHONE_REQ_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            // Check all required permissions
            when {
                !canDrawOverApps() -> {
                    requestOverlayPermission()
                }
                !hasMicrophonePermission() -> {
                    requestMicrophonePermission()
                }
                else -> {
                    startOverlayService()
                }
            }
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Help Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun canDrawOverApps(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            Toast.makeText(
                this,
                "Please allow 'Appear on top' permission for Senior Helper",
                Toast.LENGTH_LONG
            ).show()
            startActivityForResult(intent, OVERLAY_REQ_CODE)
        }
    }

    private fun requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_REQ_CODE
            )
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Help Started - Click the green bubble!", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQ_CODE) {
            if (canDrawOverApps()) {
                // Now check microphone permission
                if (hasMicrophonePermission()) {
                    startOverlayService()
                } else {
                    requestMicrophonePermission()
                }
            } else {
                Toast.makeText(
                    this,
                    "Overlay permission is required to show the floating button.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            MICROPHONE_REQ_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
                    // Now check if we can start the service
                    if (canDrawOverApps()) {
                        startOverlayService()
                    } else {
                        requestOverlayPermission()
                    }
                } else {
                    Toast.makeText(
                        this,
                        "Microphone permission is needed for voice input",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}