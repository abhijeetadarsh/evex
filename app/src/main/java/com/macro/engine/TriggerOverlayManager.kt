package com.macro.engine

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ContextThemeWrapper
import android.view.animation.LinearInterpolator
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages floating trigger buttons for macros with configured triggers.
 * Each trigger is an independent overlay window that executes its macro
 * on tap or hold, respecting the per-macro config (delay, repeat, speed).
 */
class TriggerOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val daemonClient: DaemonClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TriggerOverlayManager"
    }

    private data class TriggerEntry(
        val view: View,
        val params: WindowManager.LayoutParams,
        val binPath: String,
        val config: MacroConfig,
        var playJob: Job? = null,
        var progressDrawable: TriggerProgressDrawable? = null,
        var progressAnimator: ValueAnimator? = null
    )

    private val triggers = mutableMapOf<String, TriggerEntry>()

    /**
     * Scan the macro directory and show triggers for all enabled configs.
     */
    fun loadAllTriggers(macroDir: File) {
        removeAll()
        val binFiles = macroDir.listFiles()?.filter { it.extension == "bin" } ?: return
        for (binFile in binFiles) {
            val config = MacroConfig.load(binFile)
            if (config.trigger?.enabled == true) {
                showTrigger(binFile, config)
            }
        }
    }

    fun showTrigger(binFile: File, config: MacroConfig) {
        val key = binFile.absolutePath
        // Remove existing trigger for this macro first
        removeTrigger(key)

        val trigger = config.trigger ?: return
        if (!trigger.enabled) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_MacroEngine)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.trigger_button, null)

        // Use TriggerProgressDrawable for circle shape (supports progress animation)
        val progressDrawable = if (trigger.shape == "circle") {
            val density = context.resources.displayMetrics.density
            TriggerProgressDrawable(borderWidth = 3f * density).also {
                view.background = it
            }
        } else {
            // For non-circle shapes, use static drawables
            view.setBackgroundResource(
                when (trigger.shape) {
                    "square" -> R.drawable.trigger_shape_square
                    else -> R.drawable.trigger_shape_rounded
                }
            )
            null
        }

        // Apply opacity
        view.alpha = trigger.opacity

        // Set label — first letter of macro name or ▶
        val tv = view.findViewById<android.widget.TextView>(R.id.tvTriggerLabel)
        tv.text = config.name.firstOrNull()?.uppercase() ?: "▶"

        val sizePx = (48 * context.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = trigger.x
            y = trigger.y
        }

        val entry = TriggerEntry(view, params, key, config,
            progressDrawable = progressDrawable)
        setupTriggerTouch(entry, trigger, binFile)

        try {
            windowManager.addView(view, params)
            triggers[key] = entry
            Log.i(TAG, "Trigger shown for: ${binFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show trigger for ${binFile.name}", e)
        }
    }

    private fun setupTriggerTouch(entry: TriggerEntry, trigger: TriggerConfig, binFile: File) {
        val handler = Handler(Looper.getMainLooper())
        val longPressDelayMs = 400L

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isLongPressed = false
        var isDragging = false
        var isHolding = false

        val longPressRunnable = Runnable {
            isLongPressed = true
            // Haptic feedback to signal drag mode is active
            entry.view.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }

        entry.view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = entry.params.x
                    initialY = entry.params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isLongPressed = false
                    isDragging = false

                    // Schedule long-press detection
                    handler.postDelayed(longPressRunnable, longPressDelayMs)

                    // For hold mode, start playing on press
                    if (trigger.mode == "hold") {
                        isHolding = true
                        entry.playJob = startExecution(entry, entry.config, binFile, infinite = true)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val distSq = dx * dx + dy * dy
                    val touchSlop = (10 * context.resources.displayMetrics.density)

                    // Cancel long-press if finger moved too far before it triggered
                    if (!isLongPressed && distSq > touchSlop * touchSlop) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // Only drag after long-press is confirmed and trigger is unlocked
                    if (isLongPressed && !trigger.locked) {
                        isDragging = true
                        entry.params.x = initialX + dx.toInt()
                        entry.params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(entry.view, entry.params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)

                    if (isDragging && !trigger.locked) {
                        // Save updated position after drag
                        saveTriggerPosition(binFile, entry.params.x, entry.params.y)
                    } else if (!isLongPressed && trigger.mode == "tap") {
                        // Short tap — execute macro
                        entry.playJob = startExecution(entry, entry.config, binFile, infinite = false)
                    }

                    // For hold mode, stop on release
                    if (trigger.mode == "hold" && isHolding) {
                        isHolding = false
                        stopExecution(entry)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (trigger.mode == "hold" && isHolding) {
                        isHolding = false
                        stopExecution(entry)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startExecution(entry: TriggerEntry, config: MacroConfig, binFile: File, infinite: Boolean): Job {
        // Start progress animation if we know the duration
        if (!infinite && config.durationMs > 0 && entry.progressDrawable != null) {
            val animDuration = (config.durationMs / config.speed).toLong()
            entry.progressAnimator?.cancel()
            entry.progressDrawable!!.isActive = true
            entry.progressDrawable!!.progress = 0f
            entry.progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = animDuration
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    entry.progressDrawable!!.progress = anim.animatedValue as Float
                }
                start()
            }
        } else if (entry.progressDrawable != null) {
            // For infinite/unknown duration, just show active state
            entry.progressDrawable!!.isActive = true
            entry.progressDrawable!!.progress = 1f
        }

        return scope.launch {
            // Start delay
            if (config.startDelayMs > 0) {
                delay(config.startDelayMs.toLong())
            }

            // Try to send a command. Java's Socket.isConnected() is unreliable
            // after the remote end closes — the true failure only surfaces when
            // the write throws. So we attempt the send first, and on failure we
            // reconnect once and retry, rather than pre-checking isConnected.
            suspend fun trySend(command: String): Boolean {
                if (daemonClient.sendCommand(command)) return true
                Log.w(TAG, "Send failed for '$command', attempting reconnect…")
                val reconnected = daemonClient.connect()
                if (!reconnected) {
                    Log.e(TAG, "Reconnect failed — daemon not available")
                    return false
                }
                Log.i(TAG, "Reconnected, retrying: $command")
                return daemonClient.sendCommand(command)
            }

            // 1. Set playback speed
            if (!trySend("SET_SPEED ${config.speed}")) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Daemon not connected — cannot play macro", Toast.LENGTH_SHORT).show()
                    resetProgress(entry)
                }
                return@launch
            }

            // 2. Build and send play command
            val count = if (infinite) 0 else config.repeatCount
            val command = if (count == 1) {
                "PLAY ${binFile.absolutePath}"
            } else {
                "PLAY_LOOP $count ${binFile.absolutePath}"
            }

            if (!trySend(command)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to send play command", Toast.LENGTH_SHORT).show()
                    resetProgress(entry)
                }
            } else {
                Log.i(TAG, "Play command sent: $command")
            }
        }
    }

    private fun resetProgress(entry: TriggerEntry) {
        entry.progressAnimator?.cancel()
        entry.progressAnimator = null
        entry.progressDrawable?.isActive = false
        entry.progressDrawable?.progress = 0f
    }

    private fun stopExecution(entry: TriggerEntry) {
        entry.playJob?.cancel()
        entry.playJob = null
        resetProgress(entry)
        scope.launch {
            daemonClient.sendCommand("STOP_PLAY")
        }
    }

    private fun saveTriggerPosition(binFile: File, x: Int, y: Int) {
        val config = MacroConfig.load(binFile)
        val updatedTrigger = config.trigger?.copy(x = x, y = y) ?: return
        MacroConfig.save(binFile, config.copy(trigger = updatedTrigger))
    }

    fun isTriggerActive(key: String): Boolean = triggers.containsKey(key)

    fun removeTrigger(key: String) {
        triggers.remove(key)?.let { entry ->
            entry.playJob?.cancel()
            resetProgress(entry)
            try { windowManager.removeView(entry.view) } catch (_: Exception) {}
            Log.i(TAG, "Trigger removed: $key")
        }
    }

    fun removeAll() {
        triggers.values.forEach { entry ->
            entry.playJob?.cancel()
            resetProgress(entry)
            try { windowManager.removeView(entry.view) } catch (_: Exception) {}
        }
        triggers.clear()
        Log.i(TAG, "All triggers removed")
    }
}
