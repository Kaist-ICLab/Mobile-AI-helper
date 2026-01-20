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
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.log


class OverlayService : Service() {

    // ==================== CONFIGURATION ====================
    // CLOVA KEYS
    private val CLOVA_ID = BuildConfig.CLOVA_ID
    private val CLOVA_SECRET = BuildConfig.CLOVA_SECRET
    private val TTS_VOICE = "nara"

    // SERVER URLS
    private val HTTP_SERVER_URL = "https://mobile-woz-agent.iclab.dev"
    // ==================== VARIABLES ====================
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var chatView: View? = null
    private var isChatVisible = false
    private lateinit var micButton: ImageButton
    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var titleView : TextView
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
    private var currentChatHeight = WindowManager.LayoutParams.MATCH_PARENT
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
    private lateinit var minimizeButton: View
    private lateinit var interveneButton : ImageButton
    private lateinit var repeatButton: ImageButton

    // Last assistant message for repeat functionality
    private var lastAssistantMessage: String? = null

    // Current MediaPlayer for TTS (to stop when mic clicked)
    private var currentMediaPlayer: MediaPlayer? = null

    // Resize threshold in pixels (calculated from dp)
    private var resizeThresholdPx = 0

    // Wizard Chat Client
    private var wizardClient: WizardConsoleClient? = null
    private var sessionId : String = "-1"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wizardSocketConnected: Boolean = false
    private var sessionPaired: Boolean = false

