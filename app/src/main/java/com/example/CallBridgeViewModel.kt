package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HistoryItem(
    val key: String = "",
    val number: String = "",
    val ts: Long = 0L
)

class CallBridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("CallBridgePrefs", Context.MODE_PRIVATE)

    // Shared UI States syncing directly from companion object Service flows
    val isConnected: StateFlow<Boolean> = CallBridgeService.isServiceRunning
    val currentUid: StateFlow<String?> = CallBridgeService.currentUid
    val activeNumber: StateFlow<String?> = CallBridgeService.activeNumber
    val activeTimestamp: StateFlow<Long?> = CallBridgeService.activeTimestamp

    private val _recentCalls = MutableStateFlow<List<HistoryItem>>(emptyList())
    val recentCalls: StateFlow<List<HistoryItem>> = _recentCalls

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent

    private var historyListener: ValueEventListener? = null
    private var activeHistoryRef: String? = null

    init {
        // Observe current UID to update history listener reactively
        viewModelScope.launch {
            currentUid.collect { uid ->
                setupHistoryListener(uid)
            }
        }

        // Auto-connect if UID was previously saved (matching Javascript behavior)
        val savedUid = prefs.getString("savedUID", null)
        if (!savedUid.isNullOrBlank() && !CallBridgeService.isServiceRunning.value) {
            connectUid(savedUid)
        }
    }

    private fun setupHistoryListener(uid: String?) {
        // Remove previous listener first
        val oldUid = activeHistoryRef
        if (oldUid != null && historyListener != null) {
            FirebaseDatabase.getInstance().getReference("history/$oldUid")
                .removeEventListener(historyListener!!)
        }
        historyListener = null
        activeHistoryRef = null
        _recentCalls.value = emptyList()

        if (uid.isNullOrBlank()) return

        activeHistoryRef = uid
        val ref = FirebaseDatabase.getInstance().getReference("history/$uid")
        historyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<HistoryItem>()
                for (child in snapshot.children) {
                    val key = child.key ?: ""
                    val num = child.child("number").getValue(String::class.java) ?: ""
                    val time = child.child("ts").getValue(Long::class.java) ?: 0L
                    if (num.isNotEmpty()) {
                        list.add(HistoryItem(key, num, time))
                    }
                }
                // Sort by ts descending and limit to top 5 (matching Javascript)
                val sortedList = list.sortedByDescending { it.ts }.take(5)
                _recentCalls.value = sortedList
            }

            override fun onCancelled(error: DatabaseError) {
                // handle failures
            }
        }
        ref.addValueEventListener(historyListener as ValueEventListener)
    }

    fun connectUid(uid: String) {
        val trimmed = uid.trim()
        if (trimmed.isEmpty()) return

        // Persist saved UID (Equivalent to localStorage.setItem)
        prefs.edit().putString("savedUID", trimmed).apply()

        // Start background service
        val intent = Intent(context, CallBridgeService::class.java).apply {
            action = CallBridgeService.ACTION_START
            putExtra(CallBridgeService.EXTRA_UID, trimmed)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        
        viewModelScope.launch {
            _toastEvent.emit("Connected 🔗")
        }
    }

    fun disconnect() {
        // Clear prefs
        prefs.edit().remove("savedUID").apply()

        // Stop background service
        val intent = Intent(context, CallBridgeService::class.java).apply {
            action = CallBridgeService.ACTION_STOP
        }
        context.startService(intent)

        viewModelScope.launch {
            _toastEvent.emit("Disconnected 🔄")
        }
    }

    fun clearHistory() {
        val uid = currentUid.value
        if (!uid.isNullOrBlank()) {
            FirebaseDatabase.getInstance().getReference("history/$uid").removeValue()
            viewModelScope.launch {
                _toastEvent.emit("History cleared 🗑️")
            }
        }
    }

    override fun onCleared() {
        val oldUid = activeHistoryRef
        if (oldUid != null && historyListener != null) {
            FirebaseDatabase.getInstance().getReference("history/$oldUid")
                .removeEventListener(historyListener!!)
        }
        super.onCleared()
    }
}
