package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class CallBridgeApplication : Application() {
    companion object {
        const val CHANNEL_SERVICE_ID = "callbridge_service_channel"
        const val CHANNEL_CALLS_ID = "callbridge_calls_channel"
    }

    override fun onCreate() {
        super.onCreate()
        
        // Dynamic dynamic Firebase initialisation matching user's config
        try {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyAKTNMVnl4W04_WH0PqsIA2xattjTR6x0M")
                .setDatabaseUrl("https://call-from-browserss-default-rtdb.asia-southeast1.firebasedatabase.app")
                .setProjectId("call-from-browserss")
                .setApplicationId("1:421459301855:android:782a938ea1203bca06a") 
                .build()
            
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Create Notification Channels for background listening and incoming calls
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "CallBridge Active Link",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indicates that CallBridge is connected and listening for numbers"
            }

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS_ID,
                "Incoming Bridge Numbers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts you dynamically when a number is bridged"
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 150, 100, 150)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(callsChannel)
        }
    }
}
