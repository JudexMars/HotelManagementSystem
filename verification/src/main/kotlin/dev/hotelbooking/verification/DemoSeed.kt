package dev.hotelbooking.verification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

fun main(args: Array<String>) {
    val base = args.firstOrNull { !it.startsWith("--") } ?: "http://localhost:8080"
    val demo = DemoSeed(base)
    if ("--verify-only" !in args) demo.seed()
    demo.verify()
    println("Demo verified: historical stay, future bookings, cancellation and exact room counters")
}

class DemoSeed(
    base: String,
) {
    private val base = base.trimEnd('/')
    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val today = LocalDate.now(ZoneOffset.UTC)
    private val user = login("demo-user", "demo-user-password")
    private val admin =
        login(
            System.getenv("BOOKING_DEMO_ADMIN_USERNAME") ?: "demo-admin",
            System.getenv("BOOKING_DEMO_ADMIN_PASSWORD") ?: "demo-admin-password",
        )

    private fun login(
        username: String,
        password: String,
    ): String =
        request("POST", "/api/users/auth", null, mapOf("username" to username, "password" to password))
            .path("accessToken")
            .asText()
            .also { check(it.isNotEmpty()) { "Login returned no token" } }

    private fun request(
        method: String,
        path: String,
        token: String?,
        body: Any? = null,
        key: String? = null,
    ): JsonNode {
        val builder =
            HttpRequest
                .newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        key?.let { builder.header("Idempotency-Key", it) }
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "$method $path returned HTTP ${response.statusCode()}; check application logs, then reset the demo session if necessary"
        }
        return mapper.readTree(response.body())
    }

    fun seed() {
        for (room in listOf(201, 204, 202)) {
            val booking =
                request(
                    "POST",
                    "/api/bookings",
                    user,
                    mapOf(
                        "autoSelect" to false,
                        "roomId" to id(room),
                        "startDate" to today.plusDays(7).toString(),
                        "endDate" to today.plusDays(10).toString(),
                    ),
                    "demo-room-$room",
                )
            if (room == 202) {
                val cancelled = request("DELETE", "/api/bookings/${booking.path("id").asText()}", user)
                check(cancelled.path("status").asText() == "CANCELLED")
                check(cancelled.path("compensationStatus").asText() == "DONE") {
                    "Demo cancellation is pending; wait for recovery and run the command again"
                }
            } else {
                check(booking.path("status").asText() == "CONFIRMED") { "Demo booking was not confirmed; reset the session" }
            }
        }
    }

    fun verify() {
        val history = request("GET", "/api/bookings?size=100", user).path("items")
        check(history.size() == 4) { "Expected historical, two future and one cancelled demo bookings; found ${history.size()}" }
        val past = history.single { it.path("id").asText() == id(301) }
        check(past.path("roomId").asText() == id(201) && past.path("attemptId").asText() == id(401))
        check(past.path("status").asText() == "CONFIRMED" && past.path("startDate").asText() == "2020-01-10")
        val future = history.filter { it.path("id").asText() != id(301) }.associateBy { it.path("roomId").asText() }
        check(future.keys == setOf(id(201), id(202), id(204)))
        for (room in listOf(201, 204)) {
            val booking = future.getValue(id(room))
            check(booking.path("status").asText() == "CONFIRMED")
            check(booking.path("startDate").asText() == today.plusDays(7).toString())
            check(booking.path("endDate").asText() == today.plusDays(10).toString())
        }
        check(future.getValue(id(202)).path("status").asText() == "CANCELLED")
        check(future.getValue(id(202)).path("compensationStatus").asText() == "DONE")
        for ((room, count) in mapOf(201 to 2L, 202 to 0L, 203 to 0L, 204 to 1L)) {
            val result = request("GET", "/api/rooms/${id(room)}", user)
            check(result.path("timesBooked").asLong() == count) { "Unexpected counter for room $room" }
            check(result.path("available").asBoolean() == (room != 203))
        }
        val report = request("GET", "/api/hotels/${id(101)}/occupancy?from=2020-01-10&to=2020-01-13", admin)
        val historicalRoom = report.path("rooms").single { it.path("roomId").asText() == id(201) }
        check(historicalRoom.path("bookingCount").asLong() == 1L)
        check(historicalRoom.path("bookedNights").asLong() == 3L)
        check(historicalRoom.path("occupancyPercent").decimalValue().compareTo(java.math.BigDecimal("100")) == 0)
    }

    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
