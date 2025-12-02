package com.mobileaihelper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
        private const val SCREEN_CAPTURE_REQ_CODE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            startHelpFlow()
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Help Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startHelpFlow() {
        // 1. Check Overlay
        if (!canDrawOverApps()) {
            requestOverlayPermission()
            return
        }
        // 2. Check Mic
        if (!hasMicrophonePermission()) {
            requestMicrophonePermission()
            return
        }
        // 3. Request Screen Capture (Required for Daily Native)
        requestScreenCapture()
    }

    // --- Permissions Logic ---

    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // This opens the system dialog "Start recording or casting...?"
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ_CODE)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = "START_SESSION"
            // Pass the screen capture permission to the service
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Starting Help...", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_REQ_CODE -> startHelpFlow() // Retry flow
            SCREEN_CAPTURE_REQ_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Success! Start the Service with the permission data
                    startOverlayService(resultCode, data)
                } else {
                    Toast.makeText(this, "Screen sharing is required.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Boilerplate Permission Checks ---
    private fun canDrawOverApps(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_REQ_CODE)
        }
    }
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestMicrophonePermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQ_CODE)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_REQ_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startHelpFlow()
    }
}