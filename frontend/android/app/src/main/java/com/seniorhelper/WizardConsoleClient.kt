package com.seniorhelper

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WizardConsoleClient(
    private val serverUrl: String,
    private val sessionId: String,
    private val onMessageReceived: (String) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit
) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var lastMessageCount = 0
    
    companion object {
        private const val TAG = "WizardConsoleClient"
        private const val POLL_INTERVAL = 2000L // Poll every 2 seconds
    }
    
    fun connect() {
        Log.d(TAG, "Connecting to server: $serverUrl with session ID: $sessionId")
        
        // Test connection
        testConnection()
        
        // Start polling for new messages
        startPolling()
    }
    
    private fun testConnection() {
        val request = Request.Builder()
            .url("$serverUrl/")
            .get()
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Connection test failed: ${e.message}", e)
                handler.post {
                    onConnectionStatusChanged(false)
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                response.close()
                
                Log.d(TAG, if (success) "Connection test successful" else "Connection test failed: ${response.code}")
                handler.post {
                    onConnectionStatusChanged(success)
                }
            }
        })
    }
    
    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        pollForMessages()
    }
    
    private fun pollForMessages() {
        if (!isPolling) return
        
        val request = Request.Builder()
            .url("$serverUrl/sessions/$sessionId")
            .get()
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Polling failed: ${e.message}")
                // Continue polling even on failure
                handler.postDelayed({ pollForMessages() }, POLL_INTERVAL)
            }
            
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            try {
                                val json = JSONObject(body)
                                val messages = json.getJSONArray("messages")
                                
                                // Check if there are new messages
                                val messageCount = messages.length()
                                if (messageCount > lastMessageCount) {
                                    // Process new messages
                                    for (i in lastMessageCount until messageCount) {
                                        val message = messages.getJSONObject(i)
                                        val role = message.getString("role")
                                        val text = message.getString("text")
                                        
                                        // Only notify if it's from wizard/assistant
                                        if (role == "wizard" || role == "assistant") {
                                            handler.post {
                                                onMessageReceived(text)
                                            }
                                        }
                                    }
                                    lastMessageCount = messageCount
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing messages: ${e.message}", e)
                            }
                        }
                    } else if (response.code == 404) {
                        // Session doesn't exist yet, that's okay
                        Log.d(TAG, "Session not found yet, will be created on first message")
                    } else {
                        Log.w(TAG, "Unexpected response code: ${response.code}")
                    }
                } finally {
                    response.close()
                    // Continue polling
                    handler.postDelayed({ pollForMessages() }, POLL_INTERVAL)
                }
            }
        })
    }
    
    fun sendMessage(message: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("role", "user")
            put("text", message)
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toString().toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url("$serverUrl/message")
            .post(requestBody)
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send message: ${e.message}", e)
            }
            
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                response.close()
                
                if (success) {
                    Log.d(TAG, "Message sent successfully: $message")
                } else {
                    Log.e(TAG, "Failed to send message: ${response.code}")
                }
            }
        })
    }
    
    fun disconnect() {
        isPolling = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            onConnectionStatusChanged(false)
        }
        Log.d(TAG, "Disconnected")
    }
    
    fun isConnected(): Boolean {
        return isPolling
    }
}