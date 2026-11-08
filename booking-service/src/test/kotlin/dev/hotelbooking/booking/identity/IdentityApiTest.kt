package dev.hotelbooking.booking.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
class IdentityApiTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `registration creates a USER and issues an independently verifiable one hour token`() {
        val username = uniqueUsername()
        val beforeRegistration = Instant.now().epochSecond

        val registered = register(username, "correct-password")

        assertTokenResponse(registered, username, "USER")
        assertSignedClaims(registered, "USER", beforeRegistration)
    }

    @Test
    fun `registration persists a BCrypt hash that verifies the supplied password`() {
        val username = uniqueUsername()
        val password = "correct-password"

        register(username, password)

        val storedHash =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "select password from users where username = ?",
                    String::class.java,
                    username,
                ),
            )
        assertThat(storedHash).isNotEqualTo(password)
        assertThat(BCrypt.checkpw(password, storedHash)).isTrue()
    }

    @Test
    fun `registered credentials authenticate the same user`() {
        val username = uniqueUsername()
        val registered = register(username, "correct-password")
        val beforeAuthentication = Instant.now().epochSecond

        val authenticated = authenticate(username, "correct-password", 200)

        assertTokenResponse(authenticated, username, "USER")
        assertThat(authenticated.path("user").path("id")).isEqualTo(registered.path("user").path("id"))
        assertSignedClaims(authenticated, "USER", beforeAuthentication)
    }

    @Test
    fun `wrong password is rejected without issuing a token`() {
        val username = uniqueUsername()
        register(username, "correct-password")

        val error = authenticate(username, "wrong-password", 401)

        assertThat(error.has("accessToken")).isFalse()
    }

    @Test
    fun `unknown username is rejected without issuing a token`() {
        val error = authenticate(uniqueUsername(), "correct-password", 401)

        assertThat(error.has("accessToken")).isFalse()
    }

    @Test
    fun `duplicate registration cannot replace the original password`() {
        val username = uniqueUsername()
        val original = register(username, "original-password")

        request("/api/users/register", credentials(username, "replacement-password"), 409)

        val authenticated = authenticate(username, "original-password", 200)
        assertThat(authenticated.path("user").path("id")).isEqualTo(original.path("user").path("id"))
        authenticate(username, "replacement-password", 401)
    }

    @Test
    fun `concurrent registrations of the same username create exactly one account`() {
        val username = uniqueUsername()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val responses =
                (1..2).map {
                    executor.submit<Int> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        mockMvc
                            .perform(
                                post("/api/users/register")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsBytes(credentials(username, "correct-password"))),
                            ).andReturn()
                            .response.status
                    }
                }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            assertThat(responses.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(201, 409)
            val accountCount =
                jdbcTemplate.queryForObject(
                    "select count(*) from users where username = ?",
                    Long::class.javaObjectType,
                    username,
                )
            assertThat(accountCount).isEqualTo(1L)
            assertTokenResponse(authenticate(username, "correct-password", 200), username, "USER")
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "user name", " user", "user ", "user\tname", "user\nname"])
    fun `registration rejects empty usernames and whitespace`(username: String) {
        request("/api/users/register", credentials(username, "correct-password"), 400)
    }

    @Test
    fun `registration rejects usernames longer than 100 characters`() {
        request("/api/users/register", credentials("u".repeat(101), "correct-password"), 400)
    }

    @Test
    fun `registration accepts a username of exactly 100 characters`() {
        val username = uniqueUsername().padEnd(100, 'x')

        assertTokenResponse(register(username, "correct-password"), username, "USER")
    }

    @ParameterizedTest(name = "invalid password case {index}")
    @MethodSource("invalidPasswords")
    fun `registration validates password UTF-8 byte length`(password: String) {
        request("/api/users/register", credentials(uniqueUsername(), password), 400)
    }

    @ParameterizedTest(name = "valid password boundary {index}")
    @MethodSource("validBoundaryPasswords")
    fun `passwords at UTF-8 byte boundaries register and authenticate`(password: String) {
        val username = uniqueUsername()
        val registered = register(username, password)

        val authenticated = authenticate(username, password, 200)

        assertThat(authenticated.path("user").path("id")).isEqualTo(registered.path("user").path("id"))
    }

    @Test
    fun `registration cannot grant ADMIN from a client supplied role`() {
        val username = uniqueUsername()
        request(
            "/api/users/register",
            mapOf("username" to username, "password" to "correct-password", "role" to "ADMIN"),
            400,
        )

        authenticate(username, "correct-password", 401)
    }

    @Test
    fun `registration rejects missing credentials`() {
        request("/api/users/register", mapOf("username" to uniqueUsername()), 400)
        request("/api/users/register", mapOf("password" to "correct-password"), 400)
    }

    @Test
    fun `development bootstrap credentials issue an ADMIN user token`() {
        val beforeAuthentication = Instant.now().epochSecond

        val authenticated = authenticate("demo-admin", "demo-admin-password", 200)

        assertTokenResponse(authenticated, "demo-admin", "ADMIN")
        assertSignedClaims(authenticated, "ADMIN", beforeAuthentication)
    }

    @Test
    fun `public JWKS exposes verification keys without private key material`() {
        val keys = jwks().path("keys")

        assertThat(keys.isArray).isTrue()
        assertThat(keys.size()).isGreaterThan(0)
        keys.forEach { key ->
            assertThat(key.path("kty").asText()).isEqualTo("RSA")
            assertThat(key.path("kid").asText()).isNotBlank()
            assertThat(key.path("n").asText()).isNotBlank()
            assertThat(key.path("e").asText()).isNotBlank()
            assertThat(key.fieldNames().asSequence().toList())
                .doesNotContain("d", "p", "q", "dp", "dq", "qi", "oth", "k")
        }
    }

    private fun assertTokenResponse(
        response: JsonNode,
        username: String,
        role: String,
    ) {
        assertThat(response.path("accessToken").isTextual).isTrue()
        assertThat(response.path("accessToken").asText().isNotBlank()).isTrue()
        assertThat(response.path("tokenType").asText()).isEqualTo("Bearer")
        assertThat(response.path("expiresIn").asLong()).isEqualTo(3600)
        val user = response.path("user")
        assertThat(user.path("id").asText()).isNotBlank()
        assertThat(user.path("username").asText()).isEqualTo(username)
        assertThat(user.path("role").asText()).isEqualTo(role)
        assertThat(user.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "username", "role")
        assertThat(response.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "user")
    }

    private fun assertSignedClaims(
        response: JsonNode,
        role: String,
        issuedAfter: Long,
    ) {
        val tokenParts = response.path("accessToken").asText().split('.')
        assertThat(tokenParts.size).isEqualTo(3)
        val decoder = Base64.getUrlDecoder()
        val header = objectMapper.readTree(decoder.decode(tokenParts[0]))
        assertThat(header.path("alg").asText()).isEqualTo("RS256")
        assertThat(header.path("kid").asText()).isNotBlank()
        val key = jwks().path("keys").single { it.path("kid").asText() == header.path("kid").asText() }
        val publicKey =
            KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(
                    BigInteger(1, decoder.decode(key.path("n").asText())),
                    BigInteger(1, decoder.decode(key.path("e").asText())),
                ),
            )
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${tokenParts[0]}.${tokenParts[1]}".toByteArray(StandardCharsets.US_ASCII))
        assertThat(signature.verify(decoder.decode(tokenParts[2])))
            .describedAs("JWT signature is verifiable with the advertised public key")
            .isTrue()

        val claims = objectMapper.readTree(decoder.decode(tokenParts[1]))
        assertThat(claims.path("sub").asText()).isEqualTo(response.path("user").path("id").asText())
        assertThat(claims.path("iss").asText()).isEqualTo("hotel-booking-system")
        val audience = claims.path("aud")
        val audiences = if (audience.isArray) audience.map { it.asText() } else listOf(audience.asText())
        assertThat(audiences).containsExactly("hotel-booking-api")
        assertThat(claims.path("iat").asLong()).isBetween(issuedAfter, Instant.now().epochSecond)
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(3600)
        assertThat(claims.path("roles").map { it.asText() }).containsExactly(role)
        assertThat(claims.has("scope")).isFalse()
        assertThat(claims.has("scp")).isFalse()
    }

    private fun register(
        username: String,
        password: String,
    ): JsonNode = request("/api/users/register", credentials(username, password), 201)

    private fun authenticate(
        username: String,
        password: String,
        expectedStatus: Int,
    ): JsonNode = request("/api/users/auth", credentials(username, password), expectedStatus)

    private fun request(
        path: String,
        payload: Map<String, String>,
        expectedStatus: Int,
    ): JsonNode {
        val result =
            mockMvc
                .perform(
                    post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)),
                ).andExpect(status().`is`(expectedStatus))
                .andReturn()
        return objectMapper.readTree(result.response.contentAsByteArray)
    }

    private fun jwks(): JsonNode {
        val result =
            mockMvc
                .perform(get("/api/users/jwks"))
                .andExpect(status().isOk)
                .andReturn()
        return objectMapper.readTree(result.response.contentAsByteArray)
    }

    private fun credentials(
        username: String,
        password: String,
    ): Map<String, String> = mapOf("username" to username, "password" to password)

    private fun uniqueUsername(): String = "user-${UUID.randomUUID()}"

    companion object {
        @JvmStatic
        fun invalidPasswords(): Stream<String> = Stream.of("", "1234567", "p".repeat(73), "é".repeat(3), "é".repeat(37))

        @JvmStatic
        fun validBoundaryPasswords(): Stream<String> = Stream.of("12345678", "p".repeat(72), "é".repeat(4), "é".repeat(36))
    }
}
