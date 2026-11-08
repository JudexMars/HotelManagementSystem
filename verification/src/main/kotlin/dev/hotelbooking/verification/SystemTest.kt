package dev.hotelbooking.verification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    val project = "hotel-system-${UUID.randomUUID().toString().take(8)}"
    val output = Path.of("verification/build/system-test", project).toAbsolutePath()
    Files.createDirectories(output)
    val ports = mutableSetOf<Int>()

    fun port(): String {
        while (true) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (ports.add(candidate)) return candidate.toString()
        }
    }
    val environment = mapOf("GATEWAY_PORT" to port(), "DISCOVERY_PORT" to port(), "FAULT_PORT" to port())
    val compose =
        listOf(
            "docker",
            "compose",
            "-p",
            project,
            "-f",
            "compose.yaml",
            "-f",
            "verification/compose.system-test.yaml",
        )

    fun command(
        name: String,
        arguments: List<String>,
        timeout: Long = 600,
    ): Int {
        val process =
            ProcessBuilder(compose + arguments)
                .redirectErrorStream(true)
                .redirectOutput(output.resolve("$name.log").toFile())
                .apply { environment().putAll(environment) }
                .start()
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("$name timed out; logs: $output")
        }
        return process.exitValue()
    }
    println("System test project: $project; logs: $output")
    try {
        check(command("up", listOf("up", "--build", "--wait", "--wait-timeout", "240")) == 0) { "Compose startup failed: $output" }
        check("--fail-after-start" !in args) { "Intentional failure to verify cleanup" }
        val api = SystemApi("http://127.0.0.1:${environment.getValue("GATEWAY_PORT")}")
        val proxy = SystemApi("http://127.0.0.1:${environment.getValue("FAULT_PORT")}")
        val discovery = SystemApi("http://127.0.0.1:${environment.getValue("DISCOVERY_PORT")}")
        awaitCondition("Eureka registrations") {
            val apps =
                discovery
                    .call("GET", "/eureka/apps")
                    .json
                    .path("applications")
                    .path("application")
            apps.map { it.path("name").asText() }.toSet().containsAll(setOf("BOOKING-SERVICE", "HOTEL-SERVICE", "API-GATEWAY"))
        }
        DemoSeed(api.base).apply {
            seed()
            verify()
        }
        val trace = "system-${UUID.randomUUID()}"
        val admin =
            api
                .call("POST", "/api/users/auth", body = mapOf("username" to "demo-admin", "password" to "demo-admin-password"))
                .json
                .path("accessToken")
                .asText()
        val registration =
            api.call(
                "POST",
                "/api/users/register",
                body =
                    mapOf(
                        "username" to "system-user",
                        "password" to "system-user-password",
                    ),
            )
        check(registration.status == 201)
        val user = registration.json.path("accessToken").asText()
        val forbidden = api.call("GET", "/api/users", user, expected = 403, trace = trace)
        check(forbidden.json.path("traceId").asText() == trace && forbidden.trace == trace)
        check(
            forbidden.json.path("code").asText() == "FORBIDDEN" &&
                forbidden.json
                    .path("message")
                    .asText()
                    .isNotBlank(),
        )
        api.call("GET", "/api/bookings", expected = 401)
        api.call("GET", "/internal/rooms", admin, expected = 404)
        val hotel =
            api
                .call(
                    "POST",
                    "/api/hotels",
                    admin,
                    mapOf("name" to "Acceptance", "address" to "System test"),
                ).json
                .path("id")
                .asText()
        val room =
            api
                .call("POST", "/api/rooms", admin, mapOf("hotelId" to hotel, "number" to "1"))
                .json
                .path("id")
                .asText()
        val start = LocalDate.now(ZoneOffset.UTC).plusDays(20)
        val end = start.plusDays(3)
        val query = "startDate=$start&endDate=$end&hotelId=$hotel"
        val recommendations = api.call("GET", "/api/bookings/recommendations?$query", user).json
        check(recommendations.single().path("id").asText() == room)
        val request = mapOf("autoSelect" to true, "hotelId" to hotel, "startDate" to "$start", "endDate" to "$end")
        val booking = api.call("POST", "/api/bookings", user, request, key = "system-success").json
        check(booking.path("status").asText() == "CONFIRMED" && booking.path("roomId").asText() == room)
        val cancelled = api.call("DELETE", "/api/bookings/${booking.path("id").asText()}", user).json
        check(cancelled.path("compensationStatus").asText() == "DONE")
        check(
            api
                .call("GET", "/api/rooms/$room", user)
                .json
                .path("timesBooked")
                .asInt() == 0,
        )
        val history = api.call("GET", "/api/bookings", user).json.path("items")
        check(history.single().path("id").asText() == booking.path("id").asText())

        proxy.call("POST", "/__control", body = mapOf("delayCommit" to true, "blockRelease" to true))
        val before =
            proxy
                .call("GET", "/__control")
                .json
                .path("commits")
                .asInt()
        val failed =
            CompletableFuture.supplyAsync {
                api.call("POST", "/api/bookings", user, request, expected = 504, key = "system-timeout", trace = trace)
            }
        awaitCondition("real upstream commit") {
            proxy
                .call("GET", "/__control")
                .json
                .path("commits")
                .asInt() > before
        }
        check(
            api
                .call("GET", "/api/rooms/$room", user)
                .json
                .path("timesBooked")
                .asInt() == 1,
        )
        val error = failed.get(30, TimeUnit.SECONDS)
        check(error.json.path("code").asText() == "HOTEL_TIMEOUT")
        check(error.json.path("status").asText() == "CANCELLED")
        check(error.json.path("compensationStatus").asText() == "PENDING")
        check(error.json.path("traceId").asText() == trace && error.trace == trace)
        val failedId = error.json.path("bookingId").asText()
        val captured = proxy.call("GET", "/__control").json
        check(captured.path("bookingId").asText() == failedId && captured.path("traceId").asText() == trace)
        proxy.call("POST", "/__control", body = mapOf("delayCommit" to false, "blockRelease" to false))
        awaitCondition("background compensation") {
            api
                .call("GET", "/api/bookings/$failedId", user)
                .json
                .path("compensationStatus")
                .asText() == "DONE"
        }
        check(
            api
                .call("GET", "/api/rooms/$room", user)
                .json
                .path("timesBooked")
                .asInt() == 0,
        )
        check(
            api
                .call("GET", "/api/rooms?$query", user)
                .json
                .single()
                .path("id")
                .asText() == room,
        )
        val report =
            api
                .call("GET", "/api/hotels/$hotel/occupancy?from=$start&to=$end", admin)
                .json
                .path("rooms")
                .single()
        check(report.path("roomId").asText() == room && report.path("bookedNights").asInt() == 0)
        verifyConcurrentBookings(api, admin, user)
        check(command("application", listOf("logs", "--no-color")) == 0)
        val logs = Files.readString(output.resolve("application.log"))
        check(logs.lineSequence().any { it.contains(trace) && it.contains(failedId) }) { "Missing correlated booking/trace log" }
        println("PASS Gateway/Eureka: registration, roles, catalog, recommendation, booking, cancellation, history and report")
        println("PASS timeout after real commit: counter 1, CANCELLED/PENDING, background DONE, counter 0 and freed dates")
    } finally {
        runCatching { command("final-application", listOf("logs", "--no-color"), 30) }
            .onFailure { println("Could not collect final logs: ${it.javaClass.simpleName}") }
        check(command("down", listOf("down", "--remove-orphans", "--volumes", "--rmi", "local"), 60) == 0) { "Cleanup failed: $output" }
        check(command("remaining", listOf("ps", "--all", "--quiet"), 30) == 0)
        check(Files.readString(output.resolve("remaining.log")).isBlank()) { "Test containers remain: $output" }
        println("Cleaned isolated project $project")
    }
}

