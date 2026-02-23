package com.macro.engine

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client for communicating with the C++ daemon over a TCP loopback socket.
 * Uses 127.0.0.1 to bypass SELinux restrictions on cross-context UNIX sockets.
 * All operations run on IO dispatcher coroutines.
 */
class DaemonClient {

    companion object {
        private const val TAG = "DaemonClient"
        private const val DAEMON_HOST = "127.0.0.1"
        private const val DAEMON_PORT = 52398
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val MAX_RETRIES = 10
        private const val RETRY_DELAY_MS = 500L
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    var onStatusReceived: ((String) -> Unit)? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Connect to the daemon TCP socket. Retries up to MAX_RETRIES times.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        for (attempt in 1..MAX_RETRIES) {
            try {
                val tcpSocket = Socket()
                tcpSocket.connect(
                    InetSocketAddress(DAEMON_HOST, DAEMON_PORT),
                    CONNECT_TIMEOUT_MS
                )

                socket = tcpSocket
                writer = PrintWriter(tcpSocket.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(tcpSocket.getInputStream()))

                Log.i(TAG, "Connected to daemon on attempt $attempt")
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        Log.e(TAG, "Failed to connect after $MAX_RETRIES attempts")
        false
    }

    /**
     * Send a command to the daemon.
     */
    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val w = writer
            if (w == null) {
                Log.w(TAG, "Cannot send command (not connected): $command")
                return@withContext false
            }
            w.println(command)
            Log.d(TAG, "Sent: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: $command", e)
            false
        }
    }

    /**
     * Read a single response line from the daemon (blocking).
     */
    suspend fun receiveResponse(): String? = withContext(Dispatchers.IO) {
        try {
            val line = reader?.readLine()
            Log.d(TAG, "Received: $line")
            line
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive response", e)
            null
        }
    }

    /**
     * Send command and wait for a response.
     */
    suspend fun sendAndReceive(command: String): String? {
        return if (sendCommand(command)) {
            receiveResponse()
        } else {
            null
        }
    }

    /**
     * Start a background listener for daemon status updates.
     * This runs in a loop and calls onStatusReceived for each message.
     */
    fun startListening(scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isConnected) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "Status: $line")
                    withContext(Dispatchers.Main) {
                        onStatusReceived?.invoke(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listening stopped", e)
            }
        }
    }

    /**
     * Disconnect from the daemon.
     */
    fun disconnect() {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        } finally {
            writer = null
            reader = null
            socket = null
            Log.i(TAG, "Disconnected from daemon")
        }
    }
}
