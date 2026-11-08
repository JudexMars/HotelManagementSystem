package dev.hotelbooking.booking.identity

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserProfile
import dev.hotelbooking.booking.security.JwtTokenIssuer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

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
@Import(BookingSecurityTest.ClockConfiguration::class)
class BookingSecurityTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var issuer: JwtTokenIssuer

    @Autowired
    private lateinit var clock: MutableClock

    @BeforeEach
    @AfterEach
    fun resetClock() {
        clock.currentInstant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/users", "/api/bookings"])
    fun `protected API rejects missing credentials with a traceable JSON error`(path: String) {
        val traceId = "security-${UUID.randomUUID()}"

        val result =
            mockMvc
                .perform(get(path).header("X-Trace-Id", traceId))
                .andExpect(status().isUnauthorized)
                .andReturn()

        assertSecurityError(result, traceId)
    }

    @Test
    fun `missing credentials generate a trace identifier when none is provided`() {
        val result =
            mockMvc
                .perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized)
                .andReturn()

        val traceId = requireNotNull(result.response.getHeader("X-Trace-Id"))
        assertThat(traceId).isNotBlank()
        assertSecurityError(result, traceId)
    }

    @Test
    fun `USER cannot access the administrator user API`() {
        val traceId = "forbidden-${UUID.randomUUID()}"

        val result = protectedRequest("/api/users", tokenFor(Role.USER), 403, traceId)

        assertSecurityError(result, traceId, "FORBIDDEN")
    }

    @Test
    fun `ADMIN passes security for the administrator user API`() {
        protectedRequest("/api/users/nonexistent/resource", tokenFor(Role.ADMIN), 404)
    }

    @ParameterizedTest
    @EnumSource(Role::class)
    fun `USER and ADMIN pass security for the booking API`(role: Role) {
        protectedRequest("/api/bookings/${UUID.randomUUID()}", tokenFor(role), 404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-jwt", "eyJhbGciOiJub25lIn0.e30."])
    fun `malformed and unsigned bearer tokens are rejected`(token: String) {
        val traceId = "invalid-${UUID.randomUUID()}"

        val result = protectedRequest("/api/bookings", token, 401, traceId)

        assertSecurityError(result, traceId)
    }

    @Test
    fun `a token signed by an untrusted key is rejected despite valid claims`() {
        val token = tokenFor(Role.USER)
        val parts = token.split('.')
        val attackerKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(attackerKey.private)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        val forgedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())
        val traceId = "forged-${UUID.randomUUID()}"

        val result = protectedRequest("/api/bookings", "${parts[0]}.${parts[1]}.$forgedSignature", 401, traceId)

        assertSecurityError(result, traceId)
    }

    @Test
    fun `an issued user token expires even when its signature remains valid`() {
        val issued = issuer.issue(UserProfile(UUID.randomUUID(), "expired-user", Role.USER))
        clock.currentInstant = issued.expiresAt.plusSeconds(120)
        val traceId = "expired-${UUID.randomUUID()}"

        val result = protectedRequest("/api/bookings", issued.value, 401, traceId)

        assertSecurityError(result, traceId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/users", "/api/bookings"])
    fun `a valid inventory service token cannot authenticate to the public API`(path: String) {
        val traceId = "service-${UUID.randomUUID()}"
        val serviceToken = issuer.issueServiceToken()

        val result = protectedRequest(path, serviceToken.value, 401, traceId)

        assertSecurityError(result, traceId)
    }

    private fun tokenFor(role: Role): String = issuer.issue(UserProfile(UUID.randomUUID(), "security-${UUID.randomUUID()}", role)).value

    private fun protectedRequest(
        path: String,
        token: String,
        expectedStatus: Int,
        traceId: String = "security-${UUID.randomUUID()}",
    ): MvcResult =
        mockMvc
            .perform(
                get(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .header("X-Trace-Id", traceId),
            ).andExpect(status().`is`(expectedStatus))
            .andReturn()

    private fun assertSecurityError(
        result: MvcResult,
        traceId: String,
        expectedCode: String = "UNAUTHORIZED",
    ) {
        assertThat(
            MediaType.parseMediaType(requireNotNull(result.response.contentType)).isCompatibleWith(MediaType.APPLICATION_JSON),
        ).isTrue()
        assertThat(result.response.getHeader("X-Trace-Id")).isEqualTo(traceId)
        val error = objectMapper.readTree(result.response.contentAsByteArray)
        assertThat(error.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("code", "message", "traceId")
        assertThat(error.path("code").asText()).isEqualTo(expectedCode)
        assertThat(error.path("message").asText()).isNotBlank()
        assertThat(error.path("traceId").asText()).isEqualTo(traceId)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfiguration {
        @Bean
        @Primary
        fun securityTestClock(): MutableClock = MutableClock()
    }

    class MutableClock : Clock() {
        @Volatile
        var currentInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        override fun instant(): Instant = currentInstant

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = fixed(currentInstant, zone)
    }
}
