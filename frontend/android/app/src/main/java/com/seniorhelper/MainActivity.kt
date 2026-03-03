package com.mobileaihelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var sessionTextView : TextView
    companion object {
        private const val MICROPHONE_REQ_CODE = 1002
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canDrawOverApps()) {
            checkPermissionsAndStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        // 1. Start Voice Helper (Overlay)
        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }


        // 2. Stop Everything (Removes Bubble & Kills Service)
        stopButton.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Help Stopped", Toast.LENGTH_SHORT).show()
        }
        sessionTextView = findViewById<TextView>(R.id.sessionIdDisplayText)
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
        fun generateSessionId(): String {
            return Random.nextInt(1000, 9999).toString()
        }
        val generatedSessionNumber : String = generateSessionId()
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("sessionID", generatedSessionNumber)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        sessionTextView.text = generatedSessionNumber
        Toast.makeText(this, "세션 : $generatedSessionNumber 에서 도우미가 활성화되었습니다.", Toast.LENGTH_SHORT).show()
    }

    // --- Boilerplate Permission Helpers ---
    private fun canDrawOverApps(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicrophonePermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQ_CODE)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_REQ_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) checkPermissionsAndStart()
    }
}
