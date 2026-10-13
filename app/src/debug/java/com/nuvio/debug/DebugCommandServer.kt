package com.nuvio.debug

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.concurrent.thread

/** One bounded JSON request per connection. Never listens on the LAN. */
class DebugCommandServer(
    private val sessionFile: File,
    private val handle: (JsonObject) -> JsonObject,
) : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also { SecureRandom().nextBytes(it) },
    )
    @Volatile private var closed = false

    init {
        sessionFile.parentFile.mkdirs()
        sessionFile.writeText(buildJsonObject {
            put("protocol", 1)
            put("port", socket.localPort)
            put("token", token)
        }.toString())
        thread(name = "nuvio-debug-cli", isDaemon = true) {
            while (!closed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    it.soTimeout = 10_000
                    val response = runCatching {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        val line = StringBuilder()
                        while (true) {
                            val c = reader.read()
                            require(c >= 0) { "incomplete-request" }
                            if (c == 10) break
                            require(line.length < 65_536) { "request-too-large" }
                            line.append(c.toChar())
                        }
                        val request = Json.parseToJsonElement(line.toString()).jsonObject
                        if (request["token"]?.jsonPrimitive?.content != token) {
                            debugError("unauthorized")
                        } else handle(request)
                    }.getOrElse { debugError("invalid-request") }
                    runCatching {
                        it.getOutputStream().write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
                        it.getOutputStream().flush()
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        socket.close()
        if (runCatching { sessionFile.readText().contains(token) }.getOrDefault(false)) sessionFile.delete()
    }
}

fun debugError(code: String) = buildJsonObject { put("ok", false); put("error", code) }
fun debugOk() = buildJsonObject { put("ok", true) }
