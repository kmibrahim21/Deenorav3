package com.kmibrahim.deenorav3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * WhatsApp-style keep-alive foreground service.
 * App er process alive rakhe jate FCM data message ele
 * onMessageReceived fire kore -> CallService -> full-screen incoming call UI + ringtone.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "deenora_keep_alive"
        private const val NOTIF_ID = 9001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "KeepAliveService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start KeepAliveService", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Deenora Call Service",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply { setShowBadge(false) }
                )
            }
        }
        Log.d(TAG, "KeepAliveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Deenora")
            .setContentText("Call notification er jonno prostut")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Fallback to none or dataSync for older versions if needed, 
            // but for keep-alive, 0 (none) is often sufficient or specified type.
            startForeground(NOTIF_ID, notification)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        Log.d(TAG, "KeepAliveService running in foreground")
        return START_STICKY
    }
}
