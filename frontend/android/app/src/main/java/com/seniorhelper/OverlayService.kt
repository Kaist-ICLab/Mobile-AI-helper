package com.mobileaihelper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random
import co.daily.CallClient
import co.daily.CallClientListener
import com.mobileaihelper.BuildConfig


class OverlayService : Service() {

    // ==================== CONFIGURATION ====================
    // CLOVA KEYS
    private val CLOVA_ID = BuildConfig.CLOVA_ID
    private val CLOVA_SECRET = BuildConfig.CLOVA_SECRET
    private val TTS_VOICE = "nara"

    // SERVER URLS
    private val HTTP_SERVER_URL = "https://mobile-woz-agent.iclab.dev"
    // private val HTTP_SERVER_URL = "http://172.20.60.24:8000"
    // ==================== VARIABLES ====================
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var chatView: View? = null
    private var isChatVisible = false
    private lateinit var micButton: ImageButton
    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var sessionIdText: TextView
    private var currentMessageView: View? = null

    // Tutorial State
    private var isTutorialVisible = false
    private var tutorialStep = 0
    private var tutorialTotal = 0
    private var tutorialTitleKo = ""
    private var tutorialBodyKo = ""
    private var tutorialCardView: View? = null
    private var hiddenMessages = mutableListOf<View>()

    // Chat window state
    private var currentChatHeight = 1500
    private var isMinimized = false
    private var chatLayoutParams: WindowManager.LayoutParams? = null

    // Header drag state
    private var headerLastY = 0
    private var headerTouchedY = 0f
    private var isDraggingResize = false
    private var isDraggingMove = false
    private var dragStartHeight = 0

    // Store UI references for visibility toggling
    private lateinit var controlsLayout: LinearLayout
    private lateinit var minimizeButton: ImageButton
    private lateinit var topResizeHandle: View
    private lateinit var bottomResizeHandle: View

    // Resize threshold in pixels (calculated from dp)
    private var resizeThresholdPx = 0

