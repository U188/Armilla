package io.github.mangi.eta.agent.voice.doubao

import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class VoiceCatalogSigningTransportTest {
    @Test fun signedHeadersAndUtf8BodySurviveRealHttpTransport() {
        val body = """{"ProjectName":"中文项目","State":"Unknown","PageNumber":1,"PageSize":100}"""
        val signed = DoubaoVoiceCatalog.signedRequest("test-ak", "test-secret", body, Instant.parse("2026-09-19T12:00:00Z"))
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val received = FutureTask<Pair<Map<String, String>, ByteArray>> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val out = java.io.ByteArrayOutputStream()
                        while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; if (b != 13) out.write(b) }
                        return out.toString("UTF-8")
                    }
                    line()
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val row = line(); if (row.isEmpty()) break
                        headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim()
                    }
                    val bytes = input.readNBytes(headers.getValue("content-length").toInt())
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    headers to bytes
                }
            }
            val worker = thread(isDaemon = true) { received.run() }
            try {
                DoubaoVoiceCatalog.client.newCall(signed.newBuilder().url("http://127.0.0.1:${server.localPort}/?${signed.url.encodedQuery}").build()).execute().use { assertEquals(200, it.code) }
                val (headers, bytes) = received.get(5, TimeUnit.SECONDS)
                assertEquals(DoubaoVoiceCatalog.CONTENT_TYPE, headers["content-type"])
                assertEquals("open.volcengineapi.com", headers["host"])
                assertEquals(signed.header("Authorization"), headers["authorization"])
                assertEquals(signed.header("X-Date"), headers["x-date"])
                assertEquals(signed.header("X-Content-Sha256"), headers["x-content-sha256"])
                assertArrayEquals(body.toByteArray(Charsets.UTF_8), bytes)
                assertTrue(headers.getValue("authorization").contains("SignedHeaders=content-type;host;x-content-sha256;x-date"))
                assertFalse(DoubaoVoiceCatalog.client.followRedirects)
            } finally { server.close(); worker.join(1000) }
        }
    }
}
