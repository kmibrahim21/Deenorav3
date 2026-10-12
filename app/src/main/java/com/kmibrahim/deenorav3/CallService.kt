package com.kmibrahim.deenorav3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Incoming call ringing service (foreground, type=phoneCall).
 *
 * Extra keys are shared with MainActivity.buildStartUrl (lowercase):
 *   caller_name, call_id, type=incoming_call, call_action=answer
 *
 * Ringing stops when: Decline tapped, Answer tapped (MainActivity calls stop),
 * web calls AndroidInterface.stopRinging(), FCM "call_cancel" arrives, or after RING_TIMEOUT_MS.
 */
class CallService : Service() {

    companion object {
        const val CHANNEL_ID = "deenora_call_channel_v13" // new id: channel sound settings are immutable
        const val NOTIFICATION_ID = 101
        const val ACTION_DECLINE = "app.deenora.action.DECLINE_CALL"

        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_TYPE = "type"
        const val EXTRA_CALL_ACTION = "call_action"

        private const val RING_TIMEOUT_MS = 30_000L
        private const val TAG = "CallService"

        @Volatile
        var activeCallId: String? = null

        fun start(context: Context, callerName: String, callId: String) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALL_ID, callId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        /** Called when the caller hangs up (FCM type=call_cancel). */
        fun cancelIfMatches(context: Context, callId: String?) {
            val active = activeCallId
            if (callId.isNullOrBlank() || active == null || active == callId) stop(context)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "Ring timeout")
        stopSelf()
    }
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DECLINE) {
            // TODO: tell backend the call was declined (call_id) if your API supports it.
            stopSelf()
            return START_NOT_STICKY
        }

        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME)?.takeIf { it.isNotBlank() } ?: "Unknown Caller"
        val callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: ""
        activeCallId = callId

        createNotificationChannel()
        val notification = buildNotification(callerName, callId)

        // Always call startForeground right away (service was started with startForegroundService)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startRinging()
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)

        return START_NOT_STICKY
    }

    private fun startRinging() {
        stopRinging()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone failed", e)
        }

        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                @Suppress("DEPRECATION")
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                val pattern = longArrayOf(0, 700, 500, 700, 1200)
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
        try { ringtone?.stop() } catch (_: Exception) { }
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) { }
        vibrator = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Urgent call alerts"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    // Sound + vibration are handled by the service (looping), not the channel
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(callerName: String, callId: String): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun activityIntent(answer: Boolean) = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TYPE, "incoming_call")
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_CALL_ID, callId)
            if (answer) putExtra(EXTRA_CALL_ACTION, "answer")
        }

        // Full-screen (lock screen) and tap -> open the call page
        val fullScreenPi = PendingIntent.getActivity(this, 0, activityIntent(false), piFlags)

        // Answer: PendingIntent.getActivity directly (Android 12+ blocks service->activity trampolines)
        val answerPi = PendingIntent.getActivity(this, 1, activityIntent(true), piFlags)

        val declineIntent = Intent(this, CallService::class.java).apply { action = ACTION_DECLINE }
        val declinePi = PendingIntent.getService(this, 2, declineIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming Voice Call")
            .setContentText("Call from $callerName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePi)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        stopRinging()
        activeCallId = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}