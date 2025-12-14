package com.mobileaihelper

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
        val screenShareButton = findViewById<Button>(R.id.stopButton)

        // Button 1: Start Voice Helper (Overlay)
        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        // Button 2: Trigger Screen Share (Jitsi)
        // This sends a command to the ALREADY RUNNING service
        screenShareButton.text = "Start Screen Share"
        screenShareButton.setOnClickListener {
            val intent = Intent(this, OverlayService::class.java)
            intent.action = "START_SCREEN_SHARE"
            startService(intent) // This calls onStartCommand in the existing service
        }
    }

    // --- Standard Permissions Logic ---
    private fun checkPermissionsAndStart() {
        if (!canDrawOverApps()) {
            requestOverlayPermission(); return
        }
        if (!hasMicrophonePermission()) {
            requestMicrophonePermission(); return
        }
        startOverlayService()
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Voice Helper Started", Toast.LENGTH_SHORT).show()
    }

    // ... (Keep existing permission helper functions: canDrawOverApps, requestOverlayPermission, etc.) ...
    private fun canDrawOverApps(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_REQ_CODE)
        }
    }
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestMicrophonePermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQ_CODE)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQ_CODE && canDrawOverApps()) checkPermissionsAndStart()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_REQ_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) checkPermissionsAndStart()
    }
}