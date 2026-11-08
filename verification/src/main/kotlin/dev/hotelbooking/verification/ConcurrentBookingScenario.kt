package dev.hotelbooking.verification

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun verifyConcurrentBookings(
    api: SystemApi,
    admin: String,
    user: String,
) {
    val hotelId =
        api
            .call(
                "POST",
                "/api/hotels",
                admin,
                mapOf("name" to "Concurrency", "address" to "Acceptance"),
            ).json
            .path("id")
            .asText()
    val rooms =
        (1..3)
            .map { number ->
                api
                    .call("POST", "/api/rooms", admin, mapOf("hotelId" to hotelId, "number" to "$number"))
                    .json
                    .path("id")
                    .asText()
            }.sorted()
    val start = LocalDate.now(ZoneOffset.UTC).plusDays(40)
    val end = start.plusDays(2)
    val query = "startDate=$start&endDate=$end&hotelId=$hotelId"

    fun request(
        roomId: String? = null,
        date: LocalDate = start,
    ) = mapOf(
        "autoSelect" to (roomId == null),
        "hotelId" to hotelId,
        "roomId" to roomId,
        "startDate" to "$date",
        "endDate" to "${date.plusDays(2)}",
    )

    fun counter(roomId: String) =
        api
            .call("GET", "/api/rooms/$roomId", user)
            .json
            .path("timesBooked")
            .asInt()

    fun cancel(bookingId: String) {
        val result = api.call("DELETE", "/api/bookings/$bookingId", user).json
        check(result.path("status").asText() == "CANCELLED" && result.path("compensationStatus").asText() == "DONE")
    }

    // A disjoint booking changes the cumulative score without occupying the requested dates.
    val scored = api.call("POST", "/api/bookings", user, request(rooms[0], start.plusDays(10)), key = "score-seed").json
    val recommended = api.call("GET", "/api/bookings/recommendations?$query", user).json.map { it.path("id").asText() }
    check(recommended == listOf(rooms[1], rooms[2], rooms[0])) { "Recommendation ignored counter/id order" }
    val preferred = api.call("POST", "/api/bookings", user, request(), key = "score-pick").json
    check(preferred.path("roomId").asText() == rooms[1]) { "Automatic choice ignored recommendation order" }
    cancel(preferred.path("id").asText())
    cancel(scored.path("id").asText())
    check(rooms.map(::counter) == listOf(0, 0, 0))

    val outcomes =
        simultaneous(8) { index ->
            api.call("POST", "/api/bookings", user, request(), key = "parallel-$index", allowedStatuses = setOf(201, 409))
        }
    val confirmed = outcomes.filter { it.status == 201 }.map { it.json }
    check(confirmed.size == 3) { "Expected exactly three winners, got ${confirmed.size}" }
    check(confirmed.map { it.path("roomId").asText() }.toSet() == rooms.toSet())
    check(confirmed.map { it.path("id").asText() }.toSet().size == 3)
    check(confirmed.map { it.path("attemptId").asText() }.toSet().size == 3)
    check(
        confirmed.all {
            it.path("status").asText() == "CONFIRMED" && it.path("startDate").asText() == "$start" &&
                it.path("endDate").asText() == "$end"
        },
    )
    check(outcomes.filter { it.status == 409 }.all { it.json.path("status").asText() == "CANCELLED" })
    check(rooms.map(::counter) == listOf(1, 1, 1))
    check(api.call("GET", "/api/rooms?$query", user).json.isEmpty)
    val report = api.call("GET", "/api/hotels/$hotelId/occupancy?from=$start&to=$end", admin).json.path("rooms")
    check(report.map { it.path("roomId").asText() }.toSet() == rooms.toSet())
    check(report.all { it.path("bookingCount").asInt() == 1 && it.path("bookedNights").asInt() == 2 })
    simultaneous(confirmed.size) { index ->
        val id = confirmed[index].path("id").asText()
        cancel(id)
        cancel(id)
    }
    check(rooms.map(::counter) == listOf(0, 0, 0)) { "Repeated cancellation changed counters more than once" }
    check(
        api
            .call("GET", "/api/rooms?$query", user)
            .json
            .map { it.path("id").asText() }
            .toSet() == rooms.toSet(),
    )

    val duplicates =
        simultaneous(6) {
            api.call("POST", "/api/bookings", user, request(rooms[0]), key = "same-operation")
        }
    val ids = duplicates.map { it.json.path("id").asText() }.toSet()
    check(ids.size == 1 && duplicates.map { it.json.path("attemptId").asText() }.toSet().size == 1)
    val bookingId = ids.single()
    awaitCondition("idempotent booking completion") {
        api
            .call("GET", "/api/bookings/$bookingId", user)
            .json
            .path("status")
            .asText() == "CONFIRMED"
    }
    check(rooms.map(::counter) == listOf(1, 0, 0))
    val history = api.call("GET", "/api/bookings?size=100", user).json.path("items")
    check(history.count { it.path("id").asText() == bookingId } == 1)
    cancel(bookingId)
    check(rooms.map(::counter) == listOf(0, 0, 0))
    println("PASS concurrency: 8 requests/3 rooms => 3 distinct winners; 6 duplicate requests => 1 booking; final counters 0/0/0")
}

private fun <T> simultaneous(
    count: Int,
    action: (Int) -> T,
): List<T> {
    val ready = CountDownLatch(count)
    val start = CountDownLatch(1)
    return Executors.newFixedThreadPool(count).use { executor ->
        val futures =
            (0 until count).map { index ->
                executor.submit<T> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    action(index)
                }
            }
        check(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        futures.map { it.get(45, TimeUnit.SECONDS) }
    }
}
