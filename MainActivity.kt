package com.example.looplive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private val pickVideoCode = 2026
    private val notificationPermissionCode = 2027
    private var copiedVideo: File? = null

    private lateinit var videoLabel: TextView
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermissionIfNeeded()
        buildUi()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionCode)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF10141C.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }

        fun label(text: String, size: Float = 15f) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFF4F6FA.toInt())
            setPadding(0, 12, 0, 8)
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            textSize = 14f
            setSingleLine(true)
            setTextColor(0xFF111827.toInt())
            setHintTextColor(0xFF657084.toInt())
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        fun button(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { click() }
        }

        content.addView(label("LoopLive", 28f))
        content.addView(label("Pre-recorded video → YouTube Live"))
        content.addView(button("Choose MP4 video") { chooseVideo() })
        videoLabel = label("No video selected")
        content.addView(videoLabel)

        content.addView(label("YouTube RTMPS ingest URL"))
        urlInput = field("rtmps://a.rtmps.youtube.com:443/live2")
        urlInput.setText("rtmps://a.rtmps.youtube.com:443/live2")
        content.addView(urlInput)

        content.addView(label("Stream key (keep private)"))
        keyInput = field("Paste your YouTube stream key")
        keyInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        content.addView(keyInput)

        content.addView(label("Output target: 1080p / 30 fps / H.264 / AAC"))
        content.addView(label("Continuous loop: the video repeats until you press STOP. If the stream drops, the app retries automatically.", 14f))
        content.addView(label("Keep a stable upload connection. 1080p encoding may overheat or overload some phones.", 13f))

        startButton = button("START LOOP LIVE") { startLive() }
        content.addView(startButton)
        stopButton = button("STOP LIVE") { stopLive() }
        stopButton.isEnabled = false
        content.addView(stopButton)

        statusLabel = label("Status: Ready")
        content.addView(statusLabel)
        content.addView(label("Streaming continues through the Android foreground service while the app is in the background. Android may still stop services under extreme battery or memory pressure.", 13f))
        content.addView(label("Never share your stream key or upload it to GitHub.", 13f))

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, pickVideoCode)
    }

    @Deprecated("Legacy result API kept to keep this starter dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickVideoCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            copiedVideo = copyVideoToCache(uri)
            if (copiedVideo != null) {
                videoLabel.text = "Selected: ${copiedVideo!!.name} (${copiedVideo!!.length() / (1024 * 1024)} MB)"
                statusLabel.text = "Status: Video ready"
            } else {
                statusLabel.text = "Status: Could not read selected video"
            }
        }
    }

    private fun copyVideoToCache(uri: Uri): File? {
        return try {
            val file = File(filesDir, "looplive_input.mp4")
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) {
            Toast.makeText(this, "Video copy failed: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun startLive() {
        val file = copiedVideo
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Choose a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = urlInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (!baseUrl.startsWith("rtmps://") || key.isBlank() || key.contains(" ")) {
            Toast.makeText(this, "Enter a valid RTMPS URL and stream key", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_VIDEO_PATH, file.absolutePath)
            putExtra(StreamingService.EXTRA_BASE_URL, baseUrl)
            putExtra(StreamingService.EXTRA_STREAM_KEY, key)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            statusLabel.text = "Status: Streaming service started; check YouTube Live Control Room."
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } catch (e: Exception) {
            statusLabel.text = "Status: Could not start service: ${e.message}"
            Toast.makeText(this, "Could not start streaming service", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLive() {
        startService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusLabel.text = "Status: Stop requested"
    }

    override fun onResume() {
        super.onResume()
        // UI buttons are not a source of truth after Activity recreation; the service notification
        // remains the authoritative indicator while streaming.
    }
}