    // Audio Recording
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    companion object {
        private const val TAG = "OverlayService"
        private const val MIN_CHAT_HEIGHT = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_CHAT_HEIGHT = WindowManager.LayoutParams.MATCH_PARENT
        private const val RESIZE_THRESHOLD_DP = 20  // Edge detection zone in dp

    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        try {
            // 1. Initial Start: Microphone ONLY
            updateForegroundService(enableScreenShare = false)

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionId = intent?.getStringExtra("sessionID")!!
        try {
            connectToWizardConsole()
            showBubble()
        }
        catch (e : Exception){
            Log.e(TAG, "Error on starting command")
        }
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
            onConnectionStatusChanged = { _ ->
                mainHandler.post {
                }
            }
        )
        wizardClient?.connect()
        // Send a startup event so the backend registers this session immediately
        wizardClient?.sendEvent("phone_ready")
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

        @Suppress("DEPRECATION")
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
                        if (!isDragging) {
                            bubbleLayout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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

        minimizeButton = TextView(this).apply {
            text = "💬"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                toggleMinimize()
            }
        }
        val titleText = TextView(this).apply {
            text = "AI 도우미"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = titleText
        repeatButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {}
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                repeatLastMessage()
            }
            // Initially disabled until assistant sends a message
            isEnabled = false
            alpha = 0.3f
        }
        interveneButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if(isMinimized){
                   toggleMinimize();
                }
                startRecording()
            }
        }
        interveneButton.visibility = View.GONE
        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hideChatWindow()
            }
        }
        topRow.addView(minimizeButton)
        topRow.addView(titleText)
        topRow.addView(interveneButton)
        topRow.addView(repeatButton)
        topRow.addView(closeButton)

        headerLayout.addView(topRow)

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
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (!isRecording) startRecording() else stopRecording()
            }
        }
        controlsLayout.addView(micButton)

        chatLayout.addView(headerLayout)
        chatLayout.addView(messagesScroll)
        chatLayout.addView(controlsLayout)

        @Suppress("DEPRECATION")
        chatLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, currentChatHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        chatLayoutParams!!.gravity = Gravity.TOP
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
        clearMessage()
        if (isMinimized) {
            // Hide content, show only header
            messagesScroll.visibility = View.GONE
            if (::controlsLayout.isInitialized) {
                controlsLayout.visibility = View.GONE
            }

            interveneButton.visibility = View.VISIBLE
            chatLayoutParams!!.height = MIN_CHAT_HEIGHT
            titleView.text = "AI가 일하는 중..."
        } else {
            // Restore full view
            messagesScroll.visibility = View.VISIBLE
            if (::controlsLayout.isInitialized) {
                controlsLayout.visibility = View.VISIBLE
            }
            interveneButton.visibility = View.GONE
            chatLayoutParams!!.height = MAX_CHAT_HEIGHT
            titleView.text = "AI 도우미"
        }

        windowManager.updateViewLayout(chatView, chatLayoutParams)

        // Update minimize button icon
    }

    private fun repeatLastMessage() {
        val message = lastAssistantMessage
        if (message.isNullOrEmpty()) {
            Log.w(TAG, "No message to repeat")
            return
        }

        clovaTTS(message)

        Log.i(TAG, "Repeated assistant message: $message")
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
        // Hide tutorial if visible when wizard sends a regular message
        if (isTutorialVisible) {
            hideTutorialCard()
        }

        clearMessage()

        // Store last assistant message and enable repeat button
        lastAssistantMessage = message
        if (::repeatButton.isInitialized) {
            repeatButton.isEnabled = true
            repeatButton.alpha = 1.0f
        }

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
                    // Stop any TTS that's currently playing
                    stopCurrentAudio()
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
                mainHandler.post({ showLoadingBubbles(); wizardClient?.sendMessage(text) })
            } else {
                showUserMessage("다시 말씀해주세요.")
                clovaTTS("다시 말씀해주세요.")
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
                    val text = JSONObject(response).optString("text", "").ifEmpty { null }
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

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalAudioLen and 0xff).toByte()
        header[5] = (totalAudioLen shr 8 and 0xff).toByte()
        header[6] = (totalAudioLen shr 16 and 0xff).toByte()
        header[7] = (totalAudioLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; header[20] = 1; header[21] = 0
        header[22] = 1; header[23] = 0; header[24] = (16000 and 0xff).toByte(); header[25] = (16000 shr 8 and 0xff).toByte()
        header[26] = (16000 shr 16 and 0xff).toByte(); header[27] = (16000 shr 24 and 0xff).toByte(); header[28] = (bitrate and 0xff).toByte()
        header[29] = (bitrate shr 8 and 0xff).toByte(); header[30] = (bitrate shr 16 and 0xff).toByte(); header[31] = (bitrate shr 24 and 0xff).toByte()
        header[32] = 2; header[33] = 0; header[34] = 16; header[35] = 0; header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte(); header[40] = (totalDataLen and 0xff).toByte(); header[41] = (totalDataLen shr 8 and 0xff).toByte()
        header[42] = (totalDataLen shr 16 and 0xff).toByte(); header[43] = (totalDataLen shr 24 and 0xff).toByte()

        return header + pcmData
    }

    private fun playAudio(audioBytes: ByteArray) {
        try {
            // Stop any currently playing audio
            stopCurrentAudio()

            val tempFile = File.createTempFile("tts", ".mp3", cacheDir)
            tempFile.writeBytes(audioBytes)
            val mp = MediaPlayer()
            currentMediaPlayer = mp
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                it.release()
                tempFile.delete()
                if (currentMediaPlayer == it) currentMediaPlayer = null
            }
            mp.prepareAsync()
        } catch (e: Exception) { Log.e(TAG, "Play Error", e) }
    }

    private fun stopCurrentAudio() {
        try {
            currentMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            currentMediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
    }

    // ==================== TUTORIAL SYSTEM ====================

    private fun handleWizardPayload(text: String) {
        try {
            // Try to parse as JSON
            val json = JSONObject(text)
            val messageType = json.optString("type", "")

            when (messageType) {
                "tutorial" -> {
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
                "choices" -> {
                    val prompt = json.optString("prompt", "선택해주세요")
                    val optionsArray = json.optJSONArray("options")
                    val options = mutableListOf<String>()
                    if (optionsArray != null) {
                        for (i in 0 until optionsArray.length()) {
                            options.add(optionsArray.getString(i))
                        }
                    }

                    Log.d(TAG, "Choices command: prompt=$prompt, options=$options")

                    if (options.isNotEmpty()) {
                        showChoicesContainer(prompt, options)
                    }
                    return
                }
            }
        } catch (e: Exception) {
            // Not valid JSON - treat as normal message
            Log.d(TAG, "Not a JSON command, treating as normal message")
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
            lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 38f, resources.displayMetrics).toInt()
        }

        cardLayout.addView(stepIndicator)
        cardLayout.addView(titleView)
        cardLayout.addView(bodyView)

        messagesContainer.addView(cardLayout, 0)
        tutorialCardView = cardLayout

        messagesScroll.post { messagesScroll.smoothScrollTo(0, 0) }

        // Store tutorial body for repeat functionality and enable repeat button
        lastAssistantMessage = bodyKo
        if (::repeatButton.isInitialized) {
            repeatButton.isEnabled = true
            repeatButton.alpha = 1.0f
        }

        // Speak the tutorial body in Korean
        clovaTTS(bodyKo)

        Log.i(TAG, "Tutorial card shown: step $step/$total")
    }

    private fun hideTutorialCard() {
        if (!::messagesContainer.isInitialized) return

        // Stop TTS when tutorial is hidden
        stopCurrentAudio()

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

    // ==================== CHOICES CONTAINER ====================

    private var choicesContainerView: View? = null

    private fun showChoicesContainer(prompt: String, options: List<String>) {
        if (!::messagesContainer.isInitialized) {
            Log.w(TAG, "Messages container not initialized")
            return
        }

        if (isTutorialVisible) {
            hideTutorialCard()
        }

        // Remove existing choices container if present
        choicesContainerView?.let { messagesContainer.removeView(it) }

        // Clear previous messages
        clearMessage()

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 30f
                setColor(0xFFFFFFFF.toInt()) // White background
                setStroke(4, 0xFF42A5F5.toInt()) // Blue border
            }
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(10, 10, 10, 10)
            }
        }

        // Prompt text
        val promptView = TextView(this).apply {
            text = prompt
            textSize = 28f
            setTextColor(0xFF1565C0.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        cardLayout.addView(promptView)

        // Create buttons for each option
        options.forEachIndexed { index, option ->
            val optionButton = TextView(this).apply {
                text = "${index + 1}. $option"
                textSize = 30f
                setTextColor(0xFF1565C0.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f
                    setColor(0xFFE3F2FD.toInt()) // Light blue
                    setStroke(2, 0xFF90CAF9.toInt())
                }
                setPadding(30, 25, 30, 25)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onChoiceSelected(option)
                }
            }
            cardLayout.addView(optionButton)
        }

        // Add to messages container
        messagesContainer.addView(cardLayout)
        choicesContainerView = cardLayout

        lastAssistantMessage = prompt
        if (::repeatButton.isInitialized) {
            repeatButton.isEnabled = true
            repeatButton.alpha = 1.0f
        }

        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }

        clovaTTS(prompt)

        Log.i(TAG, "Choices container shown: $prompt with ${options.size} options")
    }

    private fun onChoiceSelected(selectedOption: String) {
        choicesContainerView?.let {
            messagesContainer.removeView(it)
            choicesContainerView = null
        }

        val confirmationMessage = "${selectedOption}을(를) 선택하셨어요"
        showAssistantResponse(confirmationMessage)

        // Send selection back to wizard
        wizardClient?.sendMessage("User selected: $selectedOption")
        Log.i(TAG, "User selected: $selectedOption")
    }
}
