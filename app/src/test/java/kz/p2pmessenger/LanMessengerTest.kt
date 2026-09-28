package kz.p2pmessenger

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class LanMessengerTest {
    @Test fun sendRetainsNewlineDelimitedUtf8Protocol() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            LanMessenger(server.localPort).send("127.0.0.1", "Привет, телефон 👋")
            server.accept().use { client ->
                client.soTimeout = 5000
                val reader = client.getInputStream().bufferedReader()
                assertEquals("Привет, телефон 👋", reader.readLine())
                assertEquals(null, reader.readLine())
            }
        }
    }

    @Test fun receiveAcceptsConsecutiveV01Connections() {
        val port = ServerSocket(0).use { it.localPort }
        val received = LinkedBlockingQueue<String>()
        val messenger = LanMessenger(port)
        messenger.onMessage = { received.add(it) }
        messenger.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun connect(): Socket {
            while (true) {
                try { return Socket("127.0.0.1", port) }
                catch (error: java.net.ConnectException) {
                    if (System.nanoTime() >= deadline) throw error
                    Thread.sleep(20)
                }
            }
        }
        for (message in listOf("Первая строка 👋", "Второе сообщение")) {
            connect().use { socket ->
                socket.getOutputStream().bufferedWriter().apply {
                    write(message)
                    newLine()
                    flush()
                }
            }
            assertEquals(message, received.poll(5, TimeUnit.SECONDS))
        }
    }
}
