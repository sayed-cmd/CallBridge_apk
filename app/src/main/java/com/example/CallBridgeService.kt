package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow

class CallBridgeService : Service() {

    private var databaseListener: ValueEventListener? = null
    private var activeUid: String? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_UID = "EXTRA_UID"

        val isServiceRunning = MutableStateFlow(false)
        val currentUid = MutableStateFlow<String?>(null)
        val activeNumber = MutableStateFlow<String?>(null)
        val activeTimestamp = MutableStateFlow<Long?>(null)
        
        // Formatter matching JS logic
        fun formatBangladeshiNumber(raw: String): String {
            val clean = raw.replace(Regex("\\D"), "")
            val regex = Regex("^(?:880|0)?(1[3-9]\\d{8})$")
            val matchResult = regex.find(clean)
            if (matchResult == null) return raw
            val suffix = matchResult.groupValues[1] // 10 digits
            return "+880${suffix.substring(0, 2)}-${suffix.substring(2, 6)}-${suffix.substring(6)}"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val uid = intent.getStringExtra(EXTRA_UID)
            if (!uid.isNullOrBlank()) {
                startMonitoring(uid)
            }
        } else if (action == ACTION_STOP) {
            stopMonitoring()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(uid: String) {
        if (activeUid == uid) return // Already monitoring this UID
        
        // Stop current if active
        stopMonitoring()

        activeUid = uid
        currentUid.value = uid
        isServiceRunning.value = true

        // Create foreground notification
        val notification = createServiceNotification(uid)
        startForeground(1001, notification)

        // Connect Firebase Realtime Database
        val callRef = FirebaseDatabase.getInstance().getReference("calls/$uid")
        databaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val number = snapshot.child("number").getValue(String::class.java)
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

                if (!number.isNullOrBlank()) {
                    val prevNumber = activeNumber.value
                    activeNumber.value = number
                    activeTimestamp.value = timestamp

                    if (prevNumber != number) {
                        // Triggers actions for a newly arrived number
                        triggerNativeFeedback(number)
                        postIncomingCallNotification(number)
                        addHistory(uid, number, timestamp)
                    }
                } else {
                    activeNumber.value = null
                    activeTimestamp.value = null
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle cancellation if needed
            }
        }
        callRef.addValueEventListener(databaseListener as ValueEventListener)
    }

    private fun stopMonitoring() {
        val uid = activeUid
        if (uid != null && databaseListener != null) {
            FirebaseDatabase.getInstance().getReference("calls/$uid")
                .removeEventListener(databaseListener!!)
        }
        databaseListener = null
        activeUid = null
        currentUid.value = null
        activeNumber.value = null
        activeTimestamp.value = null
        isServiceRunning.value = false
    }

    private fun triggerNativeFeedback(number: String) {
        // Vibrate
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 180), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(longArrayOf(0, 180, 80, 180), -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun postIncomingCallNotification(number: String) {
        val formatted = formatBangladeshiNumber(number)
        
        // Tap to open MainActivity
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 2001, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Native Action to call
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        val dialPendingIntent = PendingIntent.getActivity(
            this, 2002, dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CallBridgeApplication.CHANNEL_CALLS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("CallBridge Bridged Number")
            .setContentText("Incoming: $formatted")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(appPendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.sym_action_call, "Dial Number", dialPendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1002, builder.build())
    }

    private fun addHistory(uid: String, number: String, ts: Long) {
        val ref = FirebaseDatabase.getInstance().getReference("history/$uid")
        val key = ref.push().key ?: return
        ref.child(key).setValue(mapOf("number" to number, "ts" to ts))

        // Keep maximum 5 items
        ref.get().addOnSuccessListener { snapshot ->
            val entries = snapshot.children.mapNotNull { child ->
                val k = child.key
                val t = child.child("ts").getValue(Long::class.java) ?: 0L
                if (k != null) k to t else null
            }
            if (entries.size > 5) {
                val sorted = entries.sortedBy { it.second }
                val toRemoveCount = sorted.size - 5
                for (i in 0 until toRemoveCount) {
                    ref.child(sorted[i].first).removeValue()
                }
            }
        }
    }

    private fun createServiceNotification(uid: String): Notification {
        val stopIntent = Intent(this, CallBridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1003, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 1004, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CallBridgeApplication.CHANNEL_SERVICE_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CallBridge Active Listening")
            .setContentText("UID: $uid (Monitoring matches in background)")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }
}
