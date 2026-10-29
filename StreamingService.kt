package com.example.looplive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class StreamingService : Service() {
    companion object {
        const val ACTION_START = "com.example.looplive.action.START"
        const val ACTION_STOP = "com.example.looplive.action.STOP"
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_STREAM_KEY = "stream_key"

        private const val CHANNEL_ID = "looplive_stream"
        private const val NOTIFICATION_ID = 4401
        private const val TAG = "LoopLiveService"
    }

    private val stopping = AtomicBoolean(false)
    private var sessionId: Long? = null
    private var videoPath: String? = null
    private var baseUrl: String? = null
    private var streamKey: String? = null
    private var retryCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler by lazy { android.os.Handler(mainLooper) }
    private var retryRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreamingByUser()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                stopping.set(false)
                videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
                streamKey = intent.getStringExtra(EXTRA_STREAM_KEY)
                retryCount = 0
                startForeground(NOTIFICATION_ID, notification("Starting stream…"))
                acquireWakeLock()
                startFfmpegStream()
            }
            else -> {
                // START_REDELIVER_INTENT lets Android redeliver the last start request after
                // process recreation. The stream key is held only in the service intent/process,
                // never written to disk or logs.
                if (videoPath != null && baseUrl != null && streamKey != null && !stopping.get()) {
                    startForeground(NOTIFICATION_ID, notification("Reconnecting…"))
                    acquireWakeLock()
                    scheduleRetry(2_000L)
                } else {
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startFfmpegStream() {
        if (stopping.get()) return
        val path = videoPath
        val url = baseUrl
        val key = streamKey
        if (path.isNullOrBlank() || url.isNullOrBlank() || key.isNullOrBlank() || !File(path).exists()) {
            updateNotification("Missing video or stream settings")
            stopStreamingByUser()
            return
        }
        val inputPath = path.replace("'", "'\\''")
        val output = "$url/$key".replace("'", "'\\''")
        // -stream_loop -1 repeats this one input file indefinitely.
        // -re paces reading in real time; reconnect is handled by restarting FFmpeg on failure.
        val command = "-hide_banner -loglevel warning -re -stream_loop -1 -i '$inputPath' " +
            "-map 0:v:0 -map 0:a? -c:v libx264 -preset veryfast -tune zerolatency " +
            "-vf scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2 " +
            "-r 30 -g 60 -keyint_min 60 -sc_threshold 0 -b:v 8000k -maxrate 8000k -bufsize 16000k " +
            "-pix_fmt yuv420p -c:a aac -b:a 128k -ar 44100 -ac 2 -f flv '$output'"

        updateNotification(if (retryCount == 0) "Connecting to YouTube…" else "Reconnect attempt $retryCount…")
        try {
            val session = FFmpegKit.executeAsync(command) { finished ->
                sessionId = null
                if (stopping.get()) return@executeAsync
                val rc = finished.returnCode
                if (ReturnCode.isSuccess(rc)) {
                    // With an infinite loop, success usually means the process was stopped or input ended unexpectedly.
                    retryCount = 0
                    scheduleRetry(2_000L)
                } else {
                    retryCount += 1
                    Log.w(TAG, "FFmpeg stream ended with code ${rc?.value}; retry=$retryCount")
                    updateNotification("Stream interrupted; retrying ($retryCount)")
                    scheduleRetry(nextRetryDelay())
                }
            }
            sessionId = session.sessionId
        } catch (e: Exception) {
            retryCount += 1
            Log.e(TAG, "Could not start FFmpeg session", e)
            updateNotification("Stream failed to start; retrying")
            scheduleRetry(nextRetryDelay())
        }
    }

    private fun nextRetryDelay(): Long {
        // Exponential backoff, capped at one minute.
        val exponent = (retryCount - 1).coerceIn(0, 4)
        return (5_000L * (1 shl exponent)).coerceAtMost(60_000L)
    }

    private fun scheduleRetry(delayMs: Long) {
        if (stopping.get()) return
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            retryRunnable = null
            if (!stopping.get()) startFfmpegStream()
        }
        retryRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun stopStreamingByUser() {
        if (!stopping.compareAndSet(false, true)) {
            stopSelf()
            return
        }
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        sessionId?.let { FFmpegKit.cancel(it) }
        sessionId = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoopLive:Streaming").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock unavailable", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LoopLive streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when LoopLive is streaming to YouTube"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LoopLive · YouTube Live")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("LoopLive · YouTube Live")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping.set(true)
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionId?.let { FFmpegKit.cancel(it) }
        releaseWakeLock()
        super.onDestroy()
    }
}
