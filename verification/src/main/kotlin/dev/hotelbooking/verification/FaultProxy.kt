package dev.hotelbooking.verification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private class FaultState {
    @Volatile var delayCommit = false

    @Volatile var blockRelease = false

    @Volatile var bookingId = ""

    @Volatile var traceId = ""
    val commits = AtomicInteger()
}

/** Test-only transport fault injector; it never fabricates a successful Hotel effect. */
fun main() {
    val mapper = jacksonObjectMapper()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    val state = FaultState()
    val server = HttpServer.create(InetSocketAddress(8090), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/__control") { exchange ->
        if (exchange.requestMethod == "POST") {
            val command = mapper.readTree(exchange.requestBody)
            state.delayCommit = command.path("delayCommit").asBoolean()
            state.blockRelease = command.path("blockRelease").asBoolean()
        }
        exchange.reply(
            200,
            mapper.writeValueAsBytes(
                mapOf(
                    "commits" to state.commits.get(),
                    "bookingId" to state.bookingId,
                    "traceId" to state.traceId,
                ),
            ),
        )
    }
    server.createContext("/") { exchange ->
        try {
            val path = exchange.requestURI.toString()
            val body = exchange.requestBody.readBytes()
            if (path.endsWith("/release") && state.blockRelease) {
                exchange.reply(503, "{\"code\":\"INJECTED_RELEASE_FAILURE\"}".toByteArray())
            } else {
                // The fault proxy forwards to Hotel over the isolated system-test Compose network.
                @Suppress("HttpUrlsUsage")
                val request =
                    HttpRequest
                        .newBuilder(URI.create("http://hotel-service:8082$path"))
                        .timeout(Duration.ofSeconds(10))
                for (name in listOf("Authorization", "Content-Type", "X-Trace-Id")) {
                    exchange.requestHeaders.getFirst(name)?.let { request.header(name, it) }
                }
                request.method(
                    exchange.requestMethod,
                    if (body.isEmpty()) {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofByteArray(body)
                    },
                )
                val result = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                if (path.endsWith("/commit") && result.statusCode() == 200) {
                    state.bookingId = mapper.readTree(body).path("bookingId").asText()
                    state.traceId = exchange.requestHeaders.getFirst("X-Trace-Id") ?: ""
                    state.commits.incrementAndGet()
                    if (state.delayCommit) Thread.sleep(2500)
                }
                exchange.reply(result.statusCode(), result.body())
            }
        } catch (_: Exception) {
            // A timed-out caller can close its socket after the real upstream commit.
            exchange.close()
        }
    }
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
}

private fun HttpExchange.reply(
    status: Int,
    bytes: ByteArray,
) {
    responseHeaders.set("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
