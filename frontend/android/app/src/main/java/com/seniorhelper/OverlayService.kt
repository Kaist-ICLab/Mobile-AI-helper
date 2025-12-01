package com.seniorhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.random.Random

class OverlayService : Service() {

    // ==================== TESTING MODE ====================
    private val USE_GEMINI_FOR_TESTING = true
    // ======================================================

    // ------------------ GEMINI API ------------------
    private val GEMINI_API_KEY = "AIzaSyAZT7tOTdn1QnnAgsWqm4GVSvyJ1_d_-5A"

    // ------------------ CLOVA API KEYS ------------------
    private val TTS_KEY_ID = "INSERT_CLOVA_TTS_CLIENT_ID_HERE"
    private val TTS_KEY = "INSERT_CLOVA_TTS_CLIENT_SECRET_HERE"
    private val STT_KEY_ID = "INSERT_CLOVA_STT_CLIENT_ID_HERE"
    private val STT_KEY = "INSERT_CLOVA_STT_CLIENT_SECRET_HERE"
    private val TTS_VOICE = "nara"

    // ------------------ UI ------------------
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var chatView: View? = null
    private var isChatVisible = false

    private lateinit var micButton: ImageButton
    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var sessionIdText: TextView

    // Only ONE message visible at a time!
    private var currentMessageView: View? = null

