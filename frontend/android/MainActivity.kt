package com.seniorhelper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val serviceIntent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Help Started", Toast.LENGTH_SHORT).show()
        }

        stopButton.setOnClickListener {
            val serviceIntent = Intent(this, OverlayService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Help Stopped", Toast.LENGTH_SHORT).show()
        }
    }
}
