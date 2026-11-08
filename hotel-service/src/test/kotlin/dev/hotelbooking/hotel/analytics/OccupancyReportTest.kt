package dev.hotelbooking.hotel.analytics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.hotel.analytics.api.OccupancyController
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class OccupancyReportTest {
    @Autowired lateinit var controller: OccupancyController

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var jdbc: JdbcTemplate
    private val from = "2030-11-10"
    private val to = "2030-11-20"

    @BeforeEach fun clear() {
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

    @Test fun `each room keeps its own clipped metrics and ignores unrelated states hotels and boundary touches`() {
        val fixture = asymmetric()
        val result = report(fixture.hotel)
        assertThat(result.path("hotelId").asText()).isEqualTo(fixture.hotel)
        assertThat(result.path("hotelDeleted").asBoolean()).isFalse()
        assertThat(result.path("totalNights").asLong()).isEqualTo(10)
        val rows = result.path("rooms").associateBy { it.path("roomId").asText() }
        assertThat(rows.keys).containsExactlyInAnyOrder(fixture.edges, fixture.long, fixture.frequent, fixture.empty, fixture.boundaries)
        metric(rows.getValue(fixture.edges), "edges", 2, 4, "40.00")
        metric(rows.getValue(fixture.long), "long", 1, 6, "60.00")
        metric(rows.getValue(fixture.frequent), "frequent", 3, 3, "30.00")
        metric(rows.getValue(fixture.empty), "empty", 0, 0, "0.00")
        metric(rows.getValue(fixture.boundaries), "boundaries", 0, 0, "0.00")
    }

    @Test fun `count and occupancy sorts differ and ties use ascending room id`() {
        val f = asymmetric()
        val zero = listOf(f.empty, f.boundaries).sorted()
        assertThat(ids(report(f.hotel))).containsExactlyElementsOf(listOf(f.long, f.edges, f.frequent) + zero)
        assertThat(ids(report(f.hotel, sort = "bookings"))).containsExactlyElementsOf(listOf(f.frequent, f.edges, f.long) + zero)
        assertThat(ids(report(f.hotel, direction = "asc"))).containsExactlyElementsOf(zero + listOf(f.frequent, f.edges, f.long))
        assertThat(
            ids(report(f.hotel, sort = "bookings", direction = "asc")),
        ).containsExactlyElementsOf(zero + listOf(f.long, f.edges, f.frequent))
    }

    @Test fun `fractions are rounded after division with HALF_UP`() {
        val h = hotel()
        val one = room(h, "one")
        val two = room(h, "two")
        reservation(one, "2020-01-01", "2020-01-02")
        reservation(two, "2020-01-01", "2020-01-03")
        val thirds = report(h, "2020-01-01", "2020-01-04").path("rooms").associateBy { it.path("roomId").asText() }
        assertThat(thirds.getValue(one).path("occupancyPercent").decimalValue()).isEqualByComparingTo("33.33")
        assertThat(thirds.getValue(two).path("occupancyPercent").decimalValue()).isEqualByComparingTo("66.67")
        val half = report(h, "2020-01-01", "2020-02-02").path("rooms").single { it.path("roomId").asText() == one }
        assertThat(half.path("occupancyPercent").decimalValue()).isEqualByComparingTo("3.13")
    }

    @Test fun `deleted hotel and room retain historical metrics while public catalog hides them`() {
        val h = hotel()
        val r = room(h, "historical")
        reservation(r, "2020-01-01", "2020-01-03")
        val before = report(h, "2020-01-01", "2020-01-04")
        val removed =
            mvc
                .perform(delete("/api/hotels/$h").header("Authorization", "Bearer ${keys.userToken("ADMIN")}"))
                .andReturn()
                .response
        assertThat(removed.status).isEqualTo(204)
        assertThat(
            mvc
                .perform(get("/api/hotels/$h").header("Authorization", "Bearer ${keys.userToken()}"))
                .andReturn()
                .response.status,
        ).isEqualTo(404)
        assertThat(
            mvc
                .perform(get("/api/rooms/$r").header("Authorization", "Bearer ${keys.userToken()}"))
                .andReturn()
                .response.status,
        ).isEqualTo(404)
        val after = report(h, "2020-01-01", "2020-01-04")
        assertThat(after.path("hotelDeleted").asBoolean()).isTrue()
        assertThat(
            after
                .path("rooms")
                .single()
                .path("deleted")
                .asBoolean(),
        ).isTrue()
        for (field in listOf("roomId", "roomNumber", "bookingCount", "bookedNights", "occupancyPercent")) {
            assertThat(after.path("rooms").single().path(field)).isEqualTo(before.path("rooms").single().path(field))
        }
    }

    @Test fun `empty hotel is a report but unknown hotel is not found`() {
        val h = hotel()
        assertThat(report(h).path("rooms")).isEmpty()
        assertThat(send(UUID.randomUUID().toString()).status).isEqualTo(404)
    }

    @Test fun `invalid dates or ordering fail without computing a report`() {
        val h = hotel()
        assertThat(send(h, from, from).status).isEqualTo(400)
        assertThat(send(h, to, from).status).isEqualTo(400)
        assertThat(send(h, sort = "unknown").status).isEqualTo(400)
        assertThat(send(h, direction = "unknown").status).isEqualTo(400)
        assertThat(
            mvc
                .perform(get("/api/hotels/$h/occupancy").header("Authorization", "Bearer ${keys.userToken("ADMIN")}"))
                .andReturn()
                .response.status,
        ).isEqualTo(400)
    }

    @Test fun `only admin user audience may read analytics`() {
        val h = hotel()
        assertThat(send(h, token = keys.userToken()).status).isEqualTo(403)
        assertThat(send(h, token = keys.serviceToken()).status).isEqualTo(401)
        assertThat(
            mvc
                .perform(get("/api/hotels/$h/occupancy").param("from", from).param("to", to))
                .andReturn()
                .response.status,
        ).isEqualTo(401)
    }

    @Test
    fun `method security also blocks direct invocation by a user`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("test-user", null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        try {
            assertThatThrownBy {
                controller.report(UUID.randomUUID(), LocalDate.parse(from), LocalDate.parse(to), "occupancy", "desc")
            }.isInstanceOf(AccessDeniedException::class.java)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun asymmetric(): Fixture {
        val h = hotel()
        val edges = room(h, "edges")
        val long = room(h, "long")
        val frequent = room(h, "frequent")
        val empty = room(h, "empty")
        val boundaries = room(h, "boundaries")
        reservation(edges, "2030-11-08", "2030-11-12")
        reservation(edges, "2030-11-18", "2030-11-23")
        reservation(edges, "2030-11-12", "2030-11-13", "HELD")
        reservation(edges, "2030-11-13", "2030-11-14", "EXPIRED")
        reservation(edges, "2030-11-14", "2030-11-15", "RELEASED")
        reservation(edges, "2030-11-15", "2030-11-16", "HELD", expired = true)
        reservation(long, "2030-11-11", "2030-11-17")
        for (day in listOf(10, 12, 14)) reservation(frequent, "2030-11-$day", "2030-11-${day + 1}")
        reservation(boundaries, "2030-11-08", from)
        reservation(boundaries, to, "2030-11-22")
        reservation(room(hotel(), "foreign"), from, to)
        jdbc.update("update rooms set available = false where id = ?", UUID.fromString(edges))
        return Fixture(h, edges, long, frequent, empty, boundaries)
    }

    data class Fixture(
        val hotel: String,
        val edges: String,
        val long: String,
        val frequent: String,
        val empty: String,
        val boundaries: String,
    )

    private fun metric(
        row: JsonNode,
        number: String,
        count: Long,
        nights: Long,
        percent: String,
    ) {
        assertThat(row.path("roomNumber").asText()).isEqualTo(number)
        assertThat(row.path("bookingCount").asLong()).isEqualTo(count)
        assertThat(row.path("bookedNights").asLong()).isEqualTo(nights)
        assertThat(row.path("occupancyPercent").decimalValue()).isEqualByComparingTo(percent)
    }

    private fun ids(report: JsonNode) = report.path("rooms").map { it.path("roomId").asText() }

    private fun report(
        id: String,
        from: String = this.from,
        to: String = this.to,
        sort: String = "occupancy",
        direction: String = "desc",
    ): JsonNode {
        val response = send(id, from, to, sort, direction)
        assertThat(response.status).isEqualTo(200)
        return mapper.readTree(response.contentAsByteArray)
    }

    private fun send(
        id: String,
        from: String = this.from,
        to: String = this.to,
        sort: String = "occupancy",
        direction: String = "desc",
        token: String = keys.userToken("ADMIN"),
    ): MockHttpServletResponse =
        mvc
            .perform(
                get("/api/hotels/$id/occupancy")
                    .param("from", from)
                    .param("to", to)
                    .param("sort", sort)
                    .param("direction", direction)
                    .header("Authorization", "Bearer $token"),
            ).andReturn()
            .response

    private fun hotel() = create("/api/hotels", mapOf("name" to "Hotel", "address" to "Address"))

    private fun room(
        hotel: String,
        number: String,
    ) = create("/api/rooms", mapOf("hotelId" to hotel, "number" to number))

    private fun create(
        path: String,
        body: Any,
    ): String {
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
        return mapper.readTree(response.contentAsByteArray).path("id").asText()
    }

    private fun reservation(
        room: String,
        start: String,
        end: String,
        status: String = "RESERVED",
        expired: Boolean = false,
    ) {
        jdbc.update(
            """insert into reservations(attempt_id, booking_id, room_id, start_date, end_date, status, expires_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.fromString(room),
            LocalDate.parse(start),
            LocalDate.parse(end),
            status,
            Timestamp.from(Instant.now().plusSeconds(if (expired) -60 else 60)),
            Timestamp.from(Instant.now()),
        )
        if (status == "RESERVED") jdbc.update("update rooms set times_booked = times_booked + 1 where id = ?", UUID.fromString(room))
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
        fun close() = keys.close()
    }
}