    // ------------------ Wizard Console ------------------
    private var wizardClient: WizardConsoleClient? = null
    private val sessionId = generateSessionId()
    private val serverUrl = "http://172.20.60.72:8000"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------ Recording ------------------
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    companion object {
        private const val TAG = "OverlayService"
        private fun generateSessionId(): String {
            return Random.nextInt(1000, 9999).toString()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Voice-only service created - Session: $sessionId")

        try {
            startAsForegroundService()
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            connectToWizardConsole()
            showBubble()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wizardClient?.disconnect()
            stopRecording()
            bubbleView?.let { windowManager.removeView(it) }
            chatView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
    }

    private fun connectToWizardConsole() {
        wizardClient = WizardConsoleClient(
            serverUrl = serverUrl,
            sessionId = sessionId,
            onMessageReceived = { message ->
                mainHandler.post {
                    if (::messagesContainer.isInitialized) {
                        showAssistantResponse(message)
                        messagesScroll.post {
                            messagesScroll.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            },
            onConnectionStatusChanged = { isConnected ->
                mainHandler.post {
                    Log.d(TAG, "Connection: $isConnected")
                }
            }
        )
        wizardClient?.connect()
    }

    private fun startAsForegroundService() {
        val channelId = "senior_helper_overlay"
        val channelName = "Senior Helper"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle("Senior Helper - ID: $sessionId")
                    .setContentText("Voice Assistant Ready")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Senior Helper - ID: $sessionId")
                    .setContentText("Voice Assistant Ready")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            }

        startForeground(1, notification)
    }

    private fun showBubble() {
        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF42A5F5.toInt())
            }
            background = drawable
            setPadding(10, 10, 10, 10)
        }

        val questionMark = TextView(this).apply {
            text = "?"
            textSize = 37f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        bubbleLayout.addView(questionMark)

        val layoutParams = WindowManager.LayoutParams(
            160, 160,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.END
        layoutParams.x = 20
        layoutParams.y = 200

        bubbleLayout.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0
            private var lastY = 0
            private var touchedX = 0f
            private var touchedY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = layoutParams.x
                        lastY = layoutParams.y
                        touchedX = event.rawX
                        touchedY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - touchedX
                        val deltaY = event.rawY - touchedY
                        if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) {
                            isDragging = true
                        }
                        layoutParams.x = lastX - deltaX.toInt()
                        layoutParams.y = lastY + deltaY.toInt()
                        windowManager.updateViewLayout(bubbleLayout, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            toggleChatWindow()
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = bubbleLayout
        windowManager.addView(bubbleView, layoutParams)
    }

    private fun toggleChatWindow() {
        if (isChatVisible) hideChatWindow() else showChatWindow()
    }

    private fun showChatWindow() {
        if (chatView != null) return

        val chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(0xFFFFFFFF.toInt())
            }
            background = drawable
            clipToOutline = true
        }

        // Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(40f, 40f, 40f, 40f, 0f, 0f, 0f, 0f)
                setColor(0xFF42A5F5.toInt())
            }
            background = drawable
            setPadding(24, 24, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(this).apply {
            text = "💬 Helper Chat"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(110, 110)
            setColorFilter(Color.WHITE)
            setOnClickListener { hideChatWindow() }
        }

        topRow.addView(titleText)
        topRow.addView(closeButton)

        sessionIdText = TextView(this).apply {
            text = "Session: $sessionId"
            textSize = 14f
            setTextColor(0xFFE3F2FD.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        headerLayout.addView(topRow)
        headerLayout.addView(sessionIdText)

        // Messages area - ONE message at a time
        messagesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(0xFFF8F9FA.toInt())
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 30, 20, 30)
            gravity = Gravity.CENTER
        }

        messagesScroll.addView(messagesContainer)

        // Voice control - ONE BIG MIC BUTTON IN THE MIDDLE
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.WHITE)
            elevation = 8f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Big microphone button - press to start, press to stop recording
        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now) // Mic icon
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF42A5F5.toInt()) // Blue
            }
            background = drawable
            layoutParams = LinearLayout.LayoutParams(200, 200) // HUGE button
            setColorFilter(Color.WHITE)
            elevation = 8f
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(40, 40, 40, 40)
            setOnClickListener { 
                if (!isRecording) {
                    startRecording()
                } else {
                    stopRecording()
                }
            }
        }

        controlsLayout.addView(micButton)

        chatLayout.addView(headerLayout)
        chatLayout.addView(messagesScroll)
        chatLayout.addView(controlsLayout)

        val chatParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, 1100,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        chatParams.gravity = Gravity.BOTTOM
        chatParams.y = 100

        chatView = chatLayout
        windowManager.addView(chatView, chatParams)
        isChatVisible = true
    }

    private fun hideChatWindow() {
        if (isRecording) {
            stopRecording()
        }
        chatView?.let {
            windowManager.removeView(it)
            chatView = null
            isChatVisible = false
        }
    }

    // Clear and show only ONE message
    private fun clearMessage() {
        currentMessageView?.let {
            messagesContainer.removeView(it)
            currentMessageView = null
        }
    }

    private fun showUserMessage(message: String) {
        clearMessage() // Remove any previous message

        val messageView = TextView(this).apply {
            text = message
            textSize = 24f // Big text for seniors
            setTextColor(Color.WHITE)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 35f
                setColor(0xFF42A5F5.toInt())
            }
            background = drawable
            setPadding(40, 35, 40, 35)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(30, 20, 30, 20)
            }
            elevation = 6f
        }
        messagesContainer.addView(messageView)
        currentMessageView = messageView
    }

    private fun showLoadingBubbles() {
        clearMessage() // Remove previous message

        val loadingView = TextView(this).apply {
            text = "●  ●  ●"
            textSize = 40f
            setTextColor(0xFF90CAF9.toInt())
            gravity = Gravity.CENTER
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 45f
                setColor(0xFFE3F2FD.toInt())
            }
            background = drawable
            setPadding(60, 50, 60, 50)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(30, 30, 30, 20)
            }
        }
        messagesContainer.addView(loadingView)
        currentMessageView = loadingView
    }

    private fun showAssistantResponse(message: String) {
        clearMessage() // Remove previous message

        val messageView = TextView(this).apply {
            text = message
            textSize = 24f // Big text
            setTextColor(0xFF1565C0.toInt())
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 35f
                setColor(0xFFE3F2FD.toInt())
            }
            background = drawable
            setPadding(40, 35, 40, 35)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(30, 10, 30, 20)
            }
            elevation = 4f
        }
        messagesContainer.addView(messageView)
        currentMessageView = messageView

        // Speak response
        if (USE_GEMINI_FOR_TESTING) {
            geminiTTS(message)
        } else {
            clovaTTS(message)
        }
    }

    private fun updateMicButton(recording: Boolean) {
        if (::micButton.isInitialized) {
            if (recording) {
                // RED when recording
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE53935.toInt())
                }
                micButton.background = drawable
                micButton.setColorFilter(Color.WHITE)
                
                // Show "Recording..." message
                showUserMessage("말씀하세요...")
            } else {
                // BLUE when ready
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF42A5F5.toInt())
                }
                micButton.background = drawable
                micButton.setColorFilter(Color.WHITE)
            }
        }
    }

    private fun startRecording() {
        Thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
                )
                isRecording = true
                
                mainHandler.post { updateMicButton(true) }
                
                recorder?.startRecording()

                val pcmBuffer = ByteArrayOutputStream()
                val tempBuf = ByteArray(bufferSize)

                while (isRecording) {
                    val read = recorder!!.read(tempBuf, 0, tempBuf.size)
                    if (read > 0) pcmBuffer.write(tempBuf, 0, read)
                }

                val audioBytes = pcmBuffer.toByteArray()
                
                mainHandler.post { showLoadingBubbles() }
                
                if (USE_GEMINI_FOR_TESTING) {
                    geminiSTT(audioBytes) { text ->
                        mainHandler.post {
                            updateMicButton(false)
                            if (text != null) {
                                // Show transcribed text
                                showUserMessage(text)
                                
                                // Wait 1 second then send
                                mainHandler.postDelayed({
                                    showLoadingBubbles()
                                    wizardClient?.sendMessage(text)
                                }, 1000)
                            }
                        }
                    }
                } else {
                    clovaSTT(audioBytes) { text ->
                        mainHandler.post {
                            updateMicButton(false)
                            if (text != null) {
                                showUserMessage(text)
                                mainHandler.postDelayed({
                                    showLoadingBubbles()
                                    wizardClient?.sendMessage(text)
                                }, 1000)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
                mainHandler.post { updateMicButton(false) }
            }
        }.start()
    }

    private fun stopRecording() {
        try {
            isRecording = false
            recorder?.stop()
            recorder?.release()
            recorder = null
        } catch (_: Exception) {}
    }

    // ==================== GEMINI TTS ====================
    
    private fun geminiTTS(text: String) {
        Thread {
            try {
                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"

                val body = """
                    {
                    "contents": [{
                        "parts": [{"text": "$text"}]
                    }],
                    "generationConfig": {
                        "responseModalities": ["AUDIO"],
                        "speechConfig": {
                        "voiceConfig": {
                            "prebuiltVoiceConfig": { "voiceName": "Kore" }
                        }
                        }
                    }
                    }
                """.trimIndent()

                val (code, response) = postJson(endpoint, body)

                if (code != 200 || response == null) {
                    Log.e("GEMINI_TTS", "Error $code: $response")
                    return@Thread
                }

                val json = JSONObject(response)
                val audioBase64 = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getJSONObject("inlineData")
                    .getString("data")

                val audioBytes = Base64.getDecoder().decode(audioBase64)

                mainHandler.post { playAudio(audioBytes) }

            } catch (e: Exception) {
                Log.e("GEMINI_TTS", "TTS error: ${e.message}", e)
            }
        }.start()
    }

    // ==================== GEMINI STT ====================
    
    private fun geminiSTT(audioBytes: ByteArray, callback: (String?) -> Unit) {
        Thread {
            try {
                val audioBase64 = Base64.getEncoder().encodeToString(audioBytes)

                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"

                val body = """
                {
                "contents": [{
                    "parts": [
                    {
                        "inlineData": {
                        "mimeType": "audio/wav",
                        "data": "$audioBase64"
                        }
                    },
                    {
                        "text": "Transcribe this audio in Korean."
                    }
                    ]
                }]
                }
                """.trimIndent()

                val (code, response) = postJson(endpoint, body)

                if (code != 200 || response == null) {
                    Log.e("GEMINI_STT", "Error $code: $response")
                    callback(null)
                    return@Thread
                }

                val json = JSONObject(response)

                val text = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                callback(text)

            } catch (e: Exception) {
                Log.e("GEMINI_STT", "STT error: ${e.message}", e)
                callback(null)
            }
        }.start()
    }

    // ==================== CLOVA ====================
    
    private fun clovaTTS(text: String) {
        Thread {
            try {
                val url = java.net.URL("https://naveropenapi.apigw.ntruss.com/tts-premium/v1/tts")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("X-NCP-APIGW-API-KEY-ID", TTS_KEY_ID)
                conn.setRequestProperty("X-NCP-APIGW-API-KEY", TTS_KEY)
                conn.doOutput = true
                conn.doInput = true

                val params = "speaker=$TTS_VOICE&speed=0&volume=0&pitch=0&format=mp3&text=" +
                        java.net.URLEncoder.encode(text, "UTF-8")
                conn.outputStream.use { it.write(params.toByteArray()) }

                if (conn.responseCode == 200) {
                    val audioBytes = conn.inputStream.readBytes()
                    mainHandler.post { playAudio(audioBytes) }
                }
            } catch (e: Exception) {
                Log.e("CLOVA_TTS", "Error: ${e.message}", e)
            }
        }.start()
    }

    private fun clovaSTT(audioBytes: ByteArray, callback: (String?) -> Unit) {
        Thread {
            try {
                val url = java.net.URL("https://naveropenapi.apigw.ntruss.com/recog/v1/stt?lang=Kor")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-NCP-APIGW-API-KEY-ID", STT_KEY_ID)
                conn.setRequestProperty("X-NCP-APIGW-API-KEY", STT_KEY)
                conn.doOutput = true

                conn.outputStream.use { it.write(audioBytes) }
                val response = conn.inputStream.bufferedReader().readText()
                val text = JSONObject(response).optString("text", null)
                callback(text)
            } catch (e: Exception) {
                Log.e("CLOVA_STT", "Error: ${e.message}", e)
                callback(null)
            }
        }.start()
    }
    
    private fun postJson(urlStr: String, body: String): Pair<Int, String?> {
        return try {
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000

            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText()
            }

            Pair(code, response)
        } catch (e: Exception) {
            Log.e("GEMINI_HTTP", "Request failed: ${e.message}", e)
            Pair(-1, null)
        }
    }

    // ==================== AUDIO PLAYBACK ====================
    
    private fun playAudio(audioBytes: ByteArray) {
        try {
            Log.d(TAG, "Playing audio: ${audioBytes.size} bytes")
            
            val extension = if (USE_GEMINI_FOR_TESTING) ".wav" else ".mp3"
            val tempFile = File.createTempFile("audio", extension, cacheDir)
            tempFile.writeBytes(audioBytes)

            val mediaPlayer = MediaPlayer()
            mediaPlayer.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                mp.release()
                tempFile.delete()
                true
            }
            
            mediaPlayer.setDataSource(tempFile.absolutePath)
            
            mediaPlayer.setOnPreparedListener { 
                Log.d(TAG, "Playing...")
                it.start()
                it.setVolume(1.0f, 1.0f)
            }
            
            mediaPlayer.setOnCompletionListener {
                Log.d(TAG, "Playback done")
                it.release()
                tempFile.delete()
            }
            
            mediaPlayer.prepareAsync()
            
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}", e)
        }
    }
}