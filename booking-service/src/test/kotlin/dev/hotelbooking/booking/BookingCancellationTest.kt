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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "booking.recovery.enabled=false",
        "booking.saga.initial-backoff=1ms",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(BookingCancellationTest.Configuration::class)
class BookingCancellationTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var clock: MutableClock

    @Autowired lateinit var inventory: Inventory
    private lateinit var owner: User
    private lateinit var admin: User

    @BeforeEach fun setup() {
        clock.now = Instant.now()
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from bookings
            """.trimIndent(),
        )
        inventory.states.clear()
        inventory.counter.set(0)
        inventory.releases.set(0)
        inventory.releaseBlocked = false
        inventory.commitEntered = null
        inventory.commitProceed = null
        owner = user()
        admin = user(admin = true)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `owner and admin can cancel confirmed booking before arrival`(asAdmin: Boolean) {
        val created = create()
        assertThat(body(created).path("compensationStatus").isNull).isTrue()
        val id = body(created).path("id").asText()
        assertThat(inventory.counter.get()).isEqualTo(1)
        val cancelled = cancel(id, if (asAdmin) admin.token else owner.token)
        assertThat(cancelled.status).isEqualTo(200)
        assertThat(body(cancelled).path("status").asText()).isEqualTo("CANCELLED")
        assertThat(body(cancelled).path("compensationStatus").asText()).isEqualTo("DONE")
        assertThat(inventory.counter.get()).isZero()
        assertThat(cancelled.getHeader("Location")).isEqualTo("/api/bookings/$id")
        val repeated = cancel(id, owner.token)
        assertThat(repeated.status).isEqualTo(200)
        assertThat(inventory.releases.get()).isEqualTo(1)
    }

    @Test fun `foreign user sees not found without changing the reservation`() {
        val id = body(create()).path("id").asText()
        val foreign = user()
        assertThat(cancel(id, foreign.token).status).isEqualTo(404)
        assertThat(inventory.counter.get()).isEqualTo(1)
        assertThat(inventory.releases.get()).isZero()
        assertThat(
            jdbc.queryForObject("select status from bookings where id = ?", String::class.java, UUID.fromString(id)),
        ).isEqualTo("CONFIRMED")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `arrival today forbids new cancellation for owner and admin`(asAdmin: Boolean) {
        val id = body(create(today())).path("id").asText()
        val rejected = cancel(id, if (asAdmin) admin.token else owner.token)
        assertThat(rejected.status).isEqualTo(409)
        assertThat(body(rejected).path("code").asText()).isEqualTo("CANCELLATION_TOO_LATE")
        assertThat(inventory.counter.get()).isEqualTo(1)
        assertThat(inventory.releases.get()).isZero()
    }

    @Test fun `UTC arrival boundary is checked after waiting for the booking lock`() {
        clock.now = today().atTime(23, 59, 59).toInstant(ZoneOffset.UTC)
        owner = owner.copy(token = login(owner.username))
        val id = UUID.fromString(body(create()).path("id").asText())
        val pool = Executors.newSingleThreadExecutor()
        try {
            requireNotNull(jdbc.dataSource).connection.use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("select id from bookings where id = ? for update").use {
                    it.setObject(1, id)
                    it.executeQuery().close()
                }
                val blockingSession =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select session_id()").use {
                            it.next()
                            it.getLong(1)
                        }
                    }
                val cancelling = pool.submit<MockHttpServletResponse> { cancel(id.toString(), owner.token) }

                fun blocked() =
                    jdbc.queryForObject(
                        "select count(*) from information_schema.sessions where blocker_id = ?",
                        Long::class.java,
                        blockingSession,
                    ) ?: 0
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (blocked() == 0L && System.nanoTime() < limit) Thread.sleep(10)
                assertThat(blocked()).isEqualTo(1)
                clock.now = clock.now.plusSeconds(2)
                connection.commit()
                val response = cancelling.get(5, TimeUnit.SECONDS)
                assertThat(response.status).isEqualTo(409)
                assertThat(body(response).path("code").asText()).isEqualTo("CANCELLATION_TOO_LATE")
                assertThat(inventory.counter.get()).isEqualTo(1)
                assertThat(inventory.releases.get()).isZero()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pending booking on arrival cannot be cancelled by owner or admin`(asAdmin: Boolean) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        inventory.commitEntered = entered
        inventory.commitProceed = proceed
        val pool = Executors.newSingleThreadExecutor()
        try {
            val creating = pool.submit<MockHttpServletResponse> { create(today()) }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val id = requireNotNull(jdbc.queryForObject("select id from bookings", UUID::class.java)).toString()
            assertThat(cancel(id, if (asAdmin) admin.token else owner.token).status).isEqualTo(409)
            assertThat(inventory.releases.get()).isZero()
            proceed.countDown()
            assertThat(creating.get(5, TimeUnit.SECONDS).status).isEqualTo(201)
            assertThat(inventory.counter.get()).isEqualTo(1)
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `pending release returns accepted and repeat after arrival can finish compensation`() {
        val id = body(create()).path("id").asText()
        inventory.releaseBlocked = true
        val accepted = cancel(id, owner.token)
        assertThat(accepted.status).isEqualTo(202)
        assertThat(body(accepted).path("status").asText()).isEqualTo("CANCELLED")
        assertThat(body(accepted).path("compensationStatus").asText()).isEqualTo("PENDING")
        assertThat(inventory.counter.get()).isEqualTo(1)
        clock.now = clock.now.plusSeconds(2 * 86400)
        owner = owner.copy(token = login(owner.username))
        inventory.releaseBlocked = false
        val completed = cancel(id, owner.token)
        assertThat(completed.status).isEqualTo(200)
        assertThat(body(completed).path("compensationStatus").asText()).isEqualTo("DONE")
        assertThat(inventory.counter.get()).isZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `accepted cancellation wins against late successful commit`(asAdmin: Boolean) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        inventory.commitEntered = entered
        inventory.commitProceed = proceed
        val pool = Executors.newSingleThreadExecutor()
        try {
            val creating = pool.submit<MockHttpServletResponse> { create(assertSuccess = false) }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val id = requireNotNull(jdbc.queryForObject("select id from bookings", UUID::class.java)).toString()
            assertThat(inventory.counter.get()).isEqualTo(1)
            val cancelled = cancel(id, if (asAdmin) admin.token else owner.token)
            assertThat(cancelled.status).isEqualTo(200)
            assertThat(body(cancelled).path("status").asText()).isEqualTo("CANCELLED")
            assertThat(inventory.counter.get()).isZero()
            proceed.countDown()
            val original = creating.get(5, TimeUnit.SECONDS)
            assertThat(original.status).isEqualTo(409)
            assertThat(body(original).path("bookingId").asText()).isEqualTo(id)
            assertThat(body(original).path("code").asText()).isEqualTo("BOOKING_CANCELLED")
            assertThat(jdbc.queryForObject("select status from bookings", String::class.java)).isEqualTo("CANCELLED")
            assertThat(inventory.counter.get()).isZero()
            assertThat(inventory.releases.get()).isEqualTo(1)
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    private fun today() = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun create(
        start: LocalDate = today().plusDays(1),
        assertSuccess: Boolean = true,
    ): MockHttpServletResponse {
        val response =
            mvc
                .perform(
                    post("/api/bookings")
                        .header("Authorization", "Bearer ${owner.token}")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(
                            mapper.writeValueAsBytes(
                                mapOf(
                                    "autoSelect" to false,
                                    "roomId" to UUID.randomUUID(),
                                    "startDate" to start,
                                    "endDate" to start.plusDays(2),
                                ),
                            ),
                        ),
                ).andReturn()
                .response
        if (assertSuccess) assertThat(response.status).isEqualTo(201)
        return response
    }

    private fun cancel(
        id: String,
        token: String,
    ) = mvc.perform(delete("/api/bookings/$id").header("Authorization", "Bearer $token")).andReturn().response

    private fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    private fun user(admin: Boolean = false): User {
        val username = UUID.randomUUID().toString()
        val response =
            mvc
                .perform(
                    post("/api/users/register").contentType("application/json").content(
                        mapper.writeValueAsBytes(mapOf("username" to username, "password" to "test-password")),
                    ),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(201)
        if (admin) jdbc.update("update users set role = 'ADMIN' where username = ?", username)
        return User(username, login(username))
    }

    private fun login(username: String): String {
        val response =
            mvc
                .perform(
                    post("/api/users/auth").contentType("application/json").content(
                        mapper.writeValueAsBytes(mapOf("username" to username, "password" to "test-password")),
                    ),
                ).andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        return body(response).path("accessToken").asText()
    }

    data class User(
        val username: String,
        val token: String,
    )

    class MutableClock : Clock() {
        @Volatile var now: Instant = Instant.now()

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    class Inventory : HotelInventory {
        val states = ConcurrentHashMap<UUID, String>()
        val counter = AtomicInteger()
        val releases = AtomicInteger()

        @Volatile var releaseBlocked = false

        @Volatile var commitEntered: CountDownLatch? = null

        @Volatile var commitProceed: CountDownLatch? = null

        override fun room(
            id: UUID,
            traceId: String?,
        ) = InventoryRoom(id, UUID.randomUUID())

        override fun hold(booking: Booking): InventoryResult {
            val state = states.computeIfAbsent(booking.attemptId) { "HELD" }
            if (state == "RELEASED") throw BookingException(BookingError.ATTEMPT_RELEASED)
            return result(booking, state)
        }

        override fun commit(booking: Booking): InventoryResult {
            states.compute(booking.attemptId) { _, old ->
                if (old == "RELEASED") throw BookingException(BookingError.ATTEMPT_RELEASED)
                if (old != "RESERVED") counter.incrementAndGet()
                "RESERVED"
            }
            commitEntered?.countDown()
            check(commitProceed?.await(8, TimeUnit.SECONDS) != false)
            return result(booking, "RESERVED")
        }

        override fun release(booking: Booking): InventoryResult {
            if (releaseBlocked) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
            releases.incrementAndGet()
            states.compute(booking.attemptId) { _, old ->
                if (old == "RESERVED") counter.decrementAndGet()
                "RELEASED"
            }
            return result(booking, "RELEASED")
        }

        private fun result(
            b: Booking,
            status: String,
        ) = InventoryResult(b.attemptId, b.id, requireNotNull(b.roomId), b.startDate, b.endDate, InventoryStatus.valueOf(status))
    }

    @TestConfiguration class Configuration {
        @Bean @Primary
        fun cancellationClock() = MutableClock()

        @Bean @Primary
        fun inventory() = Inventory()
    }
}
