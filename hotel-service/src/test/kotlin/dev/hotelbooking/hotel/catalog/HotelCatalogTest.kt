package dev.hotelbooking.hotel.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.hotel.catalog.api.AuthorizedCatalogService
import dev.hotelbooking.hotel.security.JwtTestServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.util.UUID
import java.util.stream.Stream

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HotelCatalogTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var service: AuthorizedCatalogService

    @Test fun `method security prevents direct USER catalog writes without a database effect`() {
        val name = uniqueName()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "user",
                "unused",
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        try {
            assertThatThrownBy { service.createHotel(name, "Address") }.isInstanceOf(AccessDeniedException::class.java)
        } finally {
            SecurityContextHolder.clearContext()
        }
        assertThat(jdbc.queryForObject("select count(*) from hotels where name = ?", Long::class.javaObjectType, name)).isZero()
    }

    @Test fun `explicit null availability is rejected without changing the room`() {
        val original = createRoom(createHotel().path("id").asText(), "Nullable")
        val path = "/api/rooms/${original.path("id").asText()}"
        call(HttpMethod.PUT, path, mapOf("number" to "Changed", "available" to null), expectedStatus = 400, errorCode = "INVALID_REQUEST")
        assertThat(get(path)).isEqualTo(original)
    }

    @Test
    fun `filter wildcard characters are treated literally`() {
        val prefix = uniqueName()
        val target = createHotel("$prefix%_\\Literal", "Address")
        createHotel("${prefix}ABCXLiteral", "Address")
        val page = hotelPage(mapOf("name" to "$prefix%_\\"))
        assertThat(page.path("items").map { it.path("id").asText() }).containsExactly(target.path("id").asText())
        assertThat(page.path("totalElements").asInt()).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `created hotel is persisted and readable by either public role`(role: String) {
        val name = uniqueName()
        val hotel = createHotel(name, "River Street 12")

        val found = get("/api/hotels/${hotel.path("id").asText()}", role)

        assertHotel(hotel, name, "River Street 12")
        assertThat(found).isEqualTo(hotel)
    }

    @Test
    fun `hotel update preserves its id and persists both mutable fields`() {
        val hotel = createHotel()
        val id = hotel.path("id").asText()
        val updatedName = uniqueName()

        val updated = call(HttpMethod.PUT, "/api/hotels/$id", hotelInput(updatedName, "Updated Avenue 8"))

        assertHotel(updated, updatedName, "Updated Avenue 8")
        assertThat(updated.path("id")).isEqualTo(hotel.path("id"))
        assertThat(get("/api/hotels/$id")).isEqualTo(updated)
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `room creation defaults to available with zero booking count and persists its hotel`(role: String) {
        val hotelId = createHotel().path("id").asText()

        val room = createRoom(hotelId, "A-101")

        assertRoom(room, hotelId, "A-101", true)
        assertThat(get("/api/rooms/${room.path("id").asText()}", role)).isEqualTo(room)
    }

    @Test
    fun `an unavailable room remains readable by its id`() {
        val hotelId = createHotel().path("id").asText()
        val room = createRoom(hotelId, "Closed-12", available = false)

        val found = get("/api/rooms/${room.path("id").asText()}")

        assertRoom(found, hotelId, "Closed-12", false)
        assertThat(found.path("id")).isEqualTo(room.path("id"))
    }

    @Test
    fun `room update preserves identity hotel and booking count`() {
        val hotelId = createHotel().path("id").asText()
        val room = createRoom(hotelId, "101")
        val id = room.path("id").asText()

        val updated = call(HttpMethod.PUT, "/api/rooms/$id", roomUpdate("202-B", false))

        assertRoom(updated, hotelId, "202-B", false)
        assertThat(updated.path("id")).isEqualTo(room.path("id"))
        assertThat(get("/api/rooms/$id")).isEqualTo(updated)
    }

    @Test
    fun `USER cannot create a hotel and no row is inserted`() {
        val name = uniqueName()

        call(HttpMethod.POST, "/api/hotels", hotelInput(name, "Blocked Street"), "USER", 403, "FORBIDDEN")

        assertThat(jdbc.queryForObject("select count(*) from hotels where name = ?", Long::class.javaObjectType, name))
            .isEqualTo(0L)
    }

    @Test
    fun `USER cannot update a hotel and its stored fields stay unchanged`() {
        val original = createHotel()
        val path = "/api/hotels/${original.path("id").asText()}"

        call(HttpMethod.PUT, path, hotelInput("Unauthorized name", "Unauthorized address"), "USER", 403, "FORBIDDEN")

        assertThat(get(path)).isEqualTo(original)
    }

    @Test
    fun `USER cannot create a room and no row is inserted`() {
        val hotelId = createHotel().path("id").asText()

        call(HttpMethod.POST, "/api/rooms", mapOf("hotelId" to hotelId, "number" to "Forbidden"), "USER", 403, "FORBIDDEN")

        assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where hotel_id = ?",
                Long::class.javaObjectType,
                UUID.fromString(hotelId),
            ),
        ).isEqualTo(0L)
    }

    @Test
    fun `USER cannot update a room and its stored fields stay unchanged`() {
        val original = createRoom(createHotel().path("id").asText(), "Original")
        val path = "/api/rooms/${original.path("id").asText()}"

        call(HttpMethod.PUT, path, roomUpdate("Unauthorized", false), "USER", 403, "FORBIDDEN")

        assertThat(get(path)).isEqualTo(original)
    }

    @Test
    fun `hotel id cannot be supplied on creation or changed by update`() {
        val input = hotelInput(uniqueName(), "Address") + ("id" to UUID.randomUUID().toString())
        call(HttpMethod.POST, "/api/hotels", input, expectedStatus = 400, errorCode = "INVALID_REQUEST")
        val original = createHotel()
        val path = "/api/hotels/${original.path("id").asText()}"

        call(HttpMethod.PUT, path, input, expectedStatus = 400, errorCode = "INVALID_REQUEST")

        assertThat(get(path)).isEqualTo(original)
    }

    @ParameterizedTest
    @ValueSource(strings = ["id", "timesBooked"])
    fun `room creation rejects client supplied immutable fields`(field: String) {
        val hotelId = createHotel().path("id").asText()
        val value: Any = if (field == "timesBooked") 99 else UUID.randomUUID().toString()

        call(
            HttpMethod.POST,
            "/api/rooms",
            mapOf("hotelId" to hotelId, "number" to "Immutable", field to value),
            expectedStatus = 400,
            errorCode = "INVALID_REQUEST",
        )

        assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where hotel_id = ?",
                Long::class.javaObjectType,
                UUID.fromString(hotelId),
            ),
        ).isEqualTo(0L)
    }

    @ParameterizedTest
    @ValueSource(strings = ["id", "hotelId", "timesBooked"])
    fun `room update rejects immutable fields without changing stored values`(field: String) {
        val original = createRoom(createHotel().path("id").asText(), "Original")
        val path = "/api/rooms/${original.path("id").asText()}"
        val value: Any = if (field == "timesBooked") 99 else createHotel().path("id").asText()

        call(
            HttpMethod.PUT,
            path,
            roomUpdate("Changed", false) + (field to value),
            expectedStatus = 400,
            errorCode = "INVALID_REQUEST",
        )

        assertThat(get(path)).isEqualTo(original)
    }

    @Test
    fun `duplicate room number in one hotel is rejected without replacing the original`() {
        val hotelId = createHotel().path("id").asText()
        val original = createRoom(hotelId, "101")

        call(
            HttpMethod.POST,
            "/api/rooms",
            mapOf("hotelId" to hotelId, "number" to "101", "available" to false),
            expectedStatus = 409,
            errorCode = "ROOM_NUMBER_TAKEN",
        )

        assertThat(get("/api/rooms/${original.path("id").asText()}")).isEqualTo(original)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where hotel_id = ?",
                Long::class.javaObjectType,
                UUID.fromString(hotelId),
            ),
        ).isEqualTo(1L)
    }

    @Test
    fun `duplicate number update is rejected without partially changing availability`() {
        val hotelId = createHotel().path("id").asText()
        createRoom(hotelId, "101")
        val original = createRoom(hotelId, "102")
        val path = "/api/rooms/${original.path("id").asText()}"

        call(HttpMethod.PUT, path, roomUpdate("101", false), expectedStatus = 409, errorCode = "ROOM_NUMBER_TAKEN")

        assertThat(get(path)).isEqualTo(original)
    }

    @Test
    fun `different hotels may use the same room number`() {
        val firstHotelId = createHotel().path("id").asText()
        val secondHotelId = createHotel().path("id").asText()

        val first = createRoom(firstHotelId, "101")
        val second = createRoom(secondHotelId, "101")

        assertThat(second.path("id")).isNotEqualTo(first.path("id"))
        assertRoom(get("/api/rooms/${first.path("id").asText()}"), firstHotelId, "101", true)
        assertRoom(get("/api/rooms/${second.path("id").asText()}"), secondHotelId, "101", true)
    }

    @Test
    fun `missing hotels and rooms return their distinct not found errors`() {
        val missingId = UUID.randomUUID().toString()
        call(HttpMethod.GET, "/api/hotels/$missingId", expectedStatus = 404, errorCode = "HOTEL_NOT_FOUND")
        call(
            HttpMethod.PUT,
            "/api/hotels/$missingId",
            hotelInput("Missing", "Address"),
            expectedStatus = 404,
            errorCode = "HOTEL_NOT_FOUND",
        )
        call(HttpMethod.GET, "/api/rooms/$missingId", expectedStatus = 404, errorCode = "ROOM_NOT_FOUND")
        call(HttpMethod.PUT, "/api/rooms/$missingId", roomUpdate("101", true), expectedStatus = 404, errorCode = "ROOM_NOT_FOUND")
        call(
            HttpMethod.POST,
            "/api/rooms",
            mapOf("hotelId" to missingId, "number" to "101"),
            expectedStatus = 404,
            errorCode = "HOTEL_NOT_FOUND",
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidHotels")
    fun `hotel validation applies to creation and update`(
        description: String,
        name: String,
        address: String,
    ) {
        val input = hotelInput(name, address)
        call(HttpMethod.POST, "/api/hotels", input, expectedStatus = 400, errorCode = "INVALID_CATALOG_INPUT")
        val original = createHotel()
        val path = "/api/hotels/${original.path("id").asText()}"

        call(HttpMethod.PUT, path, input, expectedStatus = 400, errorCode = "INVALID_CATALOG_INPUT")

        assertThat(get(path)).describedAs(description).isEqualTo(original)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"])
    fun `room number validation applies to creation and update`(number: String) {
        val hotelId = createHotel().path("id").asText()
        call(
            HttpMethod.POST,
            "/api/rooms",
            mapOf("hotelId" to hotelId, "number" to number),
            expectedStatus = 400,
            errorCode = "INVALID_CATALOG_INPUT",
        )
        val original = createRoom(hotelId, "Valid")
        val path = "/api/rooms/${original.path("id").asText()}"

        call(HttpMethod.PUT, path, roomUpdate(number, false), expectedStatus = 400, errorCode = "INVALID_CATALOG_INPUT")

        assertThat(get(path)).isEqualTo(original)
    }

    @Test
    fun `field values at maximum lengths are accepted`() {
        val hotel = createHotel("N".repeat(200), "A".repeat(500))
        val hotelId = hotel.path("id").asText()
        val room = createRoom(hotelId, "R".repeat(50))

        assertHotel(get("/api/hotels/$hotelId"), "N".repeat(200), "A".repeat(500))
        assertRoom(get("/api/rooms/${room.path("id").asText()}"), hotelId, "R".repeat(50), true)
    }

    @Test
    fun `required hotel and room input fields cannot be omitted`() {
        call(HttpMethod.POST, "/api/hotels", mapOf("name" to "Only name"), expectedStatus = 400, errorCode = "INVALID_REQUEST")
        call(HttpMethod.POST, "/api/hotels", mapOf("address" to "Only address"), expectedStatus = 400, errorCode = "INVALID_REQUEST")
        val hotelId = createHotel().path("id").asText()
        call(HttpMethod.POST, "/api/rooms", mapOf("hotelId" to hotelId), expectedStatus = 400, errorCode = "INVALID_REQUEST")
        call(HttpMethod.POST, "/api/rooms", mapOf("number" to "101"), expectedStatus = 400, errorCode = "INVALID_REQUEST")
        val roomId = createRoom(hotelId, "101").path("id").asText()
        call(HttpMethod.PUT, "/api/rooms/$roomId", mapOf("number" to "102"), expectedStatus = 400, errorCode = "INVALID_REQUEST")
    }

    @Test
    fun `hotel filters are case insensitive substrings combined before stable pagination`() {
        val namespace = uniqueName()
        val cedar = seedHotel(3, "$namespace Cedar", "North $namespace Ridge")
        val laterAspen = seedHotel(4, "$namespace Aspen", "NORTH $namespace Shore")
        val earlierAspen = seedHotel(1, "$namespace Aspen", "North $namespace Bay")
        val birch = seedHotel(2, "$namespace Birch", "South $namespace Ridge")
        val other = seedHotel(5, "Other hotel", "North $namespace Decoy")

        val first = hotelPage(mapOf("name" to namespace.uppercase(), "address" to "nOrTh", "page" to "0", "size" to "2"))
        val second = hotelPage(mapOf("name" to namespace.uppercase(), "address" to "nOrTh", "page" to "1", "size" to "2"))
        val beyond = hotelPage(mapOf("name" to namespace.uppercase(), "address" to "nOrTh", "page" to "9", "size" to "2"))
        val nameOnly = hotelPage(mapOf("name" to namespace.uppercase()))
        val addressOnly = hotelPage(mapOf("address" to "north $namespace".uppercase()))

        assertPage(first, listOf(earlierAspen, laterAspen), 0, 2, 3, 2)
        assertPage(second, listOf(cedar), 1, 2, 3, 2)
        assertPage(beyond, emptyList(), 9, 2, 3, 2)
        assertPage(nameOnly, listOf(earlierAspen, laterAspen, birch, cedar), 0, 20, 4, 1)
        assertPage(addressOnly, listOf(other, earlierAspen, laterAspen, cedar), 0, 20, 4, 1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["page=-1", "size=0", "size=-1"])
    fun `hotel pagination rejects negative page and nonpositive size`(query: String) {
        val (key, value) = query.split('=')
        hotelPage(mapOf(key to value), 400, "INVALID_CATALOG_INPUT")
    }

    private fun createHotel(
        name: String = uniqueName(),
        address: String = "Default Street 1",
    ): JsonNode = call(HttpMethod.POST, "/api/hotels", hotelInput(name, address), expectedStatus = 201)

    private fun createRoom(
        hotelId: String,
        number: String,
        available: Boolean? = null,
    ): JsonNode {
        val input = mutableMapOf<String, Any>("hotelId" to hotelId, "number" to number)
        if (available != null) input["available"] = available
        return call(HttpMethod.POST, "/api/rooms", input, expectedStatus = 201)
    }

    private fun get(
        path: String,
        role: String = "USER",
    ): JsonNode = call(HttpMethod.GET, path, role = role)

    private fun hotelPage(
        parameters: Map<String, String>,
        expectedStatus: Int = 200,
        errorCode: String? = null,
    ): JsonNode =
        call(HttpMethod.GET, "/api/hotels", role = "USER", expectedStatus = expectedStatus, errorCode = errorCode, parameters = parameters)

    private fun call(
        method: HttpMethod,
        path: String,
        payload: Map<String, Any?>? = null,
        role: String = "ADMIN",
        expectedStatus: Int = 200,
        errorCode: String? = null,
        parameters: Map<String, String> = emptyMap(),
    ): JsonNode {
        val builder = request(method, path).header("Authorization", "Bearer ${jwks.userToken(role)}").header("X-Trace-Id", "catalog-test")
        parameters.forEach { (key, value) -> builder.param(key, value) }
        if (payload != null) builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload))
        val response = mockMvc.perform(builder).andReturn().response
        assertThat(response.status).describedAs("$method $path").isEqualTo(expectedStatus)
        assertThat(response.contentType).startsWith("application/json")
        val body = mapper.readTree(response.contentAsByteArray)
        if (errorCode != null) {
            assertThat(body.path("code").asText()).isEqualTo(errorCode)
            assertThat(body.path("message").asText()).isNotBlank()
            assertThat(body.path("traceId").asText()).isEqualTo("catalog-test")
            assertThat(response.getHeader("X-Trace-Id")).isEqualTo("catalog-test")
        }
        return body
    }

    private fun assertHotel(
        hotel: JsonNode,
        name: String,
        address: String,
    ) {
        UUID.fromString(hotel.path("id").asText())
        assertThat(hotel.path("name").asText()).isEqualTo(name)
        assertThat(hotel.path("address").asText()).isEqualTo(address)
        assertThat(hotel.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "name", "address")
    }

    private fun assertRoom(
        room: JsonNode,
        hotelId: String,
        number: String,
        available: Boolean,
    ) {
        UUID.fromString(room.path("id").asText())
        assertThat(room.path("hotelId").asText()).isEqualTo(hotelId)
        assertThat(room.path("number").asText()).isEqualTo(number)
        assertThat(room.path("available").isBoolean).isTrue()
        assertThat(room.path("available").asBoolean()).isEqualTo(available)
        assertThat(room.path("timesBooked").isIntegralNumber).isTrue()
        assertThat(room.path("timesBooked").asLong()).isZero()
        assertThat(room.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "hotelId", "number", "available", "timesBooked")
    }

    private fun seedHotel(
        suffix: Int,
        name: String,
        address: String,
    ): JsonNode {
        val id = UUID.fromString("00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}")
        jdbc.update("insert into hotels(id, name, address) values (?, ?, ?)", id, name, address)
        return mapper.valueToTree(mapOf("id" to id.toString(), "name" to name, "address" to address))
    }

    private fun assertPage(
        page: JsonNode,
        expectedItems: List<JsonNode>,
        expectedPage: Int,
        expectedSize: Int,
        totalElements: Long,
        totalPages: Int,
    ) {
        assertThat(page.path("items").isArray).isTrue()
        assertThat(page.path("items").toList()).containsExactlyElementsOf(expectedItems)
        assertThat(page.path("page").asInt()).isEqualTo(expectedPage)
        assertThat(page.path("size").asInt()).isEqualTo(expectedSize)
        assertThat(page.path("totalElements").asLong()).isEqualTo(totalElements)
        assertThat(page.path("totalPages").asInt()).isEqualTo(totalPages)
    }

    private fun hotelInput(
        name: String,
        address: String,
    ): Map<String, Any> = mapOf("name" to name, "address" to address)

    private fun roomUpdate(
        number: String,
        available: Boolean,
    ): Map<String, Any> = mapOf("number" to number, "available" to available)

    private fun uniqueName(): String = "catalog-${UUID.randomUUID()}"

    companion object {
        private val jwks = JwtTestServer()

        @JvmStatic
        // Invoked reflectively by the Spring test context.
        @Suppress("unused")
        @DynamicPropertySource
        fun securityProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.security.jwk-set-uri") { jwks.uri }
        }

        @JvmStatic
        @AfterAll
        fun stopJwksServer() {
            jwks.close()
        }

        @JvmStatic
        fun invalidHotels(): Stream<Arguments> =
            Stream.of(
                Arguments.of("empty name", "", "Valid address"),
                Arguments.of("blank name", "   ", "Valid address"),
                Arguments.of("name exceeds 200 characters", "N".repeat(201), "Valid address"),
                Arguments.of("empty address", "Valid name", ""),
                Arguments.of("blank address", "Valid name", "   "),
                Arguments.of("address exceeds 500 characters", "Valid name", "A".repeat(501)),
            )
    }
}
