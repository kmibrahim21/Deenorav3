package com.kmibrahim.deenorav3

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

class CallService : Service() {

    companion object {
        // v17确保全新的 Alerting Notification Channel তৈরি হবে
        const val CHANNEL_ID = "deenora_incoming_calls_v17"
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

        fun stopRingtoneOnly(context: Context) {
            try {
                val intent = Intent(context, CallService::class.java).apply {
                    action = "STOP_RINGTONE_ONLY"
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun cancelIfMatches(context: Context, callId: String?) {
            stop(context)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var currentRingingCallId: String? = null
    private var lastStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "Call timed out after 45s, stopping CallService")
        currentRingingCallId = null
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        if (action == "STOP_RINGTONE_ONLY") {
            stopRingingInternal()
            return START_NOT_STICKY
        }

        if (action == "STOP_SERVICE" || action == "DECLINE_CALL") {
            // Notification theke Decline: admin keo janate hobe (signaling),
            // tai MainActivity r maddhome JS reject pathano hoy.
            if (action == "DECLINE_CALL") {
                try {
                    val declineCallId = intent?.getStringExtra("CALL_ID") ?: ""
                    val signalIntent = Intent(this, MainActivity::class.java).apply {
                        // NOTE: onStartCommand er 'flags' param er sathe nam milay
                        // 'flags = ...' dile compile error hoy — tai addFlags().
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("type", "incoming_call")
                        putExtra("call_action", "decline")
                        putExtra("native_ui", true)
                        putExtra("call_id", declineCallId)
                        putExtra("CALL_ID", declineCallId)
                    }
                    startActivity(signalIntent)
                } catch (_: Exception) {}
                // CallActivity khola thakle setao bondho hobe
                try {
                    sendBroadcast(
                        Intent(CallActivity.ACTION_CALL_DISMISSED).setPackage(packageName)
                    )
                } catch (_: Exception) {}
            }
            removeNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStartTime < 2000) {
            Log.d(TAG, "Ignoring duplicate start command (time-based dedup)")
            return START_NOT_STICKY
        }
        lastStartTime = currentTime

        try {
            val callerName = intent?.getStringExtra("caller_name")
                ?: intent?.getStringExtra("CALLER_NAME")
                ?: "মাদরাসা এডমিন"
            val callId = intent?.getStringExtra("call_id")
                ?: intent?.getStringExtra("CALL_ID")
                ?: ""

            if (callId == currentRingingCallId) {
                Log.d(TAG, "Already ringing for callId: $callId, ignoring duplicate start command")
                return START_NOT_STICKY
            }
            // Track current ringing call ID even if empty, as time-based dedup will prevent rapid duplicate empty IDs
            currentRingingCallId = callId

            Log.d(TAG, "Processing incoming call from: $callerName (callId: $callId)")

            // ১. স্ক্রিন লাইট সাথে সাথে জ্বালিয়ে রাখা
            acquireScreenWakeLock()

            // ২. হাই-প্রায়োরিটি চ্যানেল তৈরি
            createNotificationChannel()

            // ৩. নোটিফিকেশন ও ফোরগ্রাউন্ড সার্ভিস রান
            showIncomingCallNotification(callerName, callId)

            // ৪. রিংটোন ও ভাইব্রেশন প্লে
            startRinging()

            // ৫. ফুল-স্ক্রিনে অ্যাক্টিভিটি লঞ্চ করার চেষ্টা (Overlay বা Unlocked থাকলে)
            launchCallActivity(callerName, callId, isAnswer = false)

            // ৬. ৪৫ সেকেন্ড পর অটোমেটিক বন্ধ
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "FATAL error processing incoming call in CallService", e)
        }

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
            Log.e(TAG, "WakeLock acquire failed: ${e.message}")
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
            val activityIntent = Intent(this, CallActivity::class.java).apply {
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
            Log.w(TAG, "Direct launch CallActivity failed (expected on background): ${e.message}")
        }
    }

    private var isRinging = false

    private fun startRinging() {
        stopRingingInternal()
        isRinging = true
        
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@CallService, uri)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer play failed", e)
        }

        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                @Suppress("DEPRECATION")
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val pattern = longArrayOf(0, 1000, 1000) // proper loop pattern (wait, vibrate, wait)
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
        isRinging = false
        stopRingingInternal()
    }

    private fun stopRingingInternal() {
        isRinging = false
        try { 
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return

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
                // Removed setSound to avoid conflict with startRinging() loop
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showIncomingCallNotification(callerName: String, callId: String) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // ১. ফুল-স্ক্রিন ও ট্যাপ ইনটেন্ট (CallActivity ওপেন হবে — ektai native UI)
        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
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
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 100, fullScreenIntent, piFlags)

        // ২. Answer ইনটেন্ট — CallActivity te jabe (native UI), MainActivity te NA.
        // CallActivity Answer chaple MainActivity ke background engine hishebe
        // chalu korbe (native_ui=true) — kono web call UI dekhabe na.
        val answerIntent = Intent(this, CallActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("caller_name", callerName)
            putExtra("call_id", callId)
            putExtra("type", "incoming_call")
            putExtra("call_action", "answer")
            putExtra("CALLER_NAME", callerName)
            putExtra("CALL_ID", callId)
            putExtra("INCOMING_CALL", true)
            putExtra("ACTION", "ANSWER")
        }
        val answerPendingIntent = PendingIntent.getActivity(this, 101, answerIntent, piFlags)

        // ৩. Decline ইনটেন্ট — CallService DECLINE_CALL: admin ke signaling + dismiss
        val declineIntent = Intent(this, CallService::class.java).apply {
            action = "DECLINE_CALL"
            putExtra("CALL_ID", callId)
        }
        val declinePendingIntent = PendingIntent.getService(this, 102, declineIntent, piFlags)

        // ৪. Custom floating-card UI — prototype "Floating Notification" screen.
        // (CallStyle er bodle: white card, navy icon, Decline/Accept button.)
        try {
            val cardView = RemoteViews(packageName, R.layout.notification_incoming_call).apply {
                setTextViewText(R.id.notif_title, callerName)
                setTextViewText(R.id.notif_subtitle, "Incoming Voice Call...")
                setOnClickPendingIntent(R.id.btn_accept, answerPendingIntent)
                setOnClickPendingIntent(R.id.btn_decline, declinePendingIntent)
            }

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setColor(Color.parseColor("#2563EB"))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(longArrayOf(0, 800, 500, 800, 500, 800))
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setCustomContentView(cardView)
                .setCustomBigContentView(cardView)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(RING_TIMEOUT_MS)

            val notification = notificationBuilder.build()
            // Removed FLAG_INSISTENT to rely purely on startRinging() looping (Issue 5 fix)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // এক্সপ্লিসিট নোটিফাই দিয়ে স্ক্রিনের উপরে Heads-Up ব্যানার নিশ্চিত করা
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "FATAL error showing incoming call notification", e)
        }
    }

    private fun removeNotification() {
        currentRingingCallId = null
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
        currentRingingCallId = null
        handler.removeCallbacks(timeoutRunnable)
        removeNotification()
        super.onDestroy()
    }
}