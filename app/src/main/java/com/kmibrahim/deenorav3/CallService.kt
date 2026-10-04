package com.kmibrahim.deenorav3

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class CallService : Service() {

    companion object {
        // Unifying with MainActivity's standardized IDs
        const val CHANNEL_ID = "deenora_call_channel_v12"
        const val NOTIFICATION_ID = 101
        private const val TAG = "CallService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra("CALLER_NAME") ?: "Unknown Caller"
        val callId = intent?.getStringExtra("CALL_ID") ?: ""
        val action = intent?.action

        Log.d(TAG, "onStartCommand action: $action callId: $callId")

        when (action) {
            "STOP_SERVICE" -> {
                removeNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            "ANSWER_CALL" -> {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("INCOMING_CALL", true)
                    putExtra("ACTION", "ANSWER")
                    putExtra("CALLER_NAME", callerName)
                    putExtra("CALL_ID", callId)
                }
                startActivity(activityIntent)
                removeNotification()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (callerName.isNotBlank()) {
            createNotificationChannel()
            showIncomingCallNotification(callerName, callId)
        }

        return START_NOT_STICKY
    }

    private fun removeNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Urgent call alerts"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                    setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, audioAttributes)
                }
                notificationManager?.createNotificationChannel(channel)
            }
        }
    }

    private fun showIncomingCallNotification(callerName: String, callId: String) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("INCOMING_CALL", true)
            putExtra("CALLER_NAME", callerName)
            putExtra("CALL_ID", callId)
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(this, CallService::class.java).apply {
            action = "ANSWER_CALL"
            putExtra("CALLER_NAME", callerName)
            putExtra("CALL_ID", callId)
        }
        val answerPendingIntent = PendingIntent.getService(this, 1, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val declineIntent = Intent(this, CallService::class.java).apply { action = "STOP_SERVICE" }
        val declinePendingIntent = PendingIntent.getService(this, 2, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming Voice Call")
            .setContentText("Call from $callerName")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(false)
            .setDefaults(Notification.DEFAULT_ALL)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
