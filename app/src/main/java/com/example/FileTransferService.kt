package com.example

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class FileTransferService : Service() {
    companion object {
        private const val TAG = "FileTransferService"
        private const val CHANNEL_ID = "swipe_transfer_channel"
        private const val NOTIFICATION_ID = 2026
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isStarted = false

    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "selected_lang") {
            val state = TransferManager.state.value
            updateNotificationForState(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        getSharedPreferences("swipe_prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialMessage = intent?.getStringExtra("status_message") ?: getLocalizedContext().getString(R.string.notif_title)
        
        if (!isStarted) {
            isStarted = true
            val notification = buildNotification(initialMessage, 0, 0L, 0L)
            startForeground(NOTIFICATION_ID, notification)
            
            // Collect the active state reactively to update the notification or stop
            serviceScope.launch {
                TransferManager.state.collectLatest { state ->
                    updateNotificationForState(state)
                }
            }
        } else {
            // Service already running, update with the new message if available
            if (initialMessage.isNotEmpty()) {
                updateNotification(initialMessage, 0, 0, 0)
            }
        }
        
        return START_NOT_STICKY
    }

    private fun updateNotificationForState(state: TransferState) {
        when (state) {
            is TransferState.Idle -> {
                Log.d(TAG, "Reactive service stop: Idle")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            is TransferState.Finished -> {
                Log.d(TAG, "Reactive service stop: Finished")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            is TransferState.SenderWaiting -> {
                val msg = getLocalizedContext().getString(R.string.notif_waiting_code, state.code)
                updateNotification(msg, 0, 0, 0)
            }
            is TransferState.ReceiverConnecting -> {
                val msg = getLocalizedContext().getString(R.string.notif_connecting, state.statusMessage)
                updateNotification(msg, 0, 0, 0)
            }
            is TransferState.SenderWaitingForApproval -> {
                val msg = getLocalizedContext().getString(R.string.sender_approval_title)
                updateNotification(msg, 0, 0, 0)
            }
            is TransferState.ActiveTransfer -> {
                val percent = if (state.totalSize > 0) {
                    ((state.totalTransferred * 100) / state.totalSize).toInt()
                } else {
                    0
                }
                val speedKb = state.speedBytesPerSec / 1024
                val speedText = if (speedKb > 1024) {
                    String.format(java.util.Locale.US, "%.1f MB/s", speedKb / 1024.0)
                } else {
                    "$speedKb KB/s"
                }
                
                val localCtx = getLocalizedContext()
                val role = if (state.isSender) localCtx.getString(R.string.notif_sending) else localCtx.getString(R.string.notif_receiving)
                val activeFile = state.files.getOrNull(state.activeFileIndex)?.name ?: localCtx.getString(R.string.notif_file_placeholder)
                
                val msg = if (state.isSender) {
                    localCtx.getString(R.string.notif_sending_active, activeFile, percent, speedText)
                } else {
                    localCtx.getString(R.string.notif_receiving_active, activeFile, percent, speedText)
                }
                
                updateNotification(msg, percent, state.totalTransferred, state.totalSize)
            }
        }
    }

    private fun getLocalizedContext(): Context {
        val prefs = getSharedPreferences("swipe_prefs", Context.MODE_PRIVATE)
        val sysLocale = java.util.Locale.getDefault().language
        val defaultLang = if (sysLocale == "ar") "ar" else "en"
        val lang = prefs.getString("selected_lang", defaultLang) ?: defaultLang
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        return createConfigurationContext(config)
    }

    private fun updateNotification(message: String, progress: Int, currentSize: Long, totalSize: Long) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(message, progress, currentSize, totalSize)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        message: String, 
        progress: Int, 
        currentSize: Long, 
        totalSize: Long
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )

        val localCtx = getLocalizedContext()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localCtx.getString(R.string.notif_title))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done) // Safe standard system icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (totalSize > 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true) // Indeterminate
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val localCtx = getLocalizedContext()
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                localCtx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localCtx.getString(R.string.notif_channel_desc)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("swipe_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        serviceScope.cancel()
        isStarted = false
        Log.d(TAG, "FileTransferService destroyed")
    }
}
