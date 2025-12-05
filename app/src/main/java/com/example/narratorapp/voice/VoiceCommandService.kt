package com.example.narratorapp.voice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.narratorapp.R
import com.example.narratorapp.narration.TTSManager
import android.os.Handler
import android.os.Looper

class VoiceCommandService : Service() {
    
    private val binder = LocalBinder()
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var ttsManager: TTSManager
    
    private var commandCallback: ((VoiceCommand) -> Unit)? = null
    private var hotwordModeCallback: ((Boolean) -> Unit)? = null  // NEW: Callback for hotword mode changes
    
    private val watchdogHandler = Handler(Looper.getMainLooper())
private val watchdogRunnable = object : Runnable {
    override fun run() {
        if (!voiceCommandManager.isCurrentlyListening()) {
            Log.w("VoiceCommandService", "Watchdog: Restarting listener...")
            startListening()
        }
        watchdogHandler.postDelayed(this, 5000) // Check every 5 seconds
    }
}

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "VoiceCommandChannel"
        const val ACTION_START_LISTENING = "com.example.narratorapp.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.example.narratorapp.STOP_LISTENING"
        
        fun start(context: Context) {
            val intent = Intent(context, VoiceCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e("VoiceCommandService", "Failed to start foreground service", e)
                }
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, VoiceCommandService::class.java)
            context.stopService(intent)
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceCommandService = this@VoiceCommandService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d("VoiceCommandService", "Service created")
        
        createNotificationChannel()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    createNotification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification(false))
            }
            Log.d("VoiceCommandService", "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e("VoiceCommandService", "Failed to start foreground service", e)
            stopSelf()
            return
        }
        
        ttsManager = TTSManager(this)
        voiceCommandManager = VoiceCommandManager(this, ttsManager)
        
        voiceCommandManager.setOnCommandRecognizedListener { command ->
            Log.d("VoiceCommandService", "Command recognized: ${command.getDescription()}")
            commandCallback?.invoke(command)
        }
        
        // ===== NEW: Forward hotword mode changes to MainActivity =====
        voiceCommandManager.setOnHotwordModeChangedListener { isHotwordMode ->
            Log.d("VoiceCommandService", "Hotword mode changed: $isHotwordMode")
            hotwordModeCallback?.invoke(isHotwordMode)
        }

        watchdogHandler.post(watchdogRunnable)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_STOP_LISTENING -> stopListening()
            else -> startListening()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    fun startListening(startWithHotword: Boolean = true) {
        if (::voiceCommandManager.isInitialized) {
            voiceCommandManager.startListening(startWithHotword)
            updateNotification(true)
        }
    }
    
    fun stopListening() {
        if (::voiceCommandManager.isInitialized) {
            voiceCommandManager.stopListening()
            updateNotification(false)
        }
    }
    
    fun isListening(): Boolean {
        return if (::voiceCommandManager.isInitialized) {
            voiceCommandManager.isCurrentlyListening()
        } else {
            false
        }
    }
    
    fun setCommandCallback(callback: (VoiceCommand) -> Unit) {
        commandCallback = callback
    }
    
    // ===== NEW: Set callback for hotword mode changes =====
    fun setHotwordModeCallback(callback: (Boolean) -> Unit) {
        hotwordModeCallback = callback
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Commands",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Narrator app voice command service"
                setShowBadge(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(isListening: Boolean): Notification {
        val title = if (isListening) "Voice Commands Active" else "Voice Commands Paused"
        val text = if (isListening) "Say 'Hey Narrator' to give commands" else "Tap to resume"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun updateNotification(isListening: Boolean) {
        val notification = createNotification(isListening)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        if (::voiceCommandManager.isInitialized) {
            voiceCommandManager.cleanup()
        }
        if (::ttsManager.isInitialized) {
            ttsManager.shutdown()
        }
        Log.d("VoiceCommandService", "Service destroyed")
    }
}