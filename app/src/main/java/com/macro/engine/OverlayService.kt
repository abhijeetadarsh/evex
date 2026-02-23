package com.macro.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.macro.engine.databinding.OverlayWidgetBinding
import android.view.ContextThemeWrapper
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground service that displays a floating overlay with Record/Stop/Play buttons.
 * Supports loop playback, speed control, volume key triggers, and notification controls.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "macro_engine_overlay"
        private const val NOTIFICATION_ID = 1001

        // Notification action intents
        const val ACTION_STOP = "com.macro.engine.ACTION_STOP"
        const val ACTION_RECORD = "com.macro.engine.ACTION_RECORD"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var binding: OverlayWidgetBinding? = null
    private val daemonClient = DaemonClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listenerJob: Job? = null

    // State tracking
    private var isDaemonConnected = false
    private var isRecording = false
    private var isPlaying = false
    private var isPending = false // waiting for daemon ack
    private var isCountingDown = false
    private var isWaitingForTouch = false
    private var isRecordingPaused = false
    private var countdownJob: Job? = null
    private var pendingTimeoutJob: Job? = null
    private var recTimerJob: Job? = null
    private var recStartTimeMs: Long = 0
    private var lastMacroPath: String? = null



    // Volume key handling
    private var volumeKeyReceiver: BroadcastReceiver? = null
    private var audioManager: AudioManager? = null
    private var triggerManager: TriggerOverlayManager? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        // Init trigger manager immediately so triggers can be shown before daemon connects
        triggerManager = TriggerOverlayManager(this, windowManager, daemonClient, serviceScope)
        connectToDaemon()
        registerVolumeKeyListener()
    }

    private fun initTriggerManager() {
        // Load all triggers for macros with enabled trigger configs
        triggerManager?.loadAllTriggers(getMacroDir())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> onStopClicked()
            ACTION_RECORD -> onRecordClicked()
            "ACTION_ACTIVATE_TRIGGER" -> {
                // Only show trigger — no recording overlay
                val path = intent.getStringExtra("macro_path")
                val enable = intent.getBooleanExtra("enable", true)
                if (path != null) setTrigger(path, enable)
            }
            "ACTION_REFRESH_TRIGGERS" -> {
                triggerManager?.loadAllTriggers(getMacroDir())
            }
            else -> {
                // No action = started from overlay switch — show recording overlay
                if (overlayView == null) showOverlay()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents — stop the service cleanly
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterVolumeKeyListener()
        triggerManager?.removeAll()
        recTimerJob?.cancel()
        removeOverlay()
        serviceScope.launch {
            daemonClient.disconnect()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Overlay UI ──────────────────────────────────────────────

    private fun showOverlay() {
        // Services don't carry the Activity's Material theme, so MaterialCardView
        // and MaterialButton crash with IllegalArgumentException.
        // Wrap the context with our app theme before inflating.
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MacroEngine)
        val inflater = LayoutInflater.from(themedContext)
        binding = OverlayWidgetBinding.inflate(inflater)
        overlayView = binding!!.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        setupDragging(params)

        binding?.apply {
            btnRecord.setOnClickListener { onRecordClicked() }
            btnStop.setOnClickListener { onStopClicked() }

            // Recording flow buttons
            tvDone.setOnClickListener { onDoneClicked() }
            btnCancel.setOnClickListener { onCancelClicked() }
            btnSave.setOnClickListener { onSaveClicked() }
        }

        windowManager.addView(overlayView, params)
        updateButtonStates()
        Log.i(TAG, "Overlay shown")
    }

    /**
     * During playback, make the overlay pass through all touch events so that
     * kernel-injected input events reach the underlying apps instead of being
     * consumed by the overlay widget.
     */
    private fun setOverlayTouchPassthrough(passthrough: Boolean) {
        val view = overlayView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (passthrough) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay flags", e)
        }
    }

    private fun setupDragging(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        binding = null
    }

    // ─── Daemon Connection ───────────────────────────────────────

    private fun connectToDaemon() {
        serviceScope.launch {
            val connected = daemonClient.connect()
            if (connected) {
                isDaemonConnected = true
                Log.i(TAG, "Connected to daemon")
                daemonClient.onStatusReceived = { status -> handleDaemonStatus(status) }
                listenerJob = daemonClient.startListening(serviceScope)
                initTriggerManager()
                // Listener ending means connection dropped
                listenerJob?.invokeOnCompletion {
                    isDaemonConnected = false
                    isRecording = false
                    isPlaying = false
                    isPending = false
                    Log.w(TAG, "Daemon connection lost")
                    serviceScope.launch(Dispatchers.Main) {
                        updateButtonStates()
                        updateNotification()
                        Toast.makeText(this@OverlayService, "Daemon disconnected", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                isDaemonConnected = false
                Log.e(TAG, "Failed to connect to daemon")
                Toast.makeText(this@OverlayService, "Failed to connect to daemon", Toast.LENGTH_LONG).show()
            }
            updateButtonStates()
        }
    }

    private fun handleDaemonStatus(status: String) {
        Log.d(TAG, "Daemon status: $status")
        when {
            status.startsWith("REC_FIRST_EVENT") -> {
                // First input event captured — NOW show timer UI
                isWaitingForTouch = false
                binding?.tvTapToStart?.visibility = View.GONE
                showRecordingUI()
                updateButtonStates()
            }
            status.startsWith("REC_STARTED") -> {
                // Daemon confirmed recording — mark as active, keep "Tap to start" visible
                setPending(false)
                isRecording = true
                updateButtonStates()
                updateNotification()
            }
            status.startsWith("REC_STOPPED") -> {
                isRecording = false
                setPending(false)
                // Don't reset UI here — let Done/Cancel/Save handle it
                updateButtonStates()
                updateNotification()
            }
            status.startsWith("PLAY_STARTED") -> {
                // Daemon confirmed playback — NOW update UI
                setPending(false)
                isPlaying = true
                setOverlayTouchPassthrough(true)
                updateButtonStates()
                updateNotification()
            }
            status.startsWith("PLAY_DONE") || status.startsWith("PLAY_STOPPED") -> {
                isPlaying = false
                setPending(false)
                setOverlayTouchPassthrough(false)
                updateButtonStates()
                updateNotification()
            }
            status.startsWith("PLAY_LOOP_ITER") -> {
                val iter = status.substringAfter(" ", "")
                Log.i(TAG, "Loop iteration: $iter")
            }
            status.startsWith("SPEED_SET") -> {
                Log.i(TAG, "Speed confirmed: ${status.substringAfter(" ")}")
            }
            status.startsWith("ERROR") -> {
                isRecording = false
                isPlaying = false
                isWaitingForTouch = false
                isRecordingPaused = false
                setPending(false)
                setOverlayTouchPassthrough(false)
                resetOverlayUI()
                updateButtonStates()
                updateNotification()
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Button Actions ──────────────────────────────────────────

    private fun setPending(pending: Boolean) {
        isPending = pending
        pendingTimeoutJob?.cancel()
        if (pending) {
            // Auto-clear pending after 5 seconds to prevent permanent button lock
            pendingTimeoutJob = serviceScope.launch {
                delay(5000)
                if (isPending) {
                    Log.w(TAG, "Pending timeout — daemon didn't acknowledge")
                    isPending = false
                    updateButtonStates()
                    Toast.makeText(this@OverlayService, "Daemon not responding", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onRecordClicked() {
        if (!isDaemonConnected || isRecording || isPlaying || isPending || isWaitingForTouch || isRecordingPaused) return

        val macroDir = getMacroDir()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val macroPath = File(macroDir, "macro_$timestamp.bin").absolutePath
        lastMacroPath = macroPath

        // Start daemon recording IMMEDIATELY so it captures the first tap
        // The daemon reads from /dev/input/eventX at kernel level, so
        // ALL touches are captured regardless of any overlay windows.
        setPending(true)
        isWaitingForTouch = true
        binding?.tvTapToStart?.visibility = View.VISIBLE
        updateButtonStates()

        serviceScope.launch {
            val sent = daemonClient.sendCommand("START_REC $macroPath")
            if (!sent) {
                isPending = false
                isWaitingForTouch = false
                isDaemonConnected = false
                binding?.tvTapToStart?.visibility = View.GONE
                updateButtonStates()
                Toast.makeText(this@OverlayService, "Lost connection to daemon", Toast.LENGTH_SHORT).show()
            }
            // REC_STARTED handler will transition to timer UI
        }
    }

    // ─── Recording Timer & UI ────────────────────────────────────

    private fun showRecordingUI() {
        recStartTimeMs = System.currentTimeMillis()
        binding?.apply {
            tvTapToStart.visibility = View.GONE
            layoutRecording.visibility = View.VISIBLE
            layoutRecActions.visibility = View.GONE
            tvRecStatus.text = "● Rec 00:00"
        }
        // Start timer
        recTimerJob?.cancel()
        recTimerJob = serviceScope.launch {
            while (isActive && isRecording) {
                val elapsed = (System.currentTimeMillis() - recStartTimeMs) / 1000
                val mins = elapsed / 60
                val secs = elapsed % 60
                binding?.tvRecStatus?.text = "● Rec %02d:%02d".format(mins, secs)
                delay(1000)
            }
        }
    }

    private fun onDoneClicked() {
        if (!isRecording) return

        // Calculate recording duration before stopping
        val durationMs = System.currentTimeMillis() - recStartTimeMs

        // Stop the daemon recording
        serviceScope.launch {
            daemonClient.sendCommand("STOP_REC")
        }
        recTimerJob?.cancel()
        isRecording = false
        isRecordingPaused = true

        // Save duration to config
        lastMacroPath?.let { path ->
            val binFile = File(path)
            if (binFile.exists()) {
                val config = MacroConfig.load(binFile)
                MacroConfig.save(binFile, config.copy(durationMs = durationMs))
            }
        }

        // Show Cancel / Save
        showRecActionsUI()
        updateButtonStates()
        updateNotification()
    }

    private fun showRecActionsUI() {
        binding?.apply {
            layoutRecording.visibility = View.GONE
            layoutRecActions.visibility = View.VISIBLE
        }
    }

    private fun onCancelClicked() {
        // Show confirmation dialog using a toast-like approach since we're a service
        // We need a themed context for AlertDialog
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MacroEngine)
        val dialog = android.app.AlertDialog.Builder(themedContext, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Discard Recording?")
            .setMessage("This will delete the recorded macro.")
            .setPositiveButton("Discard") { _, _ ->
                // Delete the recorded file
                lastMacroPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                isRecordingPaused = false
                resetOverlayUI()
                updateButtonStates()
                updateNotification()
                Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Keep", null)
            .create()

        // Service dialogs need TYPE_APPLICATION_OVERLAY
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun onSaveClicked() {
        isRecordingPaused = false
        resetOverlayUI()
        updateButtonStates()
        updateNotification()
        Toast.makeText(this, "Macro saved", Toast.LENGTH_SHORT).show()
    }

    private fun resetOverlayUI() {
        binding?.apply {
            tvTapToStart.visibility = View.GONE
            layoutRecording.visibility = View.GONE
            layoutRecActions.visibility = View.GONE
        }
    }

    private fun onStopClicked() {
        if (isWaitingForTouch) {
            // Cancel the "waiting for touch" state — also stop the daemon recording
            isWaitingForTouch = false
            binding?.tvTapToStart?.visibility = View.GONE

            // Stop daemon recording and delete the empty file
            serviceScope.launch {
                daemonClient.sendCommand("STOP_REC")
            }
            lastMacroPath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            isRecording = false
            isPending = false
            updateButtonStates()
            updateNotification()
            Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isDaemonConnected) return

        if (isCountingDown) {
            countdownJob?.cancel()
            isCountingDown = false
            isPending = false
            binding?.tvCountdown?.visibility = View.GONE
            updateButtonStates()
            Toast.makeText(this, "Playback cancelled", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            if (isRecording) {
                daemonClient.sendCommand("STOP_REC")
                // Wait for REC_STOPPED ack to update state
            } else if (isPlaying) {
                daemonClient.sendCommand("STOP_PLAY")
                // Wait for PLAY_STOPPED ack to update state
            }
        }
    }

    // ─── Trigger Activation ────────────────────────────────────────

    /** Enable or disable a trigger button — called from macro list switch */
    private fun setTrigger(macroPath: String, enable: Boolean) {
        val binFile = File(macroPath)
        if (!binFile.exists()) {
            Toast.makeText(this, "Macro file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val manager = triggerManager
        if (manager == null) {
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Always remove existing trigger first
        manager.removeTrigger(macroPath)

        // Update config
        var config = MacroConfig.load(binFile)
        val trigger = (config.trigger ?: TriggerConfig()).copy(enabled = enable)
        config = config.copy(trigger = trigger)
        MacroConfig.save(binFile, config)

        if (enable) {
            manager.showTrigger(binFile, config)
            Toast.makeText(this, "Trigger enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Trigger disabled", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Volume Key Trigger ──────────────────────────────────────

    private fun registerVolumeKeyListener() {
        volumeKeyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return

                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType != AudioManager.STREAM_MUSIC) return

                // Volume key was pressed — toggle record/stop
                if (isRecording) {
                    onStopClicked()
                } else if (!isPlaying) {
                    onRecordClicked()
                }
            }
        }

        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        registerReceiver(volumeKeyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "Volume key listener registered")
    }

    private fun unregisterVolumeKeyListener() {
        volumeKeyReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeKeyReceiver = null
    }

    // ─── UI State ────────────────────────────────────────────────

    private fun updateButtonStates() {
        binding?.apply {
            val idle = !isRecording && !isPlaying && !isWaitingForTouch
                && !isRecordingPaused && !isCountingDown && !isPending
            val active = isRecording || isPlaying || isCountingDown || isWaitingForTouch

            // Record: visible only when idle
            btnRecord.visibility = if (idle) View.VISIBLE else View.GONE
            btnRecord.isEnabled = isDaemonConnected
            btnRecord.alpha = if (btnRecord.isEnabled) 1.0f else 0.4f

            // Stop: visible only during active states
            btnStop.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun getMacroDir(): File {
        val dir = File(filesDir, "macros")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getLatestMacroFile(): String? {
        return getMacroDir().listFiles()
            ?.filter { it.extension == "bin" }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    // ─── Notifications ───────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Macro overlay service notification"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val statusText = when {
            isRecording -> "🔴 Recording…"
            isPlaying -> "▶️ Playing…"
            else -> getString(R.string.notification_content)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        // Add action buttons to notification
        if (isRecording || isPlaying) {
            val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
            val stopPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
        } else {
            val recordIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_RECORD }
            val recordPI = PendingIntent.getService(this, 1, recordIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_btn_speak_now, "Record", recordPI)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
