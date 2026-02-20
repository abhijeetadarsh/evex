package com.macro.engine

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Helper for executing root (su) commands and managing the daemon process.
 */
object RootHelper {

    private const val TAG = "RootHelper"

    /**
     * Check if root access is available.
     */
    fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            reader.close()

            val isRoot = output.contains("uid=0")
            Log.i(TAG, "Root check: $isRoot (output: $output)")
            isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    /**
     * Execute a command as root and return the output.
     */
    fun execRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            output.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Root exec failed: $command", e)
            ""
        }
    }

    /**
     * Launch the daemon binary as root in the background.
     */
    fun launchDaemon(binaryPath: String): Boolean {
        return try {
            // Make the binary executable
            execRoot("chmod 755 $binaryPath")

            // Launch in background, redirect output to logcat
            val cmd = "$binaryPath &"
            Log.i(TAG, "Launching daemon: $cmd")

            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()

            // Don't wait for the process — it runs in background
            Thread.sleep(500) // Give it a moment to start

            Log.i(TAG, "Daemon launched successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch daemon", e)
            false
        }
    }

    /**
     * Kill a running daemon process.
     */
    fun killDaemon() {
        try {
            execRoot("pkill -f macro_daemon")
            Log.i(TAG, "Daemon killed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill daemon", e)
        }
    }

    /**
     * Check if the daemon process is running.
     */
    fun isDaemonRunning(): Boolean {
        val output = execRoot("pgrep -f macro_daemon")
        return output.isNotEmpty()
    }
}
