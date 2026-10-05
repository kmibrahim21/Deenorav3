package com.kmibrahim.deenorav3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deenora push handler.
 *
 * - onNewToken: device token ele local save + backend e register kore.
 * - syncToken(context): MainActivity.onCreate theke call hoy — protibar app
 *   launch e token fetch kore. onNewToken miss holeo token pawa jay, ar
 *   Firebase er problem thakle ashol error ta log e dekha jay.
 * - onMessageReceived: data payload e caller_name/call_id thakle
 *   CallService diye full-screen incoming-call UI dekhay,
 *   na thakle shadharon notification dekhay.
 *
 * SERVER SIDE NOTE (deenora.app backend):
 * - Message obosshoi DATA-ONLY payload hote hobe (notification payload na),
 *   nahole app background/killed thakle onMessageReceived call hobe na.
 * - FCM v1 API te "android": { "priority": "high" } dite hobe.
 * - Data keys: caller_name, call_id (call er jonno) / title, body (shadharon update er jonno)
 */
class FcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmService"
        private const val PREFS = "deenora_push"
        private const val KEY_TOKEN = "fcm_token"

        // Backend endpoint already exists: POST /api/call?action=register_token
        // (api/call.ts -> register_token action). Saves {token, phone, user_id,
        // student_id, institution_id} to Supabase device_tokens table.
        private const val TOKEN_REGISTER_URL = "https://deenora.app/api/call?action=register_token"

        private const val GENERIC_CHANNEL_ID = "deenora_updates"
        private const val GENERIC_NOTIFICATION_ID = 102

        fun getSavedToken(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

        /**
         * MainActivity.onCreate() theke call koro: FcmService.syncToken(this)
         * Protibar launch e token fetch kore — fail korle karon ta log e dekhay.
         */
        fun syncToken(context: Context) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "Synced FCM token: $token")
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_TOKEN, token).apply()
                    Thread { registerTokenWithServer(token) }.start()
                } else {
                    Log.e(TAG, "FCM token fetch FAILED", task.exception)
                }
            }
        }

        private fun registerTokenWithServer(token: String) {
            try {
                val url = URL(TOKEN_REGISTER_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }
                val body = """{"token":"$token","platform":"android","app_id":"app.deenora"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                Log.d(TAG, "Token register HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Token register failed: ${e.message}")
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
        Thread { registerTokenWithServer(token) }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.d(TAG, "Push received: $data")

        val callerName = data["caller_name"] ?: data["CALLER_NAME"]
        val callId = data["call_id"] ?: data["CALL_ID"] ?: ""

        if (!callerName.isNullOrBlank()) {
            // Incoming call -> full-screen call UI
            val intent = Intent(this, CallService::class.java).apply {
                putExtra("CALLER_NAME", callerName)
                putExtra("CALL_ID", callId)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            val title = data["title"] ?: message.notification?.title ?: "Deenora"
            val body = data["body"] ?: message.notification?.body ?: ""
            if (body.isNotBlank() || title != "Deenora") {
                showGenericNotification(title, body)
            } else {
                Log.w(TAG, "Push ignored: no caller_name and no title/body")
            }
        }
    }

    private fun showGenericNotification(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(GENERIC_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(GENERIC_CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, GENERIC_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(GENERIC_NOTIFICATION_ID, notification)
    }
}
