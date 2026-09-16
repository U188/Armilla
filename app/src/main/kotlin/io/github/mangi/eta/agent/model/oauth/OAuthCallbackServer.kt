package io.github.mangi.eta.agent.model.oauth

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

internal class OAuthCallbackServer(
    private val port: Int,
    private val onCode: (code: String, state: String?) -> Unit,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var onExternalCancel: (() -> Unit)? = null

    fun start() {
        running = true
        Thread({
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (running) {
                    val client = socket.accept()
                    handleClient(client)
                }
            } catch (_: Exception) {
                if (running) {
                    // bind / accept failed; login waiter will time out or cancel
                }
            }
        }, "eta-oauth-callback").apply { isDaemon = true }.start()
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (serverSocket != null) return
            Thread.sleep(50)
        }
        if (serverSocket == null) {
            running = false
            error("无法监听 localhost:$port，请确认没有其它应用占用该端口")
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            if (requestLine.startsWith("OPTIONS")) {
                drainHeaders(reader)
                write(client, "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
                return
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val uri = URI("http://localhost${parts[1]}")
            val params = uri.rawQuery
                ?.split("&")
                ?.mapNotNull { pair ->
                    val kv = pair.split("=", limit = 2)
                    if (kv.isEmpty() || kv[0].isBlank()) return@mapNotNull null
                    val key = URLDecoder.decode(kv[0], Charsets.UTF_8)
                    val value = if (kv.size > 1) URLDecoder.decode(kv[1], Charsets.UTF_8) else ""
                    key to value
                }
                ?.toMap()
                .orEmpty()
            val code = params["code"]
            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
            write(
                client,
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html",
            )
            if (!code.isNullOrBlank()) {
                onCode(code, params["state"])
                stop()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun drainHeaders(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
    }

    private fun write(client: Socket, payload: String) {
        val out = client.getOutputStream()
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun stop() {
        val wasRunning = running
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            runCatching { cancel?.invoke() }
        }
    }
}
