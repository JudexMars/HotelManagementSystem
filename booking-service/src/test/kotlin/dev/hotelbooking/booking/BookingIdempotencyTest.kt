package dev.hotelbooking.booking
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.booking.application.port.HotelInventory
import dev.hotelbooking.booking.application.port.InventoryResult
import dev.hotelbooking.booking.application.port.InventoryRoom
import dev.hotelbooking.booking.application.port.InventoryStatus
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "booking.recovery.enabled=false"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(BookingIdempotencyTest.Configuration::class)
class BookingIdempotencyTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var inventory: CountingInventory

    @Autowired lateinit var clock: MutableClock
    private lateinit var username: String
    private lateinit var token: String
    private lateinit var roomId: UUID
    private lateinit var start: LocalDate

    @BeforeEach fun setup() {
        clock.now = Instant.now()
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from bookings
            """.trimIndent(),
        )
        inventory.failCommit = false
        inventory.catalogMissing = false
        inventory.commits.set(0)
        inventory.calls.set(0)
        inventory.entered = null
        inventory.proceed = null
        token = register()
        roomId = UUID.randomUUID()
        start = LocalDate.now(ZoneOffset.UTC).plusDays(1)
    }

    @Test fun `replay returns original booking without another Hotel call`() {
        val key = UUID.randomUUID().toString()
        val first = create(key)
        assertThat(first.status).isEqualTo(201)
        val calls = inventory.calls.get()
        val replay = create(key, hotel = UUID.randomUUID())
        assertThat(replay.status).isEqualTo(200)
        assertThat(body(replay)).isEqualTo(body(first))
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"))
        assertThat(inventory.calls.get()).isEqualTo(calls)
        assertThat(inventory.commits.get()).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from bookings", Long::class.java)).isEqualTo(1)
    }

    @Test fun `same key with different meaningful parameters conflicts`() {
        val key = UUID.randomUUID().toString()
        assertThat(create(key).status).isEqualTo(201)
        val conflict = create(key, room = UUID.randomUUID())
        assertThat(conflict.status).isEqualTo(409)
        assertThat(body(conflict).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT")
        assertThat(inventory.commits.get()).isEqualTo(1)
    }

    @Test fun `replay precedes current UTC date and mutable catalog validation`() {
        val key = UUID.randomUUID().toString()
        val first = create(key)
        assertThat(first.status).isEqualTo(201)
        val calls = inventory.calls.get()
        clock.now = clock.now.plusSeconds(3 * 86400)
        val login =
            mvc
                .perform(
                    post("/api/users/auth").contentType("application/json").content(
                        mapper.writeValueAsBytes(mapOf("username" to username, "password" to "test-password")),
                    ),
                ).andReturn()
                .response
        assertThat(login.status).isEqualTo(200)
        token = body(login).path("accessToken").asText()
        inventory.catalogMissing = true
        val replay = create(key)
        assertThat(replay.status).isEqualTo(200)
        assertThat(body(replay)).isEqualTo(body(first))
        assertThat(inventory.calls.get()).isEqualTo(calls)
        assertThat(create(UUID.randomUUID().toString()).status).isEqualTo(400)
        assertThat(inventory.calls.get()).isEqualTo(calls)
    }

    @Test fun `cancelled creation replay stays cancelled without another attempt`() {
        val key = UUID.randomUUID().toString()
        inventory.failCommit = true
        val failure = create(key)
        assertThat(failure.status).isEqualTo(409)
        val calls = inventory.calls.get()
        inventory.failCommit = false
        val replay = create(key)
        assertThat(replay.status).isEqualTo(200)
        assertThat(body(replay).path("id")).isEqualTo(body(failure).path("bookingId"))
        assertThat(body(replay).path("status").asText()).isEqualTo("CANCELLED")
        assertThat(body(replay).path("compensationStatus").asText()).isEqualTo("DONE")
        assertThat(inventory.calls.get()).isEqualTo(calls)
        assertThat(jdbc.queryForObject("select count(*) from bookings", Long::class.java)).isEqualTo(1)
    }

    @Test fun `expired pending replay resolves deadline instead of returning stale acceptance`() {
        val key = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        inventory.entered = entered
        inventory.proceed = proceed
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<MockHttpServletResponse> { create(key) }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            clock.now = clock.now.plusSeconds(25)
            val replay = create(key)
            assertThat(replay.status).isEqualTo(200)
            assertThat(body(replay).path("status").asText()).isEqualTo("CANCELLED")
            assertThat(body(replay).path("compensationStatus").asText()).isEqualTo("DONE")
            proceed.countDown()
            assertThat(first.get(5, TimeUnit.SECONDS).status).isEqualTo(504)
            assertThat(inventory.commits.get()).isZero()
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `same key belongs independently to each user`() {
        val key = UUID.randomUUID().toString()
        val first = create(key)
        token = register()
        val second = create(key, room = UUID.randomUUID())
        assertThat(second.status).isEqualTo(201)
        assertThat(body(second).path("id")).isNotEqualTo(body(first).path("id"))
        assertThat(inventory.commits.get()).isEqualTo(2)
    }

    @Test fun `concurrent first requests share the pending operation`() {
        val key = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        inventory.entered = entered
        inventory.proceed = proceed
        val pool = Executors.newFixedThreadPool(6)
        val startGate = CountDownLatch(1)
        try {
            val futures =
                (1..6).map {
                    pool.submit<MockHttpServletResponse> {
                        startGate.await()
                        create(key)
                    }
                }
            startGate.countDown()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (futures.count { it.isDone } < 5 && System.nanoTime() < limit) Thread.sleep(10)
            assertThat(futures.count { it.isDone }).isEqualTo(5)
            val pending = futures.filter { it.isDone }.map { it.get() }
            assertThat(pending.map { it.status }).containsOnly(202)
            assertThat(pending.map { body(it).path("id").asText() }.distinct()).hasSize(1)
            assertThat(pending.map { body(it).path("status").asText() }).containsOnly("PENDING")
            proceed.countDown()
            val responses = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertThat(responses.count { it.status == 201 }).isEqualTo(1)
            assertThat(responses.map { body(it).path("id").asText() }.distinct()).hasSize(1)
            assertThat(inventory.commits.get()).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from bookings", Long::class.java)).isEqualTo(1)
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `missing or invalid keys do not create a booking`() {
        for (key in listOf(null, "", " ", "a".repeat(129))) {
            assertThat(create(key).status).isEqualTo(400)
        }
        assertThat(jdbc.queryForObject("select count(*) from bookings", Long::class.java)).isZero()
        assertThat(inventory.calls.get()).isZero()
    }

    private fun register(): String {
        username = UUID.randomUUID().toString()
        val result =
            mvc
                .perform(
                    post("/api/users/register").contentType("application/json").content(
                        mapper.writeValueAsBytes(mapOf("username" to username, "password" to "test-password")),
                    ),
                ).andReturn()
                .response
        assertThat(result.status).isEqualTo(201)
        return body(result).path("accessToken").asText()
    }

    private fun create(
        key: String?,
        room: UUID = roomId,
        hotel: UUID? = null,
    ): MockHttpServletResponse {
        val request =
            post("/api/bookings")
                .header("Authorization", "Bearer $token")
                .contentType("application/json")
                .content(
                    mapper.writeValueAsBytes(
                        mapOf(
                            "autoSelect" to false,
                            "roomId" to room,
                            "hotelId" to hotel,
                            "startDate" to start,
                            "endDate" to start.plusDays(1),
                        ),
                    ),
                )
        if (key != null) request.header("Idempotency-Key", key)
        return mvc.perform(request).andReturn().response
    }

    private fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    class CountingInventory : HotelInventory {
        @Volatile var failCommit = false

        @Volatile var catalogMissing = false
        val commits = AtomicInteger()
        val calls = AtomicInteger()

        @Volatile var entered: CountDownLatch? = null

        @Volatile var proceed: CountDownLatch? = null

        override fun room(
            id: UUID,
            traceId: String?,
        ): InventoryRoom {
            calls.incrementAndGet()
            if (catalogMissing) throw BookingException(BookingError.ROOM_NOT_FOUND)
            entered?.countDown()
            check(proceed?.await(8, TimeUnit.SECONDS) != false)
            return InventoryRoom(id, UUID.randomUUID())
        }

        override fun hold(booking: Booking): InventoryResult {
            calls.incrementAndGet()
            return result(booking, "HELD")
        }

        override fun commit(booking: Booking): InventoryResult {
            calls.incrementAndGet()
            if (failCommit) throw BookingException(BookingError.HOLD_EXPIRED)
            commits.incrementAndGet()
            return result(booking, "RESERVED")
        }

        override fun release(booking: Booking): InventoryResult {
            calls.incrementAndGet()
            return result(booking, "RELEASED")
        }

        private fun result(
            b: Booking,
            status: String,
        ) = InventoryResult(b.attemptId, b.id, requireNotNull(b.roomId), b.startDate, b.endDate, InventoryStatus.valueOf(status))
    }

    class MutableClock : Clock() {
        @Volatile var now: Instant = Instant.now()

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration class Configuration {
        @Bean @Primary
        fun idempotencyClock() = MutableClock()

        @Bean @Primary
        fun inventory() = CountingInventory()
    }
}