internal fun awaitCondition(
    description: String,
    condition: () -> Boolean,
) {
    val until = System.nanoTime() + Duration.ofSeconds(40).toNanos()
    while (System.nanoTime() < until) {
        if (condition()) return
        Thread.sleep(100)
    }
    error("Timed out waiting for $description")
}

internal data class ApiResult(
    val status: Int,
    val json: JsonNode,
    val trace: String?,
)

internal class SystemApi(
    val base: String,
) {
    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun call(
        method: String,
        path: String,
        token: String? = null,
        body: Any? = null,
        expected: Int? = null,
        key: String? = null,
        trace: String? = null,
        allowedStatuses: Set<Int>? = null,
    ): ApiResult {
        val builder =
            HttpRequest
                .newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(35))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        key?.let { builder.header("Idempotency-Key", it) }
        trace?.let { builder.header("X-Trace-Id", it) }
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(
            allowedStatuses?.contains(response.statusCode())
                ?: if (expected == null) response.statusCode() in 200..299 else response.statusCode() == expected,
        ) {
            "$method $path: expected ${expected ?: "2xx"}, got ${response.statusCode()}"
        }
        return ApiResult(response.statusCode(), mapper.readTree(response.body()), response.headers().firstValue("X-Trace-Id").orElse(null))
    }
}
