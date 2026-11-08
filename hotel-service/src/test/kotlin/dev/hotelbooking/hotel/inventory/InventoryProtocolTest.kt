package dev.hotelbooking.hotel.inventory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.hotel.security.JwtTestServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "inventory.hold-ttl=10s"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(InventoryProtocolTest.TimeConfiguration::class)
class InventoryProtocolTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var clock: TestClock

    @BeforeEach fun resetTime() {
        clock.now.set(Instant.now())
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from reservations
            """.trimIndent(),
        )
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from rooms
            """.trimIndent(),
        )
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from hotels
            """.trimIndent(),
        )
    }

    @Test fun `hold is stable on replay and does not increment the room counter`() {
        val room = room()
        val command = command()
        val first = hold(room, command, 200)
        clock.now.updateAndGet { it.plusSeconds(3) }
        assertThat(hold(room, command, 200)).isEqualTo(first)
        assertThat(first.path("status").asText()).isEqualTo("HELD")
        assertThat(first.path("roomId").asText()).isEqualTo(room)
        assertThat(first.path("bookingId").asText()).isEqualTo(command["bookingId"])
        assertThat(first.path("attemptId").asText()).isEqualTo(command["attemptId"])
        assertThat(timesBooked(room)).isZero()
        assertThat(reservationCount(room)).isEqualTo(1)
    }

    @Test fun `reservation creation timestamp is persisted and survives replay commit and release`() {
        val roomId = room()
        val payload = command()
        val createdAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        clock.now.set(createdAt)
        hold(roomId, payload, 200)

        fun persistedCreationTime(): Instant =
            jdbc
                .queryForObject(
                    "select created_at from reservations where attempt_id = ?",
                    java.time.OffsetDateTime::class.java,
                    UUID.fromString(payload.getValue("attemptId")),
                )!!
                .toInstant()
        assertThat(persistedCreationTime()).isEqualTo(createdAt)
        clock.now.set(createdAt.plusSeconds(1))
        hold(roomId, payload, 200)
        commit(roomId, payload, 200)
        assertThat(persistedCreationTime()).isEqualTo(createdAt)
        clock.now.set(createdAt.plusSeconds(2))
        release(roomId, payload)
        assertThat(persistedCreationTime()).isEqualTo(createdAt)
    }

    @Test fun `overlap conflicts while checkout and checkin can share a day`() {
        val room = room()
        hold(room, command("2030-05-10", "2030-05-12"), 200)
        hold(room, command("2030-05-11", "2030-05-13"), 409)
        hold(room, command("2030-05-12", "2030-05-13"), 200)
    }

    @Test fun `expiry frees the period without cleanup and never revives the same attempt`() {
        val room = room()
        val old = command()
        val response = hold(room, old, 200)
        clock.now.set(Instant.parse(response.path("expiresAt").asText()))
        hold(room, old, 409)
        hold(room, command(), 200)
        assertThat(timesBooked(room)).isZero()
    }

    @Test fun `simultaneous first holds cannot overlap`() {
        val room = room()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<Int> {
                        barrier.await(5, TimeUnit.SECONDS)
                        send(room, command()).first
                    }
                }
            assertThat(futures.map { it.get(10, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 409)
            assertThat(reservationCount(room)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `attempt identifier cannot be rebound to different dates booking or room`() {
        val room = room()
        val command = command()
        val original = hold(room, command, 200)
        val changedCommands =
            listOf(
                command + ("bookingId" to UUID.randomUUID().toString()),
                command + ("endDate" to "2030-05-15"),
            )
        for (changed in changedCommands) {
            assertThat(hold(room, changed, 409).path("code").asText()).isEqualTo("ATTEMPT_CONFLICT")
        }
        assertThat(hold(room(), command, 409).path("code").asText()).isEqualTo("ATTEMPT_CONFLICT")
        assertThat(hold(room, command, 200)).isEqualTo(original)
    }

    @Test fun `empty and inverted intervals do not create reservations`() {
        val room = room()
        for (end in listOf("2030-05-10", "2030-05-09")) {
            assertThat(hold(room, command(end = end), 400).path("code").asText()).isEqualTo("INVALID_PERIOD")
        }
        assertThat(reservationCount(room)).isZero()
    }

    @Test fun `operational unavailability blocks new holds but preserves replay of a live hold`() {
        val room = room()
        val command = command()
        val first = hold(room, command, 200)
        val response =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/rooms/$room")
                        .header("Authorization", "Bearer ${keys.userToken("ADMIN")}")
                        .contentType("application/json")
                        .content("""{"number":"101","available":false}"""),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        assertThat(hold(room, command, 200)).isEqualTo(first)
        assertThat(hold(room, command("2030-06-10", "2030-06-12"), 409).path("code").asText()).isEqualTo("ROOM_UNAVAILABLE")
    }

    @Test fun `read only service scope cannot perform a hold`() {
        val room = room()
        val response =
            mvc
                .perform(
                    post("/internal/rooms/$room/hold")
                        .header("Authorization", "Bearer ${keys.serviceToken(scope = "inventory:read")}")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(command())),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(403)
        assertThat(reservationCount(room)).isZero()
    }

    @Test fun `identical simultaneous deliveries produce one reservation and one deadline`() {
        val room = room()
        val command = command()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<JsonNode> {
                        barrier.await(5, TimeUnit.SECONDS)
                        hold(room, command, 200)
                    }
                }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results[0]).isEqualTo(results[1])
            assertThat(reservationCount(room)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `USER and ADMIN cannot create inventory records`() {
        val room = room()
        for (role in listOf("USER", "ADMIN")) {
            val response =
                mvc
                    .perform(
                        post("/internal/rooms/$room/hold")
                            .header("Authorization", "Bearer ${keys.userToken(role)}")
                            .contentType("application/json")
                            .content(mapper.writeValueAsBytes(command())),
                    ).andReturn()
                    .response
            assertThat(response.status).isEqualTo(401)
        }
        assertThat(reservationCount(room)).isZero()
    }

    @Test
    fun `concurrent attempt collision across hotels preserves exactly the winning binding`() {
        val roomIds = listOf(room(), room())
        val payload = command()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                roomIds.map { roomId ->
                    pool.submit<Pair<Int, JsonNode>> {
                        barrier.await(5, TimeUnit.SECONDS)
                        send(roomId, payload)
                    }
                }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results.map { it.first }).containsExactlyInAnyOrder(200, 409)
            val winner = results.indexOfFirst { it.first == 200 }
            assertThat(results[1 - winner].second.path("code").asText()).isEqualTo("ATTEMPT_CONFLICT")
            assertThat(
                jdbc.queryForObject(
                    "select room_id from reservations where attempt_id = ?",
                    UUID::class.java,
                    UUID.fromString(payload.getValue("attemptId")),
                ),
            ).isEqualTo(UUID.fromString(roomIds[winner]))
            assertThat(roomIds.sumOf(::reservationCount)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `hold TTL starts after actual database lock wait`() {
        val roomId = room()
        val hotelId = jdbc.queryForObject("select hotel_id from rooms where id = ?", UUID::class.java, UUID.fromString(roomId))
        val pool = Executors.newSingleThreadExecutor()
        try {
            requireNotNull(jdbc.dataSource).connection.use { connection ->
                connection.autoCommit = false
                val blockerId =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select session_id()").use { result ->
                            result.next()
                            result.getInt(1)
                        }
                    }
                connection.prepareStatement("select id from hotels where id = ? for update").use { statement ->
                    statement.setObject(1, hotelId)
                    statement.executeQuery().use { result -> assertThat(result.next()).isTrue() }
                }
                val future = pool.submit<JsonNode> { hold(roomId, command(), 200) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

                fun blocked() =
                    jdbc.queryForObject(
                        "select count(*) from information_schema.sessions where blocker_id = ?",
                        Long::class.javaObjectType,
                        blockerId,
                    ) ?: 0L
                while (blocked() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
                assertThat(blocked()).describedAs("HTTP hold is waiting on the locked hotel row").isEqualTo(1)
                val afterWait = clock.now.updateAndGet { it.plusSeconds(5) }
                connection.commit()
                val result = future.get(10, TimeUnit.SECONDS)
                assertThat(Instant.parse(result.path("expiresAt").asText())).isEqualTo(
                    afterWait.plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `commit rejects a hold that expires during actual database lock wait`() {
        val roomId = room()
        val payload = command()
        val held = hold(roomId, payload, 200)
        val hotelId = jdbc.queryForObject("select hotel_id from rooms where id = ?", UUID::class.java, UUID.fromString(roomId))
        val pool = Executors.newSingleThreadExecutor()
        try {
            requireNotNull(jdbc.dataSource).connection.use { connection ->
                connection.autoCommit = false
                val blockerId =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select session_id()").use { result ->
                            result.next()
                            result.getInt(1)
                        }
                    }
                connection.prepareStatement("select id from hotels where id = ? for update").use { statement ->
                    statement.setObject(1, hotelId)
                    statement.executeQuery().use { result -> assertThat(result.next()).isTrue() }
                }
                val future = pool.submit<JsonNode> { commit(roomId, payload, 409) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

                fun blocked() =
                    jdbc.queryForObject(
                        "select count(*) from information_schema.sessions where blocker_id = ?",
                        Long::class.javaObjectType,
                        blockerId,
                    ) ?: 0L
                while (blocked() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
                assertThat(blocked()).describedAs("HTTP commit is waiting on the locked hotel row").isEqualTo(1)
                clock.now.set(Instant.parse(held.path("expiresAt").asText()))
                connection.commit()
                val result = future.get(10, TimeUnit.SECONDS)
                assertThat(result.path("code").asText()).isEqualTo("HOLD_EXPIRED")
                assertThat(timesBooked(roomId)).isZero()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `unknown room yields the authoritative room not found code`() {
        assertThat(hold(UUID.randomUUID().toString(), command(), 404).path("code").asText()).isEqualTo("ROOM_NOT_FOUND")
    }

    @Test
    fun `OpenAPI documents inventory commands with service credentials only`() {
        val response =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v3/api-docs"),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        val security =
            mapper
                .readTree(
                    response.contentAsByteArray,
                ).path("paths")
                .path("/internal/rooms/{id}/hold")
                .path("post")
                .path("security")
        assertThat(security.flatMap { it.fieldNames().asSequence().toList() }).containsExactly("serviceAuth")
    }

    @Test
    fun `commit and repeated hold return one reservation with one counter increment`() {
        val roomId = room()
        val payload = command()
        val held = hold(roomId, payload, 200)
        val reserved = commit(roomId, payload, 200)
        assertThat(reserved.path("status").asText()).isEqualTo("RESERVED")
        assertThat(reserved.path("expiresAt")).isEqualTo(held.path("expiresAt"))
        assertThat(commit(roomId, payload, 200)).isEqualTo(reserved)
        assertThat(hold(roomId, payload, 200)).isEqualTo(reserved)
        assertThat(timesBooked(roomId)).isEqualTo(1)
        val response =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/rooms/$roomId")
                        .header("Authorization", "Bearer ${keys.userToken()}"),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsByteArray).path("timesBooked").asLong()).isEqualTo(1)
    }

    @Test
    fun `concurrent commits increment the counter exactly once`() {
        val roomId = room()
        val payload = command()
        hold(roomId, payload, 200)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<JsonNode> {
                        barrier.await(5, TimeUnit.SECONDS)
                        commit(roomId, payload, 200)
                    }
                }
            val responses = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(responses[0]).isEqualTo(responses[1])
            assertThat(responses[0].path("status").asText()).isEqualTo("RESERVED")
            assertThat(timesBooked(roomId)).isEqualTo(1)
            assertThat(reservationCount(roomId)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `commit at the exact hold deadline is rejected without counter effect`() {
        val roomId = room()
        val payload = command()
        val held = hold(roomId, payload, 200)
        clock.now.set(Instant.parse(held.path("expiresAt").asText()))
        assertThat(commit(roomId, payload, 409).path("code").asText()).isEqualTo("HOLD_EXPIRED")
        assertThat(timesBooked(roomId)).isZero()
        jdbc.update("update reservations set status = 'EXPIRED' where attempt_id = ?", UUID.fromString(payload.getValue("attemptId")))
        assertThat(commit(roomId, payload, 409).path("code").asText()).isEqualTo("HOLD_EXPIRED")
        assertThat(timesBooked(roomId)).isZero()
        hold(roomId, command(), 200)
    }

    @Test
    fun `live hold commits after availability changes and reserved replay ignores old TTL`() {
        val roomId = room()
        val payload = command()
        val held = hold(roomId, payload, 200)
        availability(roomId, false)
        val reserved = commit(roomId, payload, 200)
        clock.now.set(Instant.parse(held.path("expiresAt").asText()).plusSeconds(5))
        assertThat(commit(roomId, payload, 200)).isEqualTo(reserved)
        assertThat(hold(roomId, payload, 200)).isEqualTo(reserved)
        assertThat(timesBooked(roomId)).isEqualTo(1)
        availability(roomId, true)
        assertThat(hold(roomId, command(), 409).path("code").asText()).isEqualTo("ROOM_UNAVAILABLE")
        hold(roomId, command("2030-05-12", "2030-05-14"), 200)
    }

    @Test
    fun `commit rejects an unknown attempt and incompatible binding`() {
        val roomId = room()
        val payload = command()
        assertThat(commit(roomId, payload, 404).path("code").asText()).isEqualTo("ATTEMPT_NOT_FOUND")
        hold(roomId, payload, 200)
        assertThat(commit(roomId, payload + ("bookingId" to UUID.randomUUID().toString()), 409).path("code").asText())
            .isEqualTo("ATTEMPT_CONFLICT")
        assertThat(timesBooked(roomId)).isZero()
        commit(roomId, payload, 200)
    }

    @Test
    fun `release before hold creates a terminal tombstone and repeat returns the same result`() {
        val roomId = room()
        val payload = command()
        val released = release(roomId, payload)
        assertThat(released.path("status").asText()).isEqualTo("RELEASED")
        assertThat(release(roomId, payload)).isEqualTo(released)
        assertThat(hold(roomId, payload, 409).path("code").asText()).isEqualTo("ATTEMPT_RELEASED")
        assertThat(commit(roomId, payload, 409).path("code").asText()).isEqualTo("ATTEMPT_RELEASED")
        assertThat(timesBooked(roomId)).isZero()
        hold(roomId, command(), 200)
    }

    @Test
    fun `release decrements only the targeted reserved attempt once`() {
        val roomId = room()
        val first = command()
        val second = command("2030-06-01", "2030-06-03")
        val heldOnly = command("2030-07-01", "2030-07-03")
        listOf(first, second).forEach {
            hold(roomId, it, 200)
            commit(roomId, it, 200)
        }
        hold(roomId, heldOnly, 200)
        assertThat(timesBooked(roomId)).isEqualTo(2)
        release(roomId, heldOnly)
        assertThat(timesBooked(roomId)).isEqualTo(2)
        val released = release(roomId, first)
        assertThat(release(roomId, first)).isEqualTo(released)
        assertThat(timesBooked(roomId)).isEqualTo(1)
        assertThat(commit(roomId, second, 200).path("status").asText()).isEqualTo("RESERVED")
        hold(roomId, command(), 200)
        assertThat(hold(roomId, command("2030-06-02", "2030-06-04"), 409).path("code").asText()).isEqualTo("ROOM_UNAVAILABLE")
    }

    @Test
    fun `release rejects incompatible binding and unknown room without modifying reservation`() {
        val roomId = room()
        val payload = command()
        hold(roomId, payload, 200)
        commit(roomId, payload, 200)
        val conflict = send(roomId, payload + ("startDate" to "2030-05-09"), "release")
        assertThat(conflict.first).isEqualTo(409)
        assertThat(conflict.second.path("code").asText()).isEqualTo("ATTEMPT_CONFLICT")
        assertThat(timesBooked(roomId)).isEqualTo(1)
        val missing = send(UUID.randomUUID().toString(), command(), "release")
        assertThat(missing.first).isEqualTo(404)
        assertThat(missing.second.path("code").asText()).isEqualTo("ROOM_NOT_FOUND")
        release(roomId, payload)
    }

    @Test
    fun `concurrent commit and release leave terminal state and zero counter`() {
        val roomId = room()
        val payload = command()
        hold(roomId, payload, 200)
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val commitFuture =
                pool.submit<Pair<Int, JsonNode>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    send(roomId, payload, "commit")
                }
            val releaseFuture =
                pool.submit<JsonNode> {
                    barrier.await(5, TimeUnit.SECONDS)
                    release(roomId, payload)
                }
            val committed = commitFuture.get(10, TimeUnit.SECONDS)
            assertThat(committed.first).isIn(200, 409)
            if (committed.first == 409) assertThat(committed.second.path("code").asText()).isEqualTo("ATTEMPT_RELEASED")
            assertThat(releaseFuture.get(10, TimeUnit.SECONDS).path("status").asText()).isEqualTo("RELEASED")
            assertThat(timesBooked(roomId)).isZero()
            assertThat(commit(roomId, payload, 409).path("code").asText()).isEqualTo("ATTEMPT_RELEASED")
            hold(roomId, command(), 200)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed release flush rolls back both reservation and counter`() {
        val roomId = room()
        val payload = command()
        hold(roomId, payload, 200)
        commit(roomId, payload, 200)
        val attempt = UUID.fromString(payload.getValue("attemptId"))
        jdbc.execute("alter table reservations add constraint reject_test_release check (attempt_id <> '$attempt' or status <> 'RELEASED')")
        try {
            assertThatThrownBy { send(roomId, payload, "release") }
                .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
                .hasStackTraceContaining("REJECT_TEST_RELEASE")
            assertThat(timesBooked(roomId)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select status from reservations where attempt_id = ?", String::class.java, attempt))
                .isEqualTo("RESERVED")
        } finally {
            // This constraint is created dynamically earlier in this test.
            jdbc.execute(
                """
                -- noinspection SqlResolve
                alter table reservations drop constraint reject_test_release
                """.trimIndent(),
            )
        }
        release(roomId, payload)
        assertThat(timesBooked(roomId)).isZero()
    }

    @Test
    fun `failed commit flush rolls back both reservation and counter`() {
        val roomId = room()
        val payload = command()
        hold(roomId, payload, 200)
        val attempt = UUID.fromString(payload.getValue("attemptId"))
        jdbc.execute("alter table reservations add constraint reject_test_commit check (attempt_id <> '$attempt' or status <> 'RESERVED')")
        try {
            assertThatThrownBy { send(roomId, payload, "commit") }
                .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
                .hasStackTraceContaining("REJECT_TEST_COMMIT")
            assertThat(timesBooked(roomId)).isZero()
            assertThat(jdbc.queryForObject("select status from reservations where attempt_id = ?", String::class.java, attempt))
                .isEqualTo("HELD")
        } finally {
            // This constraint is created dynamically earlier in this test.
            jdbc.execute(
                """
                -- noinspection SqlResolve
                alter table reservations drop constraint reject_test_commit
                """.trimIndent(),
            )
        }
        commit(roomId, payload, 200)
        assertThat(timesBooked(roomId)).isEqualTo(1)
    }

    @Test
    fun `public and service search share exact candidates and recommended counter order`() {
        val first = room()
        val second = room()
        val third = room()
        listOf(first, third, third).forEachIndexed { index, id ->
            val old = command("2029-01-${10 + index}", "2029-01-${11 + index}")
            hold(id, old, 200)
            commit(id, old, 200)
        }
        val expected = listOf(second, first, third)
        assertThat(search("/api/rooms/recommend").map { it.path("id").asText() }).containsExactlyElementsOf(expected)
        assertThat(search("/api/rooms").map { it.path("id").asText() }).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(search("/internal/rooms", token = keys.serviceToken()).map { it.path("id").asText() })
            .containsExactlyElementsOf(expected)
        val hotelId = jdbc.queryForObject("select hotel_id from rooms where id = ?", UUID::class.java, UUID.fromString(first)).toString()
        assertThat(search("/api/rooms/recommend", hotelId).map { it.path("id").asText() }).containsExactly(first)
        val zero = room()
        assertThat(search("/api/rooms/recommend").map { it.path("id").asText() })
            .containsExactlyElementsOf(listOf(second, zero).sorted() + listOf(first, third))
    }

    @Test
    fun `search calendar excludes active holds reservations unavailable and deleted objects`() {
        val held = room()
        val reserved = room()
        val expired = room()
        val released = room()
        val unavailable = room()
        val deletedRoom = room()
        val deletedHotel = room()
        val adjacent = room()
        val expiredPayload = command()
        val expiry = hold(expired, expiredPayload, 200).path("expiresAt").asText()
        clock.now.set(Instant.parse(expiry))
        hold(held, command(), 200)
        val reservedPayload = command()
        hold(reserved, reservedPayload, 200)
        commit(reserved, reservedPayload, 200)
        val releasedPayload = command()
        hold(released, releasedPayload, 200)
        release(released, releasedPayload)
        val adjacentPayload = command("2030-05-08", "2030-05-10")
        hold(adjacent, adjacentPayload, 200)
        commit(adjacent, adjacentPayload, 200)
        availability(unavailable, false)
        jdbc.update("update rooms set deleted = true where id = ?", UUID.fromString(deletedRoom))
        jdbc.update("update hotels set deleted = true where id = (select hotel_id from rooms where id = ?)", UUID.fromString(deletedHotel))
        assertThat(search("/api/rooms").map { it.path("id").asText() }).containsExactlyInAnyOrder(expired, released, adjacent)
        jdbc.update(
            "update reservations set status = 'EXPIRED' where attempt_id = ?",
            UUID.fromString(expiredPayload.getValue("attemptId")),
        )
        assertThat(search("/internal/rooms", token = keys.serviceToken()).map { it.path("id").asText() })
            .containsExactlyInAnyOrder(expired, released, adjacent)
    }

    @Test
    fun `search validates dates hotel and audience`() {
        assertThat(search("/api/rooms", UUID.randomUUID().toString(), expected = 404).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND")
        search("/api/rooms", start = "2030-05-12", expected = 400)
        search("/internal/rooms", expected = 401)
        search("/internal/rooms", token = keys.serviceToken(scope = "inventory:write"), expected = 403)
        assertThat(search("/api/rooms", token = keys.userToken("ADMIN")).isArray).isTrue()
        assertThat(search("/api/rooms", start = "2020-01-01").isArray).isTrue()
        search("/api/rooms", token = keys.serviceToken(), expected = 401)
        val missing =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/rooms")
                        .header("Authorization", "Bearer ${keys.userToken()}"),
                ).andReturn()
                .response
        assertThat(missing.status).isEqualTo(400)
    }

    @Test
    fun `deletion rejects live hold and current or future reservation then allows release`() {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        for (reserved in listOf(false, true)) {
            val roomId = room()
            val payload = command(today.toString(), today.plusDays(2).toString())
            hold(roomId, payload, 200)
            if (reserved) commit(roomId, payload, 200)
            assertThat(delete("/api/rooms/$roomId")).isEqualTo(409)
            assertThat(
                jdbc.queryForObject("select deleted from rooms where id = ?", Boolean::class.java, UUID.fromString(roomId)),
            ).isFalse()
            release(roomId, payload)
            assertThat(delete("/api/rooms/$roomId")).isEqualTo(204)
        }
    }

    @Test
    fun `deletion preserves past reservation counter and immutable room label`() {
        val roomId = room()
        val payload = command("2020-01-01", "2020-01-03")
        hold(roomId, payload, 200)
        commit(roomId, payload, 200)
        assertThat(delete("/api/rooms/$roomId", "USER")).isEqualTo(403)
        assertThat(delete("/api/rooms/$roomId")).isEqualTo(204)
        assertThat(timesBooked(roomId)).isEqualTo(1)
        assertThat(reservationCount(roomId)).isEqualTo(1)
        assertThat(hold(roomId, command(), 409).path("code").asText()).isEqualTo("ROOM_UNAVAILABLE")
        val response =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/rooms/$roomId")
                        .header("Authorization", "Bearer ${keys.userToken()}"),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(404)
        val hotelId = jdbc.queryForObject("select hotel_id from rooms where id = ?", UUID::class.java, UUID.fromString(roomId)).toString()
        val duplicate =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Authorization", "Bearer ${keys.userToken("ADMIN")}")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(mapOf("hotelId" to hotelId, "number" to "101"))),
                ).andReturn()
                .response
        assertThat(duplicate.status).isEqualTo(409)
    }

    @Test
    fun `hotel deletion is all or nothing and keeps historical rows`() {
        val occupied = room()
        val hotelId = jdbc.queryForObject("select hotel_id from rooms where id = ?", UUID::class.java, UUID.fromString(occupied)).toString()
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Authorization", "Bearer ${keys.userToken("ADMIN")}")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(mapOf("hotelId" to hotelId, "number" to "102"))),
                ).andReturn()
                .response
        assertThat(created.status).isEqualTo(201)
        val free = mapper.readTree(created.contentAsByteArray).path("id").asText()
        val payload = command()
        hold(occupied, payload, 200)
        commit(occupied, payload, 200)
        assertThat(delete("/api/hotels/$hotelId")).isEqualTo(409)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where hotel_id = ? and deleted = false",
                Long::class.java,
                UUID.fromString(hotelId),
            ),
        ).isEqualTo(2)
        assertThat(jdbc.queryForObject("select deleted from hotels where id = ?", Boolean::class.java, UUID.fromString(hotelId))).isFalse()
        release(occupied, payload)
        assertThat(delete("/api/hotels/$hotelId")).isEqualTo(204)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where hotel_id = ? and deleted = true",
                Long::class.java,
                UUID.fromString(hotelId),
            ),
        ).isEqualTo(2)
        assertThat(hold(free, command(), 409).path("code").asText()).isEqualTo("ROOM_UNAVAILABLE")
        assertThat(reservationCount(occupied)).isEqualTo(1)
        assertThat(search("/api/rooms").size()).isZero()
    }

    @Test
    fun `concurrent deletion and hold cannot both succeed`() {
        val roomId = room()
        val payload = command()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val held =
                pool.submit<Pair<Int, JsonNode>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    send(roomId, payload)
                }
            val deleted =
                pool.submit<Int> {
                    barrier.await(5, TimeUnit.SECONDS)
                    delete("/api/rooms/$roomId")
                }
            val holdStatus = held.get(10, TimeUnit.SECONDS).first
            val deleteStatus = deleted.get(10, TimeUnit.SECONDS)
            assertThat(listOf(holdStatus, deleteStatus)).isIn(listOf(200, 409), listOf(409, 204))
            assertThat(jdbc.queryForObject("select deleted from rooms where id = ?", Boolean::class.java, UUID.fromString(roomId)))
                .isEqualTo(deleteStatus == 204)
            assertThat(reservationCount(roomId)).isEqualTo(if (holdStatus == 200) 1L else 0L)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `deletion allows exact expired hold and checkout today without changing history`() {
        val expiredRoom = room()
        val held = hold(expiredRoom, command(), 200)
        clock.now.set(Instant.parse(held.path("expiresAt").asText()))
        assertThat(delete("/api/rooms/$expiredRoom")).isEqualTo(204)
        val checkedOut = room()
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val past = command(today.minusDays(2).toString(), today.toString())
        hold(checkedOut, past, 200)
        commit(checkedOut, past, 200)
        assertThat(delete("/api/rooms/$checkedOut")).isEqualTo(204)
        assertThat(timesBooked(checkedOut)).isEqualTo(1)
        assertThat(reservationCount(checkedOut)).isEqualTo(1)
    }

    @Test
    fun `hotel deletion serializes with room creation and leaves no active orphan`() {
        val existing = room()
        val hotelId = jdbc.queryForObject("select hotel_id from rooms where id = ?", UUID::class.java, UUID.fromString(existing)).toString()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val creating =
                pool.submit<Int> {
                    barrier.await(5, TimeUnit.SECONDS)
                    mvc
                        .perform(
                            post("/api/rooms")
                                .header("Authorization", "Bearer ${keys.userToken("ADMIN")}")
                                .contentType("application/json")
                                .content(mapper.writeValueAsBytes(mapOf("hotelId" to hotelId, "number" to "new"))),
                        ).andReturn()
                        .response.status
                }
            val deleting =
                pool.submit<Int> {
                    barrier.await(5, TimeUnit.SECONDS)
                    delete("/api/hotels/$hotelId")
                }
            val created = creating.get(10, TimeUnit.SECONDS)
            assertThat(created).isIn(201, 404)
            assertThat(deleting.get(10, TimeUnit.SECONDS)).isEqualTo(204)
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from rooms where hotel_id = ? and deleted = false",
                    Long::class.java,
                    UUID.fromString(hotelId),
                ),
            ).isZero()
            assertThat(jdbc.queryForObject("select count(*) from rooms where hotel_id = ?", Long::class.java, UUID.fromString(hotelId)))
                .isEqualTo(if (created == 201) 2L else 1L)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun delete(
        path: String,
        role: String = "ADMIN",
    ): Int =
        mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete(path)
                    .header("Authorization", "Bearer ${keys.userToken(role)}"),
            ).andReturn()
            .response.status

    @Test
    fun `service room lookup supports preparation without a user token or availability filtering`() {
        val roomId = room()
        availability(roomId, false)

        fun read(token: String) =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/rooms/$roomId")
                        .header("Authorization", "Bearer $token"),
                ).andReturn()
                .response
        val response = read(keys.serviceToken(scope = "inventory:read"))
        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsByteArray).path("id").asText()).isEqualTo(roomId)
        assertThat(read(keys.userToken("ADMIN")).status).isEqualTo(401)
        assertThat(read(keys.serviceToken(scope = "inventory:write")).status).isEqualTo(403)
        assertThat(delete("/api/rooms/$roomId")).isEqualTo(204)
        assertThat(read(keys.serviceToken()).status).isEqualTo(404)
    }

    private fun search(
        path: String,
        hotelId: String? = null,
        token: String = keys.userToken(),
        start: String = "2030-05-10",
        expected: Int = 200,
    ): JsonNode {
        val request =
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path)
                .header("Authorization", "Bearer $token")
                .param("startDate", start)
                .param("endDate", "2030-05-12")
        if (hotelId != null) request.param("hotelId", hotelId)
        val response = mvc.perform(request).andReturn().response
        assertThat(response.status).isEqualTo(expected)
        return mapper.readTree(response.contentAsByteArray)
    }

    private fun release(
        roomId: String,
        payload: Map<String, String>,
    ): JsonNode {
        val (status, body) = send(roomId, payload, "release")
        assertThat(status).isEqualTo(200)
        return body
    }

    private fun availability(
        roomId: String,
        available: Boolean,
    ) {
        val response =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/rooms/$roomId")
                        .header("Authorization", "Bearer ${keys.userToken("ADMIN")}")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(mapOf("number" to "101", "available" to available))),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
    }

    private fun commit(
        roomId: String,
        payload: Map<String, String>,
        expected: Int,
    ): JsonNode {
        val (status, body) = send(roomId, payload, "commit")
        assertThat(status).isEqualTo(expected)
        return body
    }

    private fun reservationCount(roomId: String): Long =
        requireNotNull(
            jdbc.queryForObject(
                "select count(*) from reservations where room_id = ?",
                Long::class.javaObjectType,
                UUID.fromString(roomId),
            ),
        )

    private fun timesBooked(roomId: String): Long =
        requireNotNull(
            jdbc.queryForObject(
                "select times_booked from rooms where id = ?",
                Long::class.javaObjectType,
                UUID.fromString(roomId),
            ),
        )

    private fun room(): String {
        fun create(
            path: String,
            body: Any,
        ): JsonNode {
            val response =
                mvc
                    .perform(
                        post(path)
                            .header("Authorization", "Bearer ${keys.userToken("ADMIN")}")
                            .contentType("application/json")
                            .content(mapper.writeValueAsBytes(body)),
                    ).andReturn()
                    .response
            assertThat(response.status).isEqualTo(201)
            return mapper.readTree(response.contentAsByteArray)
        }
        val hotel = create("/api/hotels", mapOf("name" to "Hold hotel", "address" to "Address"))
        return create("/api/rooms", mapOf("hotelId" to hotel.path("id").asText(), "number" to "101")).path("id").asText()
    }

    private fun command(
        start: String = "2030-05-10",
        end: String = "2030-05-12",
    ) = mapOf(
        "attemptId" to UUID.randomUUID().toString(),
        "bookingId" to UUID.randomUUID().toString(),
        "startDate" to start,
        "endDate" to end,
    )

    private fun send(
        room: String,
        command: Map<String, String>,
        action: String = "hold",
    ): Pair<Int, JsonNode> {
        val response =
            mvc
                .perform(
                    post("/internal/rooms/$room/$action")
                        .header("Authorization", "Bearer ${keys.serviceToken()}")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(command)),
                ).andReturn()
                .response
        return response.status to
            if (response.contentAsByteArray.isEmpty()) mapper.createObjectNode() else mapper.readTree(response.contentAsByteArray)
    }

    private fun hold(
        room: String,
        command: Map<String, String>,
        expected: Int,
    ): JsonNode {
        val (status, body) = send(room, command)
        assertThat(status).isEqualTo(expected)
        return body
    }

    class TestClock : Clock() {
        val now = AtomicReference(Instant.now())

        override fun instant(): Instant = now.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
    }

    @TestConfiguration class TimeConfiguration {
        @Bean @Primary
        fun testClock() = TestClock()
    }

    companion object {
        private val keys = JwtTestServer()

        // Invoked reflectively by the Spring test context.
        @Suppress("unused")
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.security.jwk-set-uri") { keys.uri }
        }

        @JvmStatic @AfterAll
        fun stop() {
            keys.close()
        }
    }
}
