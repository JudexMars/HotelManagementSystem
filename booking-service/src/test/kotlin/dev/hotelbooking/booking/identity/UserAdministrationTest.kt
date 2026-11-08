package dev.hotelbooking.booking.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.booking.identity.api.AuthorizedUserAdministrationService
import dev.hotelbooking.booking.identity.domain.Credentials
import dev.hotelbooking.booking.identity.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.stream.Stream

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.profiles.active=dev",
        "booking.demo.admin.username=demo-admin",
        "booking.demo.admin.password=demo-admin-password",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class UserAdministrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var administration: AuthorizedUserAdministrationService

    private lateinit var adminToken: String

    @BeforeEach
    fun authenticateDevelopmentAdmin() {
        adminToken = login("demo-admin", "demo-admin-password").path("accessToken").asText()
    }

    @ParameterizedTest
    @EnumSource(Role::class)
    fun `ADMIN creates and reads an account with the requested role without exposing its password`(role: Role) {
        val username = uniqueUsername()

        val created = createUser(username, "created-password", role)
        val id = created.path("id").asText()

        UUID.fromString(id)
        assertUser(created, id, username, role)
        assertUser(readUser(id), id, username, role)
        val authenticated = login(username, "created-password")
        assertUser(authenticated.path("user"), id, username, role)
    }

    @Test
    fun `ADMIN pagination returns every active user once with correct metadata and defaults`() {
        repeat(3) { createUser(uniqueUsername(), "created-password", Role.USER) }
        val expectedIds =
            jdbcTemplate
                .queryForList(
                    "select id from users where deleted = false",
                    UUID::class.java,
                ).map(UUID::toString)
        val defaultPage = json(perform(HttpMethod.GET, "/api/users", 200))
        assertThat(defaultPage.path("page").asInt()).isZero()
        assertThat(defaultPage.path("size").asInt()).isEqualTo(20)
        assertThat(defaultPage.path("totalElements").asInt()).isEqualTo(expectedIds.size)
        assertThat(defaultPage.path("totalPages").asInt()).isEqualTo((expectedIds.size + 19) / 20)

        val totalPages = (expectedIds.size + 1) / 2
        val actualIds =
            (0 until totalPages).flatMap { pageNumber ->
                val page = json(perform(HttpMethod.GET, "/api/users?page=$pageNumber&size=2", 200))
                assertThat(page.path("page").asInt()).isEqualTo(pageNumber)
                assertThat(page.path("size").asInt()).isEqualTo(2)
                assertThat(page.path("totalElements").asInt()).isEqualTo(expectedIds.size)
                assertThat(page.path("totalPages").asInt()).isEqualTo(totalPages)
                val items = page.path("items")
                assertThat(items.isArray).isTrue()
                assertThat(items.size()).isEqualTo(minOf(2, expectedIds.size - pageNumber * 2))
                items.map { user ->
                    assertThat(user.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "username", "role")
                    user.path("id").asText()
                }
            }
        assertThat(actualIds).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expectedIds)
        val beyondEnd = json(perform(HttpMethod.GET, "/api/users?page=$totalPages&size=2", 200))
        assertThat(beyondEnd.path("items").size()).isZero()
        assertThat(beyondEnd.path("totalElements").asInt()).isEqualTo(expectedIds.size)
    }

    @Test
    fun `ADMIN updates username password and role while preserving the account ID`() {
        val username = uniqueUsername()
        val created = createUser(username, "original-password", Role.USER)
        val id = created.path("id").asText()
        val replacementUsername = uniqueUsername()

        val updated =
            json(
                perform(
                    HttpMethod.PUT,
                    "/api/users/$id",
                    200,
                    mapOf("username" to replacementUsername, "password" to "replacement-password", "role" to "ADMIN"),
                ),
            )

        assertUser(updated, id, replacementUsername, Role.ADMIN)
        assertUser(readUser(id), id, replacementUsername, Role.ADMIN)
        assertUser(login(replacementUsername, "replacement-password").path("user"), id, replacementUsername, Role.ADMIN)
        login(username, "original-password", 401)
        login(replacementUsername, "original-password", 401)
        val stored = storedUser(id)
        assertThat(stored["id"]).isEqualTo(UUID.fromString(id))
        assertThat(stored["username"]).isEqualTo(replacementUsername)
        assertThat(stored["role"]).isEqualTo("ADMIN")
        val hash = stored["password"] as String
        assertThat(hash).isNotEqualTo("replacement-password")
        assertThat(BCrypt.checkpw("replacement-password", hash)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `omitting or nulling the password keeps the existing login password`(explicitNull: Boolean) {
        val username = uniqueUsername()
        val id = createUser(username, "original-password", Role.USER).path("id").asText()
        val replacementUsername = uniqueUsername()
        val payload = mutableMapOf<String, Any?>("username" to replacementUsername, "role" to "USER")
        if (explicitNull) payload["password"] = null

        val updated = json(perform(HttpMethod.PUT, "/api/users/$id", 200, payload))

        assertUser(updated, id, replacementUsername, Role.USER)
        assertUser(login(replacementUsername, "original-password").path("user"), id, replacementUsername, Role.USER)
    }

    @Test
    fun `a client supplied ID is rejected on creation and update without changing the account`() {
        val username = uniqueUsername()
        val id = createUser(username, "original-password", Role.USER).path("id").asText()
        val before = storedUser(id)
        val proposedId = UUID.randomUUID().toString()
        val proposedUsername = uniqueUsername()
        val payload =
            mapOf(
                "id" to proposedId,
                "username" to proposedUsername,
                "password" to "replacement-password",
                "role" to "ADMIN",
            )

        perform(HttpMethod.PUT, "/api/users/$id", 400, payload)
        perform(HttpMethod.POST, "/api/users", 400, payload)

        assertThat(storedUser(id)).isEqualTo(before)
        assertThat(countUsername(proposedUsername)).isZero()
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from users where id = ?", Long::class.javaObjectType, UUID.fromString(proposedId)),
        ).isEqualTo(0L)
    }

    @Test
    fun `deletion retains the database account and permanently reserves its username`() {
        val username = uniqueUsername()
        val id = createUser(username, "original-password", Role.USER).path("id").asText()

        perform(HttpMethod.DELETE, "/api/users/$id", 204)

        val retained = storedUser(id)
        assertThat(retained["id"]).isEqualTo(UUID.fromString(id))
        assertThat(retained["username"]).isEqualTo(username)
        assertThat(retained["deleted"]).isEqualTo(true)
        assertThat(countUsername(username)).isEqualTo(1L)
        perform(HttpMethod.GET, "/api/users/$id", 404)
        perform(HttpMethod.DELETE, "/api/users/$id", 404)
        login(username, "original-password", 401)
        perform(HttpMethod.POST, "/api/users", 409, accountPayload(username, "replacement-password", Role.USER))
        perform(
            HttpMethod.POST,
            "/api/users/register",
            409,
            mapOf("username" to username, "password" to "replacement-password"),
            token = null,
        )
        assertThat(storedUser(id)).isEqualTo(retained)
        val listedIds = activeUsersFromApi().map { it.path("id").asText() }
        assertThat(listedIds).doesNotContain(id)
    }

    @Test
    fun `a duplicate username cannot overwrite either account during creation or update`() {
        val firstUsername = uniqueUsername()
        val firstId = createUser(firstUsername, "first-password", Role.USER).path("id").asText()
        val secondUsername = uniqueUsername()
        val secondId = createUser(secondUsername, "second-password", Role.ADMIN).path("id").asText()
        val firstBefore = storedUser(firstId)
        val secondBefore = storedUser(secondId)

        perform(HttpMethod.POST, "/api/users", 409, accountPayload(firstUsername, "replacement-password", Role.ADMIN))
        perform(HttpMethod.PUT, "/api/users/$secondId", 409, accountPayload(firstUsername, "replacement-password", Role.USER))

        assertThat(countUsername(firstUsername)).isEqualTo(1L)
        assertThat(storedUser(firstId)).isEqualTo(firstBefore)
        assertThat(storedUser(secondId)).isEqualTo(secondBefore)
    }

    @Test
    fun `USER is denied every administrator operation without database effects`() {
        val username = uniqueUsername()
        createUser(username, "user-password", Role.USER)
        val userToken = login(username, "user-password").path("accessToken").asText()
        val targetUsername = uniqueUsername()
        val targetId = createUser(targetUsername, "target-password", Role.USER).path("id").asText()
        val before = storedUser(targetId)
        val forbiddenUsername = uniqueUsername()

        perform(HttpMethod.GET, "/api/users", 403, token = userToken)
        perform(HttpMethod.GET, "/api/users/$targetId", 403, token = userToken)
        perform(HttpMethod.POST, "/api/users", 403, accountPayload(forbiddenUsername, "replacement-password", Role.ADMIN), userToken)
        perform(
            HttpMethod.PUT,
            "/api/users/$targetId",
            403,
            accountPayload(forbiddenUsername, "replacement-password", Role.ADMIN),
            userToken,
        )
        perform(HttpMethod.DELETE, "/api/users/$targetId", 403, token = userToken)

        assertThat(storedUser(targetId)).isEqualTo(before)
        assertThat(countUsername(forbiddenUsername)).isZero()
    }

    @Test
    fun `an issued ADMIN token keeps its authority after demotion password change and deletion`() {
        val username = uniqueUsername()
        val id = createUser(username, "original-password", Role.ADMIN).path("id").asText()
        val oldAdminToken = login(username, "original-password").path("accessToken").asText()

        perform(HttpMethod.PUT, "/api/users/$id", 200, accountPayload(username, "replacement-password", Role.USER))

        perform(HttpMethod.GET, "/api/users", 200, token = oldAdminToken)
        val freshLogin = login(username, "replacement-password")
        assertThat(freshLogin.path("user").path("role").asText()).isEqualTo("USER")
        perform(HttpMethod.GET, "/api/users", 403, token = freshLogin.path("accessToken").asText())
        login(username, "original-password", 401)

        perform(HttpMethod.DELETE, "/api/users/$id", 204)

        perform(HttpMethod.GET, "/api/users", 200, token = oldAdminToken)
        login(username, "replacement-password", 401)
        assertThat(storedUser(id)["deleted"]).isEqualTo(true)
    }

    @Test
    fun `unknown account updates and reads return not found without creating an account`() {
        val id = UUID.randomUUID().toString()
        val username = uniqueUsername()

        perform(HttpMethod.GET, "/api/users/$id", 404)
        perform(HttpMethod.PUT, "/api/users/$id", 404, accountPayload(username, "created-password", Role.USER))
        perform(HttpMethod.DELETE, "/api/users/$id", 404)

        assertThat(countUsername(username)).isZero()
    }

    @Test
    fun `direct service calls cannot let USER create or delete accounts`() {
        val targetId = createUser(uniqueUsername(), "target-password", Role.USER).path("id").asText()
        val before = storedUser(targetId)
        val forbiddenUsername = uniqueUsername()
        val userContext = SecurityContextHolder.createEmptyContext()
        userContext.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "direct-user",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.setContext(userContext)
        try {
            assertThatThrownBy { administration.create(Credentials(forbiddenUsername, "created-password"), Role.ADMIN) }
                .isInstanceOf(AccessDeniedException::class.java)
            assertThatThrownBy { administration.delete(UUID.fromString(targetId)) }
                .isInstanceOf(AccessDeniedException::class.java)
        } finally {
            SecurityContextHolder.clearContext()
        }

        assertThat(countUsername(forbiddenUsername)).isZero()
        assertThat(storedUser(targetId)).isEqualTo(before)
    }

    @ParameterizedTest
    @CsvSource("-1,20", "0,0", "0,101", "2147483647,2")
    fun `invalid pagination including an overflowing offset returns bad request`(
        page: Int,
        size: Int,
    ) {
        val error = json(perform(HttpMethod.GET, "/api/users?page=$page&size=$size", 400))

        assertThat(error.path("code").asText()).isEqualTo("INVALID_PAGE")
    }

    @ParameterizedTest
    @MethodSource("invalidUsernames")
    fun `ADMIN cannot create or rename an account with an invalid username`(username: String) {
        val targetId = createUser(uniqueUsername(), "original-password", Role.USER).path("id").asText()
        val before = storedUser(targetId)

        perform(HttpMethod.POST, "/api/users", 400, accountPayload(username, "replacement-password", Role.ADMIN))
        perform(HttpMethod.PUT, "/api/users/$targetId", 400, accountPayload(username, "replacement-password", Role.ADMIN))

        assertThat(countUsername(username)).isZero()
        assertThat(storedUser(targetId)).isEqualTo(before)
    }

    @Test
    fun `ADMIN can create and rename an account at the 100 character username limit`() {
        val username = uniqueUsername().padEnd(100, 'x')
        val created = createUser(username, "original-password", Role.USER)
        val id = created.path("id").asText()
        assertUser(created, id, username, Role.USER)
        val replacementUsername = uniqueUsername().padEnd(100, 'y')

        val updated =
            json(
                perform(
                    HttpMethod.PUT,
                    "/api/users/$id",
                    200,
                    mapOf("username" to replacementUsername, "role" to "USER"),
                ),
            )

        assertUser(updated, id, replacementUsername, Role.USER)
        assertUser(login(replacementUsername, "original-password").path("user"), id, replacementUsername, Role.USER)
    }

    @ParameterizedTest(name = "invalid admin password case {index}")
    @MethodSource("dev.hotelbooking.booking.identity.IdentityApiTest#invalidPasswords")
    fun `ADMIN create and update reject passwords outside the UTF-8 byte limits`(password: String) {
        val targetId = createUser(uniqueUsername(), "original-password", Role.USER).path("id").asText()
        val before = storedUser(targetId)
        val proposedUsername = uniqueUsername()

        perform(HttpMethod.POST, "/api/users", 400, accountPayload(proposedUsername, password, Role.ADMIN))
        perform(HttpMethod.PUT, "/api/users/$targetId", 400, accountPayload(proposedUsername, password, Role.ADMIN))

        assertThat(countUsername(proposedUsername)).isZero()
        assertThat(storedUser(targetId)).isEqualTo(before)
    }

    @ParameterizedTest(name = "valid admin password boundary {index}")
    @MethodSource("dev.hotelbooking.booking.identity.IdentityApiTest#validBoundaryPasswords")
    fun `ADMIN create and password replacement accept the UTF-8 byte boundaries`(password: String) {
        val username = uniqueUsername()
        val id = createUser(username, password, Role.USER).path("id").asText()
        assertUser(login(username, password).path("user"), id, username, Role.USER)
        val targetUsername = uniqueUsername()
        val targetId = createUser(targetUsername, "original-password", Role.USER).path("id").asText()

        perform(HttpMethod.PUT, "/api/users/$targetId", 200, accountPayload(targetUsername, password, Role.USER))

        assertUser(login(targetUsername, password).path("user"), targetId, targetUsername, Role.USER)
        login(targetUsername, "original-password", 401)
    }

    private fun activeUsersFromApi(): List<JsonNode> {
        val firstPage = json(perform(HttpMethod.GET, "/api/users?page=0&size=20", 200))
        val pages = firstPage.path("totalPages").asInt()
        return (0 until pages).flatMap { page ->
            val response = if (page == 0) firstPage else json(perform(HttpMethod.GET, "/api/users?page=$page&size=20", 200))
            response.path("items").toList()
        }
    }

    private fun createUser(
        username: String,
        password: String,
        role: Role,
    ): JsonNode = json(perform(HttpMethod.POST, "/api/users", 201, accountPayload(username, password, role)))

    private fun readUser(id: String): JsonNode = json(perform(HttpMethod.GET, "/api/users/$id", 200))

    private fun login(
        username: String,
        password: String,
        expectedStatus: Int = 200,
    ): JsonNode =
        json(
            perform(
                HttpMethod.POST,
                "/api/users/auth",
                expectedStatus,
                mapOf("username" to username, "password" to password),
                token = null,
            ),
        )

    private fun accountPayload(
        username: String,
        password: String,
        role: Role,
    ): Map<String, Any?> = mapOf("username" to username, "password" to password, "role" to role.name)

    private fun perform(
        method: HttpMethod,
        path: String,
        expectedStatus: Int,
        payload: Map<String, Any?>? = null,
        token: String? = adminToken,
    ): MvcResult {
        val request = MockMvcRequestBuilders.request(method, path)
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        if (payload != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(payload))
        return mockMvc.perform(request).andExpect(status().`is`(expectedStatus)).andReturn()
    }

    private fun json(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsByteArray)

    private fun storedUser(id: String): Map<String, Any> = jdbcTemplate.queryForMap("select * from users where id = ?", UUID.fromString(id))

    private fun countUsername(username: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject("select count(*) from users where username = ?", Long::class.javaObjectType, username),
        )

    private fun assertUser(
        user: JsonNode,
        id: String,
        username: String,
        role: Role,
    ) {
        assertThat(user.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "username", "role")
        assertThat(user.path("id").asText()).isEqualTo(id)
        assertThat(user.path("username").asText()).isEqualTo(username)
        assertThat(user.path("role").asText()).isEqualTo(role.name)
    }

    private fun uniqueUsername(): String = "user-${UUID.randomUUID()}"

    companion object {
        @JvmStatic
        fun invalidUsernames(): Stream<String> = Stream.of("", " ", "user name", "user\tname", "u".repeat(101))
    }
}
