package dev.hotelbooking.booking
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.booking.application.port.HotelInventory
import dev.hotelbooking.booking.application.port.HotelRoomSearch
import dev.hotelbooking.booking.application.port.InventoryCandidate
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "booking.saga.lease=500ms", "booking.recovery.interval-ms=30",
        "booking.saga.initial-backoff=1ms", "booking.saga.recovery-backoff=100ms",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(BookingAutoSelectionTest.Configuration::class)
class BookingAutoSelectionTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var hotel: Inventory
    private lateinit var token: String
    private val selectedHotel = UUID.fromString("00000000-0000-0000-0000-000000000010")
    private val anotherHotel = UUID.fromString("00000000-0000-0000-0000-000000000020")
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val popular = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val start = LocalDate.now(ZoneOffset.UTC).plusDays(1)

    @BeforeEach fun setup() {
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from bookings
            """.trimIndent(),
        )
        hotel.rooms =
            listOf(
                InventoryCandidate(popular, selectedHotel, "popular", true, 2),
                InventoryCandidate(second, selectedHotel, "second", true, 0),
                InventoryCandidate(first, selectedHotel, "first", true, 0),
                InventoryCandidate(other, anotherHotel, "other", true, 0),
            )
        hotel.states.clear()
        hotel.calls.clear()
        hotel.attempts.clear()
        hotel.rejectRoom = null
        hotel.rejection = BookingError.ROOM_UNAVAILABLE
        hotel.searchEntered = null
        hotel.searchProceed = null
        hotel.timeoutRoom = null
        hotel.releaseBlocked = false
        hotel.stopRelease = false
        hotel.stopped.set(false)
        val registered =
            mvc
                .perform(
                    post("/api/users/register").contentType("application/json").content(
                        mapper.writeValueAsBytes(mapOf("username" to UUID.randomUUID().toString(), "password" to "test-password")),
                    ),
                ).andReturn()
                .response
        assertThat(registered.status).isEqualTo(201)
        token = body(registered).path("accessToken").asText()
    }

    @Test fun `recommendations use selected hotel or all hotels and never hold rooms`() {
        val filtered = recommendations(selectedHotel)
        assertThat(filtered.status).isEqualTo(200)
        assertThat(body(filtered).map { it.path("id").asText() }).containsExactly(first.toString(), second.toString(), popular.toString())
        val all = recommendations(null)
        assertThat(all.status).isEqualTo(200)
        assertThat(
            body(all).map {
                it.path("id").asText()
            },
        ).containsExactly(first.toString(), second.toString(), other.toString(), popular.toString())
        assertThat(hotel.attempts).isEmpty()
    }

    @Test fun `auto selection picks lowest counter then id and ignores supplied manual room on replay`() {
        val key = UUID.randomUUID().toString()
        val created = create(key, manualRoom = popular)
        assertThat(created.status).isEqualTo(201)
        assertThat(body(created).path("roomId").asText()).isEqualTo(first.toString())
        assertThat(body(created).path("hotelId").asText()).isEqualTo(selectedHotel.toString())
        val replay = create(key, manualRoom = other)
        assertThat(replay.status).isEqualTo(200)
        assertThat(body(replay)).isEqualTo(body(created))
        assertThat(hotel.attempts).hasSize(1)
    }

    @Test fun `known hold conflict is released before trying next candidate`() {
        hotel.rejectRoom = first
        val result = create()
        assertThat(result.status).isEqualTo(201)
        assertThat(body(result).path("roomId").asText()).isEqualTo(second.toString())
        assertThat(hotel.calls).containsSubsequence("hold:$first", "release:$first", "hold:$second", "commit:$second")
        val rejected = hotel.attempts.values.single { it.roomId == first }
        assertThat(hotel.states[rejected.attemptId]).isEqualTo("RELEASED")
        assertThat(
            hotel.attempts.values
                .map { it.id }
                .distinct(),
        ).hasSize(1)
        assertThat(hotel.attempts).hasSize(2)
        assertThatThrownBy { hotel.hold(rejected) }.isInstanceOf(BookingException::class.java)
        assertThat(hotel.states.values.count { it == "RESERVED" }).isEqualTo(1)
    }

    @Test fun `unknown hold outcome never switches room`() {
        hotel.timeoutRoom = first
        val result = create()
        assertThat(result.status).isEqualTo(504)
        assertThat(body(result).path("status").asText()).isEqualTo("CANCELLED")
        assertThat(
            hotel.attempts.values
                .map { it.roomId }
                .distinct(),
        ).containsExactly(first)
        assertThat(hotel.states.values).containsExactly("RELEASED")
        assertThat(hotel.calls).doesNotContain("hold:$second")
    }

    @Test fun `unconfirmed release prevents fallback`() {
        hotel.rejectRoom = first
        hotel.releaseBlocked = true
        val result = create()
        assertThat(result.status).isEqualTo(503)
        assertThat(body(result).path("compensationStatus").asText()).isEqualTo("PENDING")
        assertThat(hotel.calls).doesNotContain("hold:$second")
        hotel.releaseBlocked = false
        await { jdbc.queryForObject("select compensation_status from bookings", String::class.java) == "DONE" }
        assertThat(hotel.calls).doesNotContain("hold:$second")
    }

    @Test fun `background resumes after release effect before switching candidate`() {
        hotel.rejectRoom = first
        hotel.stopRelease = true
        assertThatThrownBy { create() }.hasRootCauseInstanceOf(SimulatedStop::class.java)
        await { jdbc.queryForObject("select status from bookings", String::class.java) == "CONFIRMED" }
        assertThat(jdbc.queryForObject("select room_id from bookings", UUID::class.java)).isEqualTo(second)
        assertThat(hotel.attempts).hasSize(2)
        assertThat(hotel.states.values.count { it == "RESERVED" }).isEqualTo(1)
        assertThat(hotel.states.values.count { it == "RELEASED" }).isEqualTo(1)
    }

    @Test fun `unrelated business conflict does not switch candidate`() {
        hotel.rejectRoom = first
        hotel.rejection = BookingError.ATTEMPT_CONFLICT
        val result = create()
        assertThat(result.status).isEqualTo(409)
        assertThat(body(result).path("code").asText()).isEqualTo("ATTEMPT_CONFLICT")
        assertThat(hotel.attempts).hasSize(1)
        assertThat(hotel.calls).doesNotContain("hold:$second")
        assertThat(hotel.states.values).containsExactly("RELEASED")
    }

    @Test fun `cancellation during candidate search needs no remote release and prevents later hold`() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        hotel.searchEntered = entered
        hotel.searchProceed = proceed
        val pool = Executors.newSingleThreadExecutor()
        try {
            val creating = pool.submit<MockHttpServletResponse> { create() }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val row = jdbc.queryForMap("select id, room_id from bookings")
            assertThat(row["ROOM_ID"]).isNull()
            val cancelled = mvc.perform(delete("/api/bookings/${row["ID"]}").header("Authorization", "Bearer $token")).andReturn().response
            assertThat(cancelled.status).isEqualTo(200)
            assertThat(body(cancelled).path("compensationStatus").asText()).isEqualTo("DONE")
            proceed.countDown()
            assertThat(creating.get(5, TimeUnit.SECONDS).status).isEqualTo(409)
            assertThat(hotel.calls).isEmpty()
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `empty candidate list cancels without inventory commands`() {
        hotel.rooms = emptyList()
        val result = create()
        assertThat(result.status).isEqualTo(409)
        assertThat(body(result).path("status").asText()).isEqualTo("CANCELLED")
        assertThat(body(result).path("compensationStatus").asText()).isEqualTo("DONE")
        assertThat(hotel.calls).isEmpty()
    }

    private fun recommendations(hotelId: UUID?): MockHttpServletResponse {
        val request =
            get("/api/bookings/recommendations")
                .header("Authorization", "Bearer $token")
                .param("startDate", start.toString())
                .param("endDate", start.plusDays(2).toString())
        if (hotelId != null) request.param("hotelId", hotelId.toString())
        return mvc.perform(request).andReturn().response
    }

    private fun create(
        key: String = UUID.randomUUID().toString(),
        manualRoom: UUID? = null,
    ): MockHttpServletResponse =
        mvc
            .perform(
                post("/api/bookings")
                    .header("Authorization", "Bearer $token")
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsBytes(
                            mapOf(
                                "autoSelect" to true,
                                "hotelId" to selectedHotel,
                                "roomId" to manualRoom,
                                "startDate" to start,
                                "endDate" to start.plusDays(2),
                            ),
                        ),
                    ),
            ).andReturn()
            .response

    private fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    private fun await(check: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!check() && System.nanoTime() < limit) Thread.sleep(20)
        assertThat(check()).isTrue()
    }

    class SimulatedStop : RuntimeException("simulated handler stop")

    class Inventory :
        HotelInventory,
        HotelRoomSearch {
        @Volatile var rooms = emptyList<InventoryCandidate>()

        @Volatile var rejectRoom: UUID? = null

        @Volatile var rejection = BookingError.ROOM_UNAVAILABLE

        @Volatile var searchEntered: CountDownLatch? = null

        @Volatile var searchProceed: CountDownLatch? = null

        @Volatile var timeoutRoom: UUID? = null

        @Volatile var releaseBlocked = false

        @Volatile var stopRelease = false
        val stopped = AtomicBoolean()
        val calls = CopyOnWriteArrayList<String>()
        val states = ConcurrentHashMap<UUID, String>()
        val attempts = ConcurrentHashMap<UUID, Booking>()

        override fun candidates(
            startDate: LocalDate,
            endDate: LocalDate,
            hotelId: UUID?,
            traceId: String?,
        ): List<InventoryCandidate> {
            searchEntered?.countDown()
            check(searchProceed?.await(8, TimeUnit.SECONDS) != false)
            return rooms.filter { hotelId == null || it.hotelId == hotelId }
        }

        override fun room(
            id: UUID,
            traceId: String?,
        ): InventoryRoom = error("Auto selection must use the candidate snapshot")

        override fun hold(booking: Booking): InventoryResult {
            attempts[booking.attemptId] = booking
            calls.add("hold:${booking.roomId}")
            if (states[booking.attemptId] == "RELEASED") throw BookingException(BookingError.ATTEMPT_RELEASED)
            if (booking.roomId == rejectRoom) throw BookingException(rejection)
            states.putIfAbsent(booking.attemptId, "HELD")
            if (booking.roomId == timeoutRoom) throw BookingException(BookingError.HOTEL_TIMEOUT)
            return result(booking, requireNotNull(states[booking.attemptId]))
        }

        override fun commit(booking: Booking): InventoryResult {
            calls.add("commit:${booking.roomId}")
            states[booking.attemptId] = "RESERVED"
            return result(booking, "RESERVED")
        }

        override fun release(booking: Booking): InventoryResult {
            calls.add("release:${booking.roomId}")
            if (releaseBlocked) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
            states[booking.attemptId] = "RELEASED"
            if (stopRelease && stopped.compareAndSet(false, true)) throw SimulatedStop()
            return result(booking, "RELEASED")
        }

        private fun result(
            b: Booking,
            status: String,
        ) = InventoryResult(b.attemptId, b.id, requireNotNull(b.roomId), b.startDate, b.endDate, InventoryStatus.valueOf(status))
    }

    @TestConfiguration class Configuration {
        @Bean @Primary
        fun inventory() = Inventory()
    }
}
