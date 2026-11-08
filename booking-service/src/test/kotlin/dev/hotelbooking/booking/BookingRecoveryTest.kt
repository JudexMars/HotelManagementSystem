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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "booking.saga.lease=500ms", "booking.recovery.interval-ms=30",
        "booking.saga.initial-backoff=1ms", "booking.saga.recovery-backoff=100ms",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(BookingRecoveryTest.Configuration::class)
class BookingRecoveryTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var inventory: InterruptedInventory

    @BeforeEach fun reset() {
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from bookings
            """.trimIndent(),
        )
        inventory.states.clear()
        inventory.attempts.clear()
        inventory.counter.set(0)
        inventory.crashed.set(false)
        inventory.crashAt = ""
        inventory.commitFails = false
        inventory.releaseBlocked = false
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROOM", "HOLD", "COMMIT"])
    fun `background resumes interrupted step without another user request`(stage: String) {
        inventory.crashAt = stage
        assertThatThrownBy { create() }.hasRootCauseInstanceOf(SimulatedStop::class.java)
        await { jdbc.queryForObject("select count(*) from bookings where status = 'CONFIRMED'", Long::class.java) == 1L }
        assertThat(inventory.counter.get()).isEqualTo(1)
        assertThat(inventory.attempts.size).isEqualTo(1)
        assertThat(jdbc.queryForObject("select attempt_id from bookings", UUID::class.java)).isEqualTo(inventory.attempts.single())
    }

    @Test fun `background compensates an interrupted commit after the booking deadline`() {
        inventory.crashAt = "COMMIT"
        assertThatThrownBy { create() }.hasRootCauseInstanceOf(SimulatedStop::class.java)
        assertThat(inventory.counter.get()).isEqualTo(1)
        // Apply the same test timestamp to every seeded booking.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            update bookings set deadline = dateadd('SECOND', -1, current_timestamp)
            """.trimIndent(),
        )
        await { jdbc.queryForObject("select compensation_status from bookings", String::class.java) == "DONE" }
        assertThat(jdbc.queryForObject("select status from bookings", String::class.java)).isEqualTo("CANCELLED")
        assertThat(jdbc.queryForObject("select failure_code from bookings", String::class.java)).isEqualTo("BOOKING_DEADLINE")
        assertThat(inventory.counter.get()).isZero()
        assertThat(inventory.attempts.size).isEqualTo(1)
        assertThat(inventory.states.values).containsExactly("RELEASED")
    }

    @Test fun `lost release response is recovered without a second decrement`() {
        inventory.commitFails = true
        inventory.crashAt = "RELEASE"
        assertThatThrownBy { create() }.hasRootCauseInstanceOf(SimulatedStop::class.java)
        await { jdbc.queryForObject("select compensation_status from bookings", String::class.java) == "DONE" }
        assertThat(inventory.counter.get()).isZero()
        assertThat(inventory.attempts.size).isEqualTo(1)
        assertThat(jdbc.queryForObject("select status from bookings", String::class.java)).isEqualTo("CANCELLED")
    }

    @Test fun `pending compensation finishes after Hotel recovers without a new request`() {
        inventory.commitFails = true
        inventory.releaseBlocked = true
        assertThat(create()).isEqualTo(503)
        assertThat(jdbc.queryForObject("select compensation_status from bookings", String::class.java)).isEqualTo("PENDING")
        assertThat(inventory.counter.get()).isEqualTo(1)
        inventory.releaseBlocked = false
        await { jdbc.queryForObject("select compensation_status from bookings", String::class.java) == "DONE" }
        assertThat(inventory.counter.get()).isZero()
    }

    private fun create(): Int {
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
                    "startDate" to today.plusDays(1),
                    "endDate" to today.plusDays(2),
                ),
            )
        return mvc
            .perform(
                post("/api/bookings")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Authorization", "Bearer $token")
                    .contentType("application/json")
                    .content(payload),
            ).andReturn()
            .response.status
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(check()).describedAs("Background workflow must reach its terminal result").isTrue()
    }

    class SimulatedStop : RuntimeException("simulated handler stop")

    class InterruptedInventory : HotelInventory {
        val states = ConcurrentHashMap<UUID, String>()
        val attempts: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val counter = AtomicInteger()
        val crashed = AtomicBoolean()

        @Volatile var crashAt = ""

        @Volatile var commitFails = false

        @Volatile var releaseBlocked = false
        private val hotelId = UUID.randomUUID()

        private fun stop(stage: String) {
            if (crashAt == stage && crashed.compareAndSet(false, true)) throw SimulatedStop()
        }

        override fun room(
            id: UUID,
            traceId: String?,
        ): InventoryRoom {
            stop("ROOM")
            return InventoryRoom(id, hotelId)
        }

        override fun hold(booking: Booking): InventoryResult {
            attempts.add(booking.attemptId)
            val state = states.computeIfAbsent(booking.attemptId) { "HELD" }
            if (state == "RELEASED") throw BookingException(BookingError.ATTEMPT_RELEASED)
            stop("HOLD")
            return result(booking, state)
        }

        override fun commit(booking: Booking): InventoryResult {
            attempts.add(booking.attemptId)
            states.compute(booking.attemptId) { _, old ->
                if (old == "RELEASED") throw BookingException(BookingError.ATTEMPT_RELEASED)
                if (old != "RESERVED") counter.incrementAndGet()
                "RESERVED"
            }
            stop("COMMIT")
            if (commitFails) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
            return result(booking, "RESERVED")
        }

        override fun release(booking: Booking): InventoryResult {
            attempts.add(booking.attemptId)
            if (releaseBlocked) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
            states.compute(booking.attemptId) { _, old ->
                if (old == "RESERVED") counter.decrementAndGet()
                "RELEASED"
            }
            stop("RELEASE")
            return result(booking, "RELEASED")
        }

        private fun result(
            b: Booking,
            status: String,
        ) = InventoryResult(b.attemptId, b.id, requireNotNull(b.roomId), b.startDate, b.endDate, InventoryStatus.valueOf(status))
    }

    @TestConfiguration class Configuration {
        @Bean @Primary
        fun inventory() = InterruptedInventory()
    }
}
