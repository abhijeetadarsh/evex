package com.macro.engine

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.macro.engine.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Main activity — handles root permission, daemon lifecycle, overlay toggle,
 * and macro list management.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val DAEMON_BINARY_NAME = "macro_daemon"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var macroAdapter: MacroAdapter
    private val daemonClient = DaemonClient()
    private var daemonConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()

        // Start initialization sequence
        lifecycleScope.launch {
            initializeApp()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMacroList()
    }

    override fun onDestroy() {
        daemonClient.disconnect()
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        macroAdapter = MacroAdapter(
            onPlayClick = { file -> playMacro(file) },
            onDeleteClick = { file -> deleteMacro(file) }
        )
        binding.rvMacros.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = macroAdapter
        }
    }

    private fun setupListeners() {
        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startOverlayService()
            } else {
                stopOverlayService()
            }
        }
    }

    private suspend fun initializeApp() {
        // Step 1: Check root access
        updateStatus("Checking root access…", false)

        val hasRoot = withContext(Dispatchers.IO) {
            RootHelper.checkRoot()
        }

        if (!hasRoot) {
            updateStatus("Root access denied", false)
            AlertDialog.Builder(this)
                .setTitle("Root Required")
                .setMessage(getString(R.string.root_required))
                .setPositiveButton("Retry") { _, _ ->
                    lifecycleScope.launch { initializeApp() }
                }
                .setNegativeButton("Exit") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        updateStatus("Root access granted", false)

        // Step 2: Deploy and launch daemon
        updateStatus("Starting daemon…", false)

        val daemonPath = deployDaemonBinary()
        if (daemonPath == null) {
            updateStatus("Failed to deploy daemon binary", false)
            Toast.makeText(this, "Failed to deploy daemon binary", Toast.LENGTH_LONG).show()
            return
        }

        // Kill any existing daemon first
        withContext(Dispatchers.IO) {
            RootHelper.killDaemon()
            delay(300)
        }

        val launched = withContext(Dispatchers.IO) {
            RootHelper.launchDaemon(daemonPath)
        }

        if (!launched) {
            updateStatus("Failed to launch daemon", false)
            Toast.makeText(this, "Failed to launch daemon", Toast.LENGTH_LONG).show()
            return
        }

        // Step 3: Connect to daemon
        updateStatus("Connecting to daemon…", false)
        delay(1000) // Give daemon time to start listening

        val connected = daemonClient.connect()
        if (connected) {
            daemonConnected = true
            updateStatus("Connected", true)

            // Send a ping to verify
            val response = daemonClient.sendAndReceive("PING")
            if (response == "PONG") {
                Log.i(TAG, "Daemon is alive and responding")
            }

            // IMPORTANT: Disconnect now so the OverlayService can connect.
            // The daemon only serves one client at a time.
            daemonClient.disconnect()
            Log.i(TAG, "Disconnected from daemon — OverlayService will take over")
        } else {
            updateStatus("Failed to connect to daemon", false)
            Toast.makeText(this, "Failed to connect to daemon", Toast.LENGTH_LONG).show()
        }

        // Step 4: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            binding.switchOverlay.isEnabled = false
            Toast.makeText(this, getString(R.string.overlay_permission_needed), Toast.LENGTH_LONG).show()
            requestOverlayPermission()
        }

        refreshMacroList()
    }

    /**
     * Copy the daemon binary from the APK's native libs to the app's files directory.
     * The binary is packaged as libmacro_daemon.so to satisfy Android's APK packaging rules.
     */
    private fun deployDaemonBinary(): String? {
        return try {
            val sourceDir = applicationInfo.nativeLibraryDir
            Log.i(TAG, "Native library dir: $sourceDir")

            // List all files in the native lib directory for debugging
            val nativeDir = File(sourceDir)
            if (nativeDir.exists()) {
                val files = nativeDir.listFiles()
                Log.i(TAG, "Native lib contents: ${files?.map { it.name }}")
            }

            // The daemon is built as libmacro_daemon.so to pass APK packaging
            val sourceFile = File(sourceDir, "libmacro_daemon.so")
            if (!sourceFile.exists()) {
                Log.e(TAG, "Daemon binary not found at: ${sourceFile.absolutePath}")
                return null
            }

            val destFile = File(filesDir, DAEMON_BINARY_NAME)
            val destPath = destFile.absolutePath

            // Copy the binary
            sourceFile.copyTo(destFile, overwrite = true)
            Log.i(TAG, "Copied daemon binary to: $destPath (${destFile.length()} bytes)")

            // Make it executable via root
            RootHelper.execRoot("chmod 755 $destPath")
            Log.i(TAG, "Made daemon executable")

            destPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy daemon binary", e)
            null
        }
    }

    private fun refreshMacroList() {
        val macroDir = File(filesDir, "macros")
        val files = macroDir.listFiles()?.filter { it.extension == "bin" } ?: emptyList()

        if (files.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvMacros.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvMacros.visibility = View.VISIBLE
            macroAdapter.updateMacros(files)
        }
    }

    private fun playMacro(file: File) {
        // Route through OverlayService — MainActivity's daemonClient is disconnected.
        // The OverlayService holds the sole daemon connection.
        val intent = Intent(this, OverlayService::class.java).apply {
            action = "ACTION_PLAY_FILE"
            putExtra("macro_path", file.absolutePath)
        }
        startForegroundService(intent)
        Toast.makeText(this, "Playing: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
    }

    private fun deleteMacro(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Macro")
            .setMessage("Delete ${file.nameWithoutExtension}?")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                refreshMacroList()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            binding.switchOverlay.isChecked = false
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, OverlayService::class.java)
        startForegroundService(intent)
        Log.i(TAG, "Overlay service started")
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        Log.i(TAG, "Overlay service stopped")
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            binding.switchOverlay.isEnabled = Settings.canDrawOverlays(this)
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus(text: String, connected: Boolean) {
        binding.tvDaemonStatus.text = text
        binding.viewStatusDot.setBackgroundResource(
            if (connected) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
    }
}
