package dev.hotelbooking.hotel.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
    ],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HotelSecurityTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `direct room access requires authentication and returns a traceable error`() {
        assertError(request("/api/rooms", traceId = "hotel-authentication"), 401, "UNAUTHORIZED", "hotel-authentication")
    }

    @Test
    fun `authentication failures generate a trace id when none was supplied`() {
        assertError(request("/api/rooms"), 401, "UNAUTHORIZED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `valid public user tokens pass direct hotel authentication`(role: String) {
        assertThat(request("/api/rooms/nonexistent/resource", jwks.userToken(role)).status).isEqualTo(404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong issuer", "wrong audience", "expired", "missing expiry", "forged signature", "future not-before"])
    fun `direct hotel access rejects invalid signed user tokens`(defect: String) {
        assertError(request("/api/rooms", jwks.userToken(defect = defect)), 401, "UNAUTHORIZED")
    }

    @Test
    fun `malformed bearer token is rejected by the actual security filters`() {
        assertError(request("/api/rooms", "not-a-jwt"), 401, "UNAUTHORIZED")
    }

    @Test
    fun `service token cannot authenticate as a public hotel user`() {
        assertError(request("/api/rooms", jwks.serviceToken()), 401, "UNAUTHORIZED")
    }

    @Test
    fun `internal room reads require a service token`() {
        assertError(request("/internal/rooms"), 401, "UNAUTHORIZED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `user roles cannot authenticate to internal inventory`(role: String) {
        assertError(request("/internal/rooms", jwks.userToken(role)), 401, "UNAUTHORIZED")
    }

    @Test
    fun `inventory read scope allows the request through the internal security chain`() {
        assertThat(
            request("/internal/rooms?startDate=2030-05-10&endDate=2030-05-12", jwks.serviceToken(scope = "inventory:read")).status,
        ).isEqualTo(200)
    }

    @Test
    fun `inventory write scope does not grant inventory read access`() {
        assertError(
            request("/internal/rooms", jwks.serviceToken(scope = "inventory:write"), "hotel-authorization"),
            403,
            "FORBIDDEN",
            "hotel-authorization",
        )
    }

    @Test
    fun `internal inventory rejects a service token issued for another subject`() {
        assertError(request("/internal/rooms", jwks.serviceToken(subject = "another-service")), 401, "UNAUTHORIZED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong issuer", "wrong audience", "expired", "missing expiry", "forged signature"])
    fun `internal inventory verifies service token authenticity and claims`(defect: String) {
        assertError(request("/internal/rooms", jwks.serviceToken(defect = defect)), 401, "UNAUTHORIZED")
    }

    @Test
    fun `readiness is available without a bearer token`() {
        val response = request("/actuator/health/readiness")

        assertThat(response.status).isEqualTo(200)
        assertThat(objectMapper.readTree(response.contentAsByteArray).path("status").asText()).isEqualTo("UP")
    }

    private fun request(
        path: String,
        token: String? = null,
        traceId: String? = null,
    ): MockHttpServletResponse {
        val request = get(path)
        if (token != null) request.header("Authorization", "Bearer $token")
        if (traceId != null) request.header("X-Trace-Id", traceId)
        return mockMvc.perform(request).andReturn().response
    }

    private fun assertError(
        response: MockHttpServletResponse,
        expectedStatus: Int,
        expectedCode: String,
        expectedTraceId: String? = null,
    ) {
        assertThat(response.status).isEqualTo(expectedStatus)
        assertThat(response.contentType).startsWith("application/json")
        val error = objectMapper.readTree(response.contentAsByteArray)
        assertThat(error.path("code").asText()).isEqualTo(expectedCode)
        assertThat(error.path("message").asText()).isNotBlank()
        val traceId = error.path("traceId").asText()
        assertThat(traceId).matches("[a-zA-Z0-9_-]{1,64}")
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(traceId)
        if (expectedTraceId != null) assertThat(traceId).isEqualTo(expectedTraceId)
    }

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
    }
}
