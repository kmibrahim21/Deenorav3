package com.kmibrahim.deenorav3

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class Fcmservice : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token: $token")
        // এখানে আপনি আপনার সার্ভারে টোকেন পাঠানোর লজিক দিতে পারেন
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("FCM", "From: ${remoteMessage.from}")

        // কল হ্যান্ডেল করার জন্য 'data' পে-লোড চেক করা
        if (remoteMessage.data.isNotEmpty()) {
            val type = remoteMessage.data["type"]
            val callerName = remoteMessage.data["caller_name"] ?: "Unknown Caller"

            if (type == "incoming_call") {
                startCallService(callerName)
            }
        }

        // যদি সাধারণ নোটিফিকেশন থাকে
        remoteMessage.notification?.let {
            Log.d("FCM", "Message Notification Body: ${it.body}")
        }
    }

    private fun startCallService(callerName: String) {
        val intent = Intent(this, CallService::class.java).apply {
            putExtra("CALLER_NAME", callerName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
