package dev.hotelbooking.booking
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "booking.saga.budget=1s",
        "booking.recovery.enabled=false",
        "booking.saga.initial-backoff=1ms",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(BookingFlowTest.Configuration::class)
class BookingFlowTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var inventory: InspectingInventory

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var clock: TestClock

    @BeforeEach fun reset() {
        inventory.calls.clear()
        inventory.wrongBinding = false
        inventory.commitStatus = "RESERVED"
        inventory.commitFailures = 0
        inventory.lateCommit = false
        inventory.afterCommit = null
        inventory.releaseFails = false
        clock.now.set(Instant.now())
    }

    @Test fun `pending and step are committed before each remote call and attempt is stable`() {
        val response = create()
        assertThat(response.first).isEqualTo(201)
        val booking = mapper.readTree(response.second)
        assertThat(booking.path("status").asText()).isEqualTo("CONFIRMED")
        assertThat(booking.path("step").asText()).isEqualTo("DONE")
        assertThat(inventory.calls).containsExactly("RESOLVE_ROOM", "HOLD", "COMMIT")
        assertThat(booking.path("attemptId").asText()).isEqualTo(inventory.attempt.toString())
    }

    @Test fun `wrong commit binding cannot produce confirmed booking`() {
        inventory.wrongBinding = true
        val response = create()
        assertThat(response.first).isEqualTo(503)
        assertThat(mapper.readTree(response.second).path("code").asText()).isEqualTo("INVENTORY_PROTOCOL_ERROR")
        assertThat(jdbc.queryForObject("select status from bookings where attempt_id = ?", String::class.java, inventory.attempt))
            .isEqualTo("CANCELLED")
    }

    @Test fun `valid status for another step cannot confirm booking and still compensates`() {
        inventory.commitStatus = "HELD"
        val response = create()
        assertThat(response.first).isEqualTo(503)
        assertThat(mapper.readTree(response.second).path("code").asText()).isEqualTo("INVENTORY_PROTOCOL_ERROR")
        val stored = jdbc.queryForMap("select status, compensation_status from bookings where attempt_id = ?", inventory.attempt)
        assertThat(stored["STATUS"]).isEqualTo("CANCELLED")
        assertThat(stored["COMPENSATION_STATUS"]).isEqualTo("DONE")
    }

    @Test fun `temporary commit failure retries the same attempt and then confirms`() {
        inventory.commitFailures = 1
        val response = create()
        assertThat(response.first).isEqualTo(201)
        assertThat(inventory.calls).containsExactly("RESOLVE_ROOM", "HOLD", "COMMIT", "COMMIT")
        assertThat(mapper.readTree(response.second).path("status").asText()).isEqualTo("CONFIRMED")
    }

    @Test fun `exhausted commit retries persist cancellation and reference in creation error`() {
        inventory.commitFailures = 20
        val response = create()
        assertThat(response.first).isEqualTo(503)
        val error = mapper.readTree(response.second)
        val saved = jdbc.queryForMap("select id, status from bookings where attempt_id = ?", inventory.attempt)
        assertThat(saved["STATUS"]).isEqualTo("CANCELLED")
        assertThat(error.path("bookingId").asText()).isEqualTo(saved["ID"].toString())
        assertThat(error.path("status").asText()).isEqualTo("CANCELLED")
        assertThat(inventory.calls.count { it == "COMMIT" }).isEqualTo(3)
    }

    @Test fun `reserved returned after budget cannot confirm the booking`() {
        inventory.lateCommit = true
        val response = create()
        assertThat(response.first).isEqualTo(504)
        assertThat(mapper.readTree(response.second).path("code").asText()).isEqualTo("BOOKING_DEADLINE")
        assertThat(jdbc.queryForObject("select status from bookings where attempt_id = ?", String::class.java, inventory.attempt))
            .isEqualTo("CANCELLED")
    }

    @Test fun `commit finalization rechecks deadline after a real database lock wait`() {
        val commitReturned = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        inventory.afterCommit = {
            commitReturned.countDown()
            check(allowReturn.await(10, TimeUnit.SECONDS))
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Pair<Int, ByteArray>> { create() }
            assertThat(commitReturned.await(10, TimeUnit.SECONDS)).isTrue()
            requireNotNull(jdbc.dataSource).connection.use { connection ->
                connection.autoCommit = false
                val blockerId =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select session_id()").use { row ->
                            row.next()
                            row.getInt(1)
                        }
                    }
                connection.prepareStatement("select id from bookings where attempt_id = ? for update").use { statement ->
                    statement.setObject(1, inventory.attempt)
                    statement.executeQuery().use { row -> assertThat(row.next()).isTrue() }
                }
                allowReturn.countDown()

                fun blocked() =
                    jdbc.queryForObject(
                        "select count(*) from information_schema.sessions where blocker_id = ?",
                        Long::class.java,
                        blockerId,
                    ) ?: 0
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (blocked() == 0L && System.nanoTime() < limit) Thread.sleep(10)
                assertThat(blocked()).isEqualTo(1)
                clock.now.updateAndGet { it.plusSeconds(2) }
                connection.commit()
            }
            val response = future.get(10, TimeUnit.SECONDS)
            assertThat(response.first).isEqualTo(504)
            assertThat(mapper.readTree(response.second).path("compensationStatus").asText()).isEqualTo("DONE")
            assertThat(jdbc.queryForObject("select status from bookings where attempt_id = ?", String::class.java, inventory.attempt))
                .isEqualTo("CANCELLED")
        } finally {
            allowReturn.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `unavailable release leaves visible pending compensation`() {
        inventory.commitFailures = 20
        inventory.releaseFails = true
        val response = create()
        assertThat(response.first).isEqualTo(503)
        assertThat(mapper.readTree(response.second).path("compensationStatus").asText()).isEqualTo("PENDING")
        assertThat(
            jdbc.queryForObject("select compensation_status from bookings where attempt_id = ?", String::class.java, inventory.attempt),
        ).isEqualTo("PENDING")
    }

    private fun create(): Pair<Int, ByteArray> {
        val registration = mapper.writeValueAsBytes(mapOf("username" to UUID.randomUUID().toString(), "password" to "test-password"))
        val user = mvc.perform(post("/api/users/register").contentType("application/json").content(registration)).andReturn().response
        assertThat(user.status).isEqualTo(201)
        val token = mapper.readTree(user.contentAsByteArray).path("accessToken").asText()
        val today = LocalDate.now(ZoneOffset.UTC)
        val payload =
            mapper.writeValueAsBytes(
                mapOf(
                    "autoSelect" to false,
                    "roomId" to UUID.randomUUID(),
                    "startDate" to today,
                    "endDate" to today.plusDays(1),
                ),
            )
        val result =
            mvc
                .perform(
                    post("/api/bookings")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer $token")
                        .contentType("application/json")
                        .content(payload),
                ).andReturn()
                .response
        return result.status to result.contentAsByteArray
    }

    class InspectingInventory(
        private val jdbc: JdbcTemplate,
        private val clock: TestClock,
    ) : HotelInventory {
        val calls = mutableListOf<String>()
        var wrongBinding = false
        var commitStatus = "RESERVED"
        var commitFailures = 0
        var lateCommit = false
        var afterCommit: (() -> Unit)? = null
        var releaseFails = false
        var attempt: UUID? = null

        override fun room(
            id: UUID,
            traceId: String?,
        ): InventoryRoom {
            checkState(id, "RESOLVE_ROOM")
            return InventoryRoom(id, UUID.randomUUID())
        }

        override fun hold(booking: Booking): InventoryResult {
            checkState(requireNotNull(booking.roomId), "HOLD")
            attempt = booking.attemptId
            return result(booking, "HELD")
        }

        override fun commit(booking: Booking): InventoryResult {
            checkState(requireNotNull(booking.roomId), "COMMIT")
            assertThat(booking.attemptId).isEqualTo(attempt)
            if (commitFailures-- > 0) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
            afterCommit?.invoke()
            if (lateCommit) clock.now.updateAndGet { it.plusSeconds(2) }
            return result(booking, commitStatus).let { if (wrongBinding) it.copy(bookingId = UUID.randomUUID()) else it }
        }

        override fun release(booking: Booking): InventoryResult {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(
                jdbc.queryForObject("select status from bookings where id = ?", String::class.java, booking.id),
            ).isEqualTo("CANCELLED")
            if (releaseFails) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
            return result(booking, "RELEASED")
        }

        private fun checkState(
            room: UUID,
            step: String,
        ) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            val row = jdbc.queryForMap("select status, step from bookings where room_id = ?", room)
            assertThat(row["STATUS"]).isEqualTo("PENDING")
            assertThat(row["STEP"]).isEqualTo(step)
            calls.add(step)
        }

        private fun result(
            b: Booking,
            status: String,
        ) = InventoryResult(b.attemptId, b.id, requireNotNull(b.roomId), b.startDate, b.endDate, InventoryStatus.valueOf(status))
    }

    class TestClock : Clock() {
        val now = AtomicReference(Instant.now())

        override fun instant(): Instant = now.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration class Configuration {
        @Bean @Primary
        fun inventory(
            jdbc: JdbcTemplate,
            clock: TestClock,
        ) = InspectingInventory(jdbc, clock)

        @Bean @Primary
        fun testClock() = TestClock()
    }
}