    // Wizard Chat Client
    private var wizardClient: WizardConsoleClient? = null
    private val sessionId = generateSessionId()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Audio Recording
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    companion object {
        private const val TAG = "OverlayService"
        private const val MIN_CHAT_HEIGHT = 800
        private const val MAX_CHAT_HEIGHT = 1500
        private const val RESIZE_THRESHOLD_DP = 20  // Edge detection zone in dp

        private fun generateSessionId(): String {
            return Random.nextInt(1000, 9999).toString()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        try {
            // 1. Initial Start: Microphone ONLY
            updateForegroundService(enableScreenShare = false)

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            connectToWizardConsole()
            showBubble()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // --- HANDLE COMMANDS FROM MAIN ACTIVITY ---
        return super.onStartCommand(intent, flags, startId)
    }

    private fun updateForegroundService(enableScreenShare: Boolean) {
        val channelId = "mobile_ai_helper_overlay"
        val channelName = "Mobile AI Helper"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Mobile AI Helper")
                .setContentText(if (enableScreenShare) "Sharing Screen..." else "Helper Active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Helper").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (enableScreenShare) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(1, notification, type)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update foreground service: ${e.message}")
            }
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wizardClient?.disconnect()
        stopRecording()
        bubbleView?.let { windowManager.removeView(it) }
        chatView?.let { windowManager.removeView(it) }
    }

    private fun startAsForegroundService() {
        val channelId = "senior_helper_overlay"
        val channelName = "Senior Helper"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                    .setContentTitle("Mobile AI Helper")
                    .setContentText("Active Session: $sessionId")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Helper").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        }

        // Android 14: Just Microphone type is enough since Jitsi handles the screen share separately
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    // ==================== UI SETUP ====================
    private fun connectToWizardConsole() {
        wizardClient = WizardConsoleClient(
            serverUrl = HTTP_SERVER_URL,
            sessionId = sessionId,
            onMessageReceived = { message ->
                mainHandler.post {
                    if (::messagesContainer.isInitialized) {
                        handleWizardPayload(message)
                        // Only scroll down if not showing tutorial (tutorial scrolls to top)
                        if (!isTutorialVisible) {
                            messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }
            },
            onConnectionStatusChanged = { isConnected ->
                mainHandler.post {
                    if(::sessionIdText.isInitialized) {
                        sessionIdText.setTextColor(if (isConnected) 0xFF4CAF50.toInt() else 0xFFE57373.toInt())
                    }
                }
            }
        )
        wizardClient?.connect()
    }

    private fun showBubble() {
        val bubbleLayout = CardView(this).apply {
            radius = 80f
            cardElevation = 0f
            setCardBackgroundColor(0xFF42A5F5.toInt())
        }
        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.chat_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        bubbleLayout.addView(iconView)

        val layoutParams = WindowManager.LayoutParams(
            160, 160, // Width, Height
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // UPDATED FLAGS:
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                        if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) isDragging = true
                        layoutParams.x = lastX - deltaX.toInt()
                        layoutParams.y = lastY + deltaY.toInt()
                        windowManager.updateViewLayout(bubbleLayout, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) toggleChatWindow()
                        return true
                    }
                }
                return false
            }
        })
        bubbleView = bubbleLayout
        windowManager.addView(bubbleView, layoutParams)
    }

    // ==================== CHAT WINDOW LOGIC ====================

    private fun toggleChatWindow() {
        if (isChatVisible) hideChatWindow() else showChatWindow()
    }

    private fun createIcon(drawableId: Int): View {
        val container = CardView(this).apply {
            radius = 40f
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        val imageView = ImageView(this).apply {
            setImageResource(drawableId)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        container.addView(imageView)
        return container
    }

    private fun showChatWindow() {
        if (chatView != null) return

        // Calculate resize threshold in pixels from dp
        resizeThresholdPx = (RESIZE_THRESHOLD_DP * resources.displayMetrics.density).toInt()

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

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(40f, 40f, 40f, 40f, 0f, 0f, 0f, 0f)
                setColor(0xFF42A5F5.toInt())
            }
            setPadding(24, 24, 24, 20)
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleText = TextView(this).apply {
            text = "💬 Helper Chat"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        minimizeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_upload)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 0, 16, 0) }
            setOnClickListener { toggleMinimize() }
        }
        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { hideChatWindow() }
        }
        topRow.addView(titleText)
        topRow.addView(minimizeButton)
        topRow.addView(closeButton)

        sessionIdText = TextView(this).apply {
            text = "Session: $sessionId"
            textSize = 14f
            setTextColor(0xFFE3F2FD.toInt())
        }
        headerLayout.addView(topRow)
        headerLayout.addView(sessionIdText)

        // Add touch listener for drag-move only (header moves window)
        headerLayout.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (chatLayoutParams == null || isMinimized) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        headerLastY = chatLayoutParams!!.y
                        headerTouchedY = event.rawY
                        isDraggingMove = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.rawY - headerTouchedY

                        if (kotlin.math.abs(deltaY) > 10) {
                            // MOVE MODE: Adjust window position
                            isDraggingMove = true
                            chatLayoutParams!!.y = headerLastY - deltaY.toInt()
                            windowManager.updateViewLayout(chatView, chatLayoutParams)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        isDraggingMove = false
                        return true
                    }
                }
                return false
            }
        })

        messagesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFFF8F9FA.toInt())
        }
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 30, 20, 30)
            gravity = Gravity.CENTER
        }
        messagesScroll.addView(messagesContainer)

        controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.WHITE)
            elevation = 8f
            gravity = Gravity.CENTER
        }
        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF42A5F5.toInt()) }
            layoutParams = LinearLayout.LayoutParams(200, 200)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(40, 40, 40, 40)
            setOnClickListener { if (!isRecording) startRecording() else stopRecording() }
        }
        controlsLayout.addView(micButton)

        // Create top resize handle (invisible)
        topResizeHandle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resizeThresholdPx
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener(createResizeListener(isTop = true))
        }

        // Create bottom resize handle (visible with nice styling)
        bottomResizeHandle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resizeThresholdPx + 10  // Slightly taller for easier grabbing
            )
            setPadding(0, 5, 0, 5)
            setBackgroundColor(0xFFE0E0E0.toInt())

            // Add visual indicator (three horizontal lines)
            val indicator = View(this@OverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(60, 4).apply {
                    setMargins(0, 2, 0, 2)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2f
                    setColor(0xFF9E9E9E.toInt())
                }
            }
            addView(indicator)

            setOnTouchListener(createResizeListener(isTop = false))
        }

        chatLayout.addView(topResizeHandle)
        chatLayout.addView(headerLayout)
        chatLayout.addView(messagesScroll)
        chatLayout.addView(controlsLayout)
        chatLayout.addView(bottomResizeHandle)

        chatLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, currentChatHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        chatLayoutParams!!.gravity = Gravity.BOTTOM
        chatLayoutParams!!.y = 100
        chatView = chatLayout
        windowManager.addView(chatView, chatLayoutParams)
        isChatVisible = true
    }

    private fun hideChatWindow() {
        if (isRecording) stopRecording()
        chatView?.let {
            windowManager.removeView(it)
            chatView = null
            chatLayoutParams = null  // Clear params
            isChatVisible = false
            isMinimized = false  // Reset state
        }
    }

    private fun toggleMinimize() {
        if (chatView == null || chatLayoutParams == null) return

        isMinimized = !isMinimized

        if (isMinimized) {
            // Hide content, show only header
            messagesScroll.visibility = View.GONE
            if (::controlsLayout.isInitialized) {
                controlsLayout.visibility = View.GONE
            }
            if (::topResizeHandle.isInitialized) {
                topResizeHandle.visibility = View.GONE
            }
            if (::bottomResizeHandle.isInitialized) {
                bottomResizeHandle.visibility = View.GONE
            }
            chatLayoutParams!!.height = MIN_CHAT_HEIGHT
        } else {
            // Restore full view
            messagesScroll.visibility = View.VISIBLE
            if (::controlsLayout.isInitialized) {
                controlsLayout.visibility = View.VISIBLE
            }
            if (::topResizeHandle.isInitialized) {
                topResizeHandle.visibility = View.VISIBLE
            }
            if (::bottomResizeHandle.isInitialized) {
                bottomResizeHandle.visibility = View.VISIBLE
            }
            chatLayoutParams!!.height = currentChatHeight
        }

        windowManager.updateViewLayout(chatView, chatLayoutParams)

        // Update minimize button icon
        if (::minimizeButton.isInitialized) {
            minimizeButton.setImageResource(
                if (isMinimized) android.R.drawable.ic_menu_more  // Expand icon
                else android.R.drawable.ic_menu_upload           // Minimize icon
            )
        }
    }

    private fun createResizeListener(isTop: Boolean): View.OnTouchListener {
        return object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (chatLayoutParams == null || isMinimized) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartHeight = chatLayoutParams!!.height
                        headerTouchedY = event.rawY
                        isDraggingResize = true
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!isDraggingResize) return true

                        val deltaY = event.rawY - headerTouchedY

                        val newHeight = if (isTop) {
                            // Top edge: dragging up decreases height, dragging down increases height
                            (dragStartHeight - deltaY.toInt()).coerceIn(MIN_CHAT_HEIGHT, MAX_CHAT_HEIGHT)
                        } else {
                            // Bottom edge: dragging down increases height, dragging up decreases height
                            (dragStartHeight + deltaY.toInt()).coerceIn(MIN_CHAT_HEIGHT, MAX_CHAT_HEIGHT)
                        }

                        chatLayoutParams!!.height = newHeight
                        currentChatHeight = newHeight
                        windowManager.updateViewLayout(chatView, chatLayoutParams)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        isDraggingResize = false
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun clearMessage() {
        currentMessageView?.let { messagesContainer.removeView(it); currentMessageView = null }
    }

    private fun showUserMessage(message: String) {
        clearMessage()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val text = TextView(this).apply {
            text = message; textSize = 34f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 35f; setColor(0xFF42A5F5.toInt()) }
            setPadding(40, 35, 40, 35)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 20, 0) }
        }
        val icon = createIcon(android.R.drawable.ic_menu_myplaces)
        row.addView(text)
        row.addView(icon)
        messagesContainer.addView(row)
        currentMessageView = row
    }

    private fun showAssistantResponse(message: String) {
        clearMessage()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val icon = createIcon(R.drawable.chat_icon)
        val text = TextView(this).apply {
            text = message; textSize = 34f; setTextColor(0xFF1565C0.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 35f; setColor(0xFFE3F2FD.toInt()) }
            setPadding(40, 35, 40, 35)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(20, 0, 0, 0) }
        }
        row.addView(icon)
        row.addView(text)
        messagesContainer.addView(row)
        currentMessageView = row
        clovaTTS(message)
    }

    private fun showLoadingBubbles() {
        clearMessage()
        val text = TextView(this).apply {
            text = "●  ●  ●"; textSize = 40f; setTextColor(0xFF90CAF9.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 45f; setColor(0xFFE3F2FD.toInt()) }
            setPadding(60, 50, 60, 50)
        }
        messagesContainer.addView(text)
        currentMessageView = text
    }

    private fun updateMicButton(recording: Boolean) {
        if (!::micButton.isInitialized) return
        val color = if (recording) 0xFFE53935.toInt() else 0xFF42A5F5.toInt()
        micButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        if (recording) showUserMessage("듣고 있어요...")
    }

    // ==================== AUDIO LOGIC (RECORDING & CLOVA) ====================

    private fun startRecording() {
        Thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)

                if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio Record Init Failed")
                    return@Thread
                }

                isRecording = true
                mainHandler.post {
                    updateMicButton(true)
                    // Hide tutorial when user starts speaking
                    if (isTutorialVisible) {
                        hideTutorialCard()
                    }
                }
                recorder?.startRecording()

                val pcmBuffer = ByteArrayOutputStream()
                val tempBuf = ByteArray(bufferSize)

                while (isRecording) {
                    val read = recorder!!.read(tempBuf, 0, tempBuf.size)
                    if (read > 0) pcmBuffer.write(tempBuf, 0, read)
                }

                val audioBytes = pcmBuffer.toByteArray()
                mainHandler.post { showLoadingBubbles() }

                clovaSTT(audioBytes) { text -> processSTTResult(text) }

            } catch (e: Exception) {
                Log.e(TAG, "Rec Error", e)
                mainHandler.post { updateMicButton(false) }
            }
        }.start()
    }

    private fun stopRecording() {
        try { isRecording = false; recorder?.stop(); recorder?.release(); recorder = null } catch (_: Exception) {}
    }

    private fun processSTTResult(text: String?) {
        mainHandler.post {
            updateMicButton(false)
            if (!text.isNullOrEmpty()) {
                showUserMessage(text)
                mainHandler.postDelayed({ showLoadingBubbles(); wizardClient?.sendMessage(text) }, 1000)
            } else {
                showUserMessage("다시 말씀해주세요.")
            }
        }
    }

    // ==================== CLOVA API ====================

    private fun clovaSTT(rawAudioBytes: ByteArray, callback: (String?) -> Unit) {
        Thread {
            try {
                val wavBytes = addWavHeader(rawAudioBytes)
                val url = URL("https://naveropenapi.apigw.ntruss.com/recog/v1/stt?lang=Kor")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-NCP-APIGW-API-KEY-ID", CLOVA_ID)
                conn.setRequestProperty("X-NCP-APIGW-API-KEY", CLOVA_SECRET)
                conn.doOutput = true

                conn.outputStream.use { it.write(wavBytes) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val text = JSONObject(response).optString("text", null)
                    callback(text)
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText()
                    Log.e("CLOVA_STT", "Error ${conn.responseCode}: $err")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e("CLOVA_STT", "Exception", e)
                callback(null)
            }
        }.start()
    }

    private fun clovaTTS(text: String) {
        Thread {
            try {
                val url = URL("https://naveropenapi.apigw.ntruss.com/tts-premium/v1/tts")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("X-NCP-APIGW-API-KEY-ID", CLOVA_ID)
                conn.setRequestProperty("X-NCP-APIGW-API-KEY", CLOVA_SECRET)
                conn.doOutput = true

                val params = "speaker=$TTS_VOICE&speed=0&volume=0&pitch=0&format=mp3&text=" + URLEncoder.encode(text, "UTF-8")
                conn.outputStream.use { it.write(params.toByteArray()) }

                if (conn.responseCode == 200) {
                    val audioBytes = conn.inputStream.readBytes()
                    mainHandler.post { playAudio(audioBytes) }
                }
            } catch (e: Exception) {
                Log.e("CLOVA_TTS", "Exception", e)
            }
        }.start()
    }

    private fun addWavHeader(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size.toLong()
        val bitrate = 16000L * 16 * 1 / 8
        val totalAudioLen = totalDataLen + 36
        val header = ByteArray(44)

        header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
        header[4] = (totalAudioLen and 0xff).toByte()
        header[5] = (totalAudioLen shr 8 and 0xff).toByte()
        header[6] = (totalAudioLen shr 16 and 0xff).toByte()
        header[7] = (totalAudioLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; header[20] = 1; header[21] = 0
        header[22] = 1; header[23] = 0; header[24] = (16000 and 0xff).toByte(); header[25] = (16000 shr 8 and 0xff).toByte()
        header[26] = (16000 shr 16 and 0xff).toByte(); header[27] = (16000 shr 24 and 0xff).toByte(); header[28] = (bitrate and 0xff).toByte()
        header[29] = (bitrate shr 8 and 0xff).toByte(); header[30] = (bitrate shr 16 and 0xff).toByte(); header[31] = (bitrate shr 24 and 0xff).toByte()
        header[32] = 2; header[33] = 0; header[34] = 16; header[35] = 0; header[36] = 'd'.toByte(); header[37] = 'a'.toByte()
        header[38] = 't'.toByte(); header[39] = 'a'.toByte(); header[40] = (totalDataLen and 0xff).toByte(); header[41] = (totalDataLen shr 8 and 0xff).toByte()
        header[42] = (totalDataLen shr 16 and 0xff).toByte(); header[43] = (totalDataLen shr 24 and 0xff).toByte()

        return header + pcmData
    }

    private fun playAudio(audioBytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("tts", ".mp3", cacheDir)
            tempFile.writeBytes(audioBytes)
            val mp = MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release(); tempFile.delete() }
            mp.prepareAsync()
        } catch (e: Exception) { Log.e(TAG, "Play Error", e) }
    }

    // ==================== TUTORIAL SYSTEM ====================

    private fun handleWizardPayload(text: String) {
        try {
            // Try to parse as JSON
            val json = JSONObject(text)
            if (json.optString("type") == "tutorial") {
                val action = json.optString("action", "")
                val step = json.optInt("step", 0)
                val total = json.optInt("total", 0)
                val titleKo = json.optString("title_ko", "도움말")
                val bodyKo = json.optString("body_ko", "")

                Log.d(TAG, "Tutorial command: action=$action, step=$step, total=$total")

                when (action) {
                    "show", "update", "next" -> {
                        showTutorialCard(step, total, titleKo, bodyKo)
                    }
                    "hide" -> {
                        hideTutorialCard()
                    }
                }
                return
            }
        } catch (e: Exception) {
            // Not valid JSON or not a tutorial command - treat as normal message
            Log.d(TAG, "Not a tutorial command, treating as normal message")
        }

        // Fall back to normal assistant response
        showAssistantResponse(text)
    }

    private fun showTutorialCard(step: Int, total: Int, titleKo: String, bodyKo: String) {
        if (!::messagesContainer.isInitialized) {
            Log.w(TAG, "Messages container not initialized")
            return
        }

        // Update state
        tutorialStep = step
        tutorialTotal = total
        tutorialTitleKo = titleKo
        tutorialBodyKo = bodyKo
        isTutorialVisible = true

        // Remove existing tutorial card if present
        tutorialCardView?.let { messagesContainer.removeView(it) }

        // Hide all existing messages
        hiddenMessages.clear()
        for (i in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                child.visibility = View.GONE
                hiddenMessages.add(child)
            }
        }

        // Create tutorial card
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 30f
                setColor(0xFFFFF9C4.toInt()) // Light yellow background
                setStroke(4, 0xFFFBC02D.toInt()) // Yellow border
            }
            setPadding(30, 25, 30, 25)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }

        // Step indicator (e.g., "1/5")
        val stepIndicator = TextView(this).apply {
            text = "$step/$total"
            textSize = 24f
            setTextColor(0xFF6D4C41.toInt()) // Brown
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }

        // Title
        val titleView = TextView(this).apply {
            text = titleKo
            textSize = 32f
            setTextColor(0xFF6D4C41.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 5, 0, 10)
        }

        // Body
        val bodyView = TextView(this).apply {
            text = bodyKo
            textSize = 34f
            setTextColor(0xFF4E342E.toInt()) // Dark brown
            lineHeight = (38 * resources.displayMetrics.scaledDensity).toInt()
        }

        cardLayout.addView(stepIndicator)
        cardLayout.addView(titleView)
        cardLayout.addView(bodyView)

        // Add to messages container (insert at top)
        messagesContainer.addView(cardLayout, 0)
        tutorialCardView = cardLayout

        // Scroll to top to show tutorial
        messagesScroll.post { messagesScroll.smoothScrollTo(0, 0) }

        // Speak the tutorial body in Korean
        clovaTTS(bodyKo)

        Log.i(TAG, "Tutorial card shown: step $step/$total")
    }

    private fun hideTutorialCard() {
        if (!::messagesContainer.isInitialized) return

        tutorialCardView?.let {
            messagesContainer.removeView(it)
            tutorialCardView = null
        }

        // Clear all hidden messages permanently instead of restoring
        hiddenMessages.clear()

        // Clear the entire message container
        messagesContainer.removeAllViews()

        isTutorialVisible = false
        tutorialStep = 0
        tutorialTotal = 0
        tutorialTitleKo = ""
        tutorialBodyKo = ""

        Log.i(TAG, "Tutorial card hidden, screen cleared")
    }
}