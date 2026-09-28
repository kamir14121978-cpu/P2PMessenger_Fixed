package kz.p2pmessenger

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LanMessenger(private val port: Int = 45888) {
    var onMessage: ((String) -> Unit)? = null
    fun start() = thread(isDaemon = true) {
        runCatching {
            ServerSocket(port).use { server ->
                while (true) server.accept().use { s ->
                    val msg = s.getInputStream().bufferedReader().readLine()
                    if (msg != null) onMessage?.invoke(msg)
                }
            }
        }
    }
    fun send(host: String, text: String) = thread(isDaemon = true) {
        runCatching { Socket(host, port).use { it.getOutputStream().bufferedWriter().apply { write(text); newLine(); flush() } } }
    }
}
