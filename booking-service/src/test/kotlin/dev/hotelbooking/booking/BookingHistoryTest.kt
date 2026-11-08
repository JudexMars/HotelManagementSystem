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
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "booking.recovery.enabled=false"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(BookingHistoryTest.Configuration::class)
class BookingHistoryTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var inventory: Inventory
    private lateinit var owner: User
    private lateinit var other: User
    private lateinit var admin: User

    @BeforeEach fun setup() {
        // Reset all rows to isolate this test.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            delete from bookings
            """.trimIndent(),
        )
        inventory.calls.set(0)
        inventory.unavailable = false
        inventory.stopHold = false
        inventory.releaseUnavailable = false
        owner = user()
        other = user()
        admin = user(admin = true)
    }

    @Test fun `owner pages are stable with equal timestamps and never contain another owner`() {
        val own = (1..4).map { create(owner).path("id").asText() }
        repeat(2) { create(other) }
        create(admin)
        // Apply the same test timestamp to every seeded booking.
        jdbc.update(
            """
            -- noinspection SqlWithoutWhere
            update bookings set created_at = timestamp with time zone '2026-01-01 00:00:00+00'
            """.trimIndent(),
        )
        val expected = own.sortedDescending()
        val page0 = history(owner, "page=0&size=2")
        val page1 = history(owner, "page=1&size=2")
        assertThat(ids(page0) + ids(page1)).containsExactlyElementsOf(expected)
        assertThat(page0.path("totalElements").asLong()).isEqualTo(4)
        assertThat(page0.path("totalPages").asLong()).isEqualTo(2)
        assertThat(page1.path("page").asInt()).isEqualTo(1)
        assertThat(ids(history(owner, "page=2&size=2"))).isEmpty()
        assertThat(ids(history(owner, "page=0&size=2"))).isEqualTo(ids(page0))
        assertThat(page0.path("items").map { it.path("userId").asText() }).containsOnly(owner.id)
    }

    @Test fun `created time precedes UUID tie break`() {
        val first = create(owner).path("id").asText()
        val later = create(owner).path("id").asText()
        jdbc.update(
            "update bookings set created_at = timestamp with time zone '2026-01-01 00:00:00+00' where id = ?",
            UUID.fromString(first),
        )
        jdbc.update(
            "update bookings set created_at = timestamp with time zone '2026-01-02 00:00:00+00' where id = ?",
            UUID.fromString(later),
        )
        assertThat(ids(history(owner))).containsExactly(later, first)
    }

    @Test fun `admin sees all and can filter another or own user`() {
        val own = create(owner)
        val foreign = create(other)
        val administrative = create(admin)
        assertThat(
            ids(history(admin)),
        ).containsExactlyInAnyOrder(own.path("id").asText(), foreign.path("id").asText(), administrative.path("id").asText())
        assertThat(ids(history(admin, "userId=${owner.id}"))).containsExactly(own.path("id").asText())
        assertThat(ids(history(admin, "userId=${admin.id}"))).containsExactly(administrative.path("id").asText())
        val missing = history(admin, "userId=${UUID.randomUUID()}")
        assertThat(ids(missing)).isEmpty()
        assertThat(missing.path("totalElements").asLong()).isZero()
    }

    @Test fun `user cannot expand visibility with another user filter`() {
        val own = create(owner)
        create(other)
        val forbidden = getHistory(owner, "userId=${other.id}")
        assertThat(forbidden.status).isEqualTo(403)
        assertThat(body(forbidden).path("code").asText()).isEqualTo("BOOKING_FILTER_FORBIDDEN")
        assertThat(ids(history(owner, "userId=${owner.id}"))).containsExactly(own.path("id").asText())
    }

    @Test fun `page bounds and defaults match the public contract`() {
        for (query in listOf("page=-1", "size=0", "size=101", "page=2147483647&size=100")) {
            assertThat(getHistory(owner, query).status).isEqualTo(400)
        }
        val empty = history(owner)
        assertThat(empty.path("page").asInt()).isZero()
        assertThat(empty.path("size").asInt()).isEqualTo(20)
        assertThat(empty.path("totalPages").asLong()).isZero()
        assertThat(ids(empty)).isEmpty()
    }

    @Test fun `deleted user keeps pending confirmed and cancelled history without Hotel calls`() {
        val confirmed = create(owner)
        val cancelled = create(owner)
        val cancelResponse =
            mvc
                .perform(
                    delete("/api/bookings/${cancelled.path("id").asText()}")
                        .header("Authorization", "Bearer ${owner.token}"),
                ).andReturn()
                .response
        assertThat(cancelResponse.status).isEqualTo(200)
        inventory.stopHold = true
        assertThatThrownBy { create(owner) }.hasRootCauseInstanceOf(SimulatedStop::class.java)
        val before = history(owner)
        assertThat(before.path("items").map { it.path("status").asText() }).containsExactlyInAnyOrder("PENDING", "CONFIRMED", "CANCELLED")
        val calls = inventory.calls.get()
        inventory.unavailable = true
        val deleted = mvc.perform(delete("/api/users/${owner.id}").header("Authorization", "Bearer ${admin.token}")).andReturn().response
        assertThat(deleted.status).isEqualTo(204)
        assertThat(login(owner.username).status).isEqualTo(401)
        assertThat(history(owner)).isEqualTo(before)
        assertThat(history(admin, "userId=${owner.id}")).isEqualTo(before)
        val read =
            mvc
                .perform(get("/api/bookings/${confirmed.path("id").asText()}").header("Authorization", "Bearer ${admin.token}"))
                .andReturn()
                .response
        assertThat(read.status).isEqualTo(200)
        assertThat(body(read)).isEqualTo(confirmed)
        assertThat(inventory.calls.get()).isEqualTo(calls)
        assertThat(
            jdbc.queryForObject("select count(*) from bookings where user_id = ?", Long::class.java, UUID.fromString(owner.id)),
        ).isEqualTo(3)
    }

    @Test fun `history exposes pending compensation without requiring Hotel`() {
        val created = create(owner)
        inventory.releaseUnavailable = true
        val cancelled =
            mvc
                .perform(
                    delete("/api/bookings/${created.path("id").asText()}")
                        .header("Authorization", "Bearer ${owner.token}"),
                ).andReturn()
                .response
        assertThat(cancelled.status).isEqualTo(202)
        inventory.unavailable = true
        val calls = inventory.calls.get()
        val page = history(owner)
        assertThat(page.path("items")).hasSize(1)
        assertThat(page.path("items")[0]).isEqualTo(body(cancelled))
        assertThat(page.path("items")[0].path("compensationStatus").asText()).isEqualTo("PENDING")
        assertThat(inventory.calls.get()).isEqualTo(calls)
    }

    private fun history(
        user: User,
        query: String = "",
    ): JsonNode {
        val response = getHistory(user, query)
        assertThat(response.status).isEqualTo(200)
        return body(response)
    }

    private fun getHistory(
        user: User,
        query: String,
    ) = mvc
        .perform(get("/api/bookings?$query").header("Authorization", "Bearer ${user.token}"))
        .andReturn()
        .response

    private fun ids(page: JsonNode) = page.path("items").map { it.path("id").asText() }

    private fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    private fun create(user: User): JsonNode {
        val start = LocalDate.now(ZoneOffset.UTC).plusDays(2)
        val response =
            mvc
                .perform(
                    post("/api/bookings")
                        .header("Authorization", "Bearer ${user.token}")
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
        assertThat(response.status).isEqualTo(201)
        return body(response)
    }

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
        return User(body(response).path("user").path("id").asText(), username, body(login(username)).path("accessToken").asText())
    }

    private fun login(username: String) =
        mvc
            .perform(
                post("/api/users/auth").contentType("application/json").content(
                    mapper.writeValueAsBytes(mapOf("username" to username, "password" to "test-password")),
                ),
            ).andReturn()
            .response

    data class User(
        val id: String,
        val username: String,
        val token: String,
    )

    class SimulatedStop : RuntimeException("simulated handler stop")

    class Inventory : HotelInventory {
        val calls = AtomicInteger()

        @Volatile var unavailable = false

        @Volatile var stopHold = false

        @Volatile var releaseUnavailable = false

        private fun called() {
            calls.incrementAndGet()
            check(!unavailable) { "History must not call Hotel" }
        }

        override fun room(
            id: UUID,
            traceId: String?,
        ): InventoryRoom {
            called()
            return InventoryRoom(id, UUID.randomUUID())
        }

        override fun hold(booking: Booking): InventoryResult {
            called()
            if (stopHold) throw SimulatedStop()
            return result(booking, "HELD")
        }

        override fun commit(booking: Booking): InventoryResult {
            called()
            return result(booking, "RESERVED")
        }

        override fun release(booking: Booking): InventoryResult {
            called()
            if (releaseUnavailable) throw BookingException(BookingError.HOTEL_UNAVAILABLE)
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
