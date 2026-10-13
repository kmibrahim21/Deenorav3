package com.kmibrahim.deenorav3

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat

class CallService : Service() {

    companion object {
        // v16 ensures any cached channel settings with silent/disabled flags are reset
        const val CHANNEL_ID = "deenora_incoming_calls_v16"
        const val NOTIFICATION_ID = 101
        private const val TAG = "CallService"
        private const val RING_TIMEOUT_MS = 45_000L

        fun start(context: Context, callerName: String, callId: String) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra("caller_name", callerName)
                putExtra("call_id", callId)
                putExtra("CALLER_NAME", callerName)
                putExtra("CALL_ID", callId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallService::class.java))
            } catch (_: Exception) {}
        }

        fun cancelIfMatches(context: Context, callId: String?) {
            stop(context)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "Call timed out after 45s, stopping CallService")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        if (action == "STOP_SERVICE" || action == "DECLINE_CALL") {
            removeNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        val callerName = intent?.getStringExtra("caller_name")
            ?: intent?.getStringExtra("CALLER_NAME")
            ?: "মাদরাসা এডমিন"
        val callId = intent?.getStringExtra("call_id")
            ?: intent?.getStringExtra("CALL_ID")
            ?: ""

        Log.d(TAG, "Processing incoming call from: $callerName (callId: $callId)")

        // 1. Wake the screen immediately
        acquireScreenWakeLock()

        // 2. Setup notification channel
        createNotificationChannel()

        // 3. Post notification & foreground service
        showIncomingCallNotification(callerName, callId)

        // 4. Start looping ringtone & vibration
        startRinging()

        // 5. Directly launch MainActivity over lock screen & normal screen
        launchCallActivity(callerName, callId, isAnswer = false)

        // 6. Timeout after 45 seconds if nobody answers
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)

        return START_NOT_STICKY
    }

    private fun acquireScreenWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            releaseScreenWakeLock()
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "deenora:incoming_call_wake"
            ).apply { acquire(45_000L) }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun launchCallActivity(callerName: String, callId: String, isAnswer: Boolean) {
        try {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("caller_name", callerName)
                putExtra("call_id", callId)
                putExtra("type", "incoming_call")
                putExtra("CALLER_NAME", callerName)
                putExtra("CALL_ID", callId)
                putExtra("INCOMING_CALL", true)
                if (isAnswer) {
                    putExtra("call_action", "answer")
                    putExtra("ACTION", "ANSWER")
                }
            }
            startActivity(activityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct launch MainActivity failed: ${e.message}")
        }
    }

    private fun startRinging() {
        stopRinging()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone play failed", e)
        }

        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                @Suppress("DEPRECATION")
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val pattern = longArrayOf(0, 800, 500, 800, 500, 800)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ইনকামিং কল (Incoming Calls)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "জরুরি ইনকামিং কল অ্যালার্ট"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 500, 800, 500, 800)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                    setSound(null, null) // Sound & vibration are looped directly by startRinging()
                }
                notificationManager?.createNotificationChannel(channel)
            }
        }
    }

    private fun showIncomingCallNotification(callerName: String, callId: String) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // 1. Full-screen and tap intent
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("caller_name", callerName)
            putExtra("call_id", callId)
            putExtra("type", "incoming_call")
            putExtra("CALLER_NAME", callerName)
            putExtra("CALL_ID", callId)
            putExtra("INCOMING_CALL", true)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, piFlags)

        // 2. Answer intent (direct Activity intent to avoid Android 12+ trampoline block)
        val answerIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("caller_name", callerName)
            putExtra("call_id", callId)
            putExtra("type", "incoming_call")
            putExtra("call_action", "answer")
            putExtra("CALLER_NAME", callerName)
            putExtra("CALL_ID", callId)
            putExtra("INCOMING_CALL", true)
            putExtra("ACTION", "ANSWER")
        }
        val answerPendingIntent = PendingIntent.getActivity(this, 1, answerIntent, piFlags)

        // 3. Decline intent (handled by CallService STOP_SERVICE)
        val declineIntent = Intent(this, CallService::class.java).apply {
            action = "STOP_SERVICE"
            putExtra("CALL_ID", callId)
        }
        val declinePendingIntent = PendingIntent.getService(this, 2, declineIntent, piFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ইনকামিং ভয়েস কল")
            .setContentText("$callerName থেকে কল আসছে...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .addAction(android.R.drawable.ic_menu_call, "উত্তর দিন (Answer)", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "কেটে দিন (Decline)", declinePendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Explicit notify forces Heads-Up notification banner on normal screen
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "NotificationManager notify failed: ${e.message}")
        }
    }

    private fun removeNotification() {
        releaseScreenWakeLock()
        stopRinging()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        removeNotification()
        super.onDestroy()
    }
}