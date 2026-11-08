package dev.hotelbooking.gateway.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewaySecurityTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `public API requires authentication and returns a traceable error`() {
        assertError(request("/api/unmapped", traceId = "gateway-authentication"), 401, "UNAUTHORIZED", "gateway-authentication")
    }

    @Test
    fun `authentication failures generate a trace id when none was supplied`() {
        assertError(request("/api/unmapped"), 401, "UNAUTHORIZED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `valid public user tokens pass gateway authentication`(role: String) {
        assertThat(request("/api/unmapped", jwks.userToken(role)).status.value()).isEqualTo(404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong issuer", "wrong audience", "expired", "missing expiry", "forged signature", "future not-before"])
    fun `gateway rejects invalid signed user tokens`(defect: String) {
        assertError(request("/api/unmapped", jwks.userToken(defect = defect)), 401, "UNAUTHORIZED")
    }

    @Test
    fun `malformed bearer token is rejected by the actual gateway filters`() {
        assertError(request("/api/unmapped", "not-a-jwt"), 401, "UNAUTHORIZED")
    }

    @Test
    fun `service token cannot authenticate to public gateway APIs`() {
        assertError(request("/api/unmapped", jwks.serviceToken()), 401, "UNAUTHORIZED")
    }

    @Test
    fun `USER cannot access user administration`() {
        assertError(
            request("/api/users", jwks.userToken(), "gateway-authorization"),
            403,
            "FORBIDDEN",
            "gateway-authorization",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed", "USER", "ADMIN", "service"])
    fun `internal inventory stays hidden before authentication is attempted`(credentials: String) {
        val token =
            when (credentials) {
                "missing" -> null
                "malformed" -> "not-a-jwt"
                "service" -> jwks.serviceToken()
                else -> jwks.userToken(credentials)
            }

        assertError(request("/internal/rooms", token, "gateway-internal"), 404, "NOT_FOUND", "gateway-internal")
    }

    @Test
    fun `readiness is available without a bearer token`() {
        val response = request("/actuator/health/readiness")

        assertThat(response.status.value()).isEqualTo(200)
        assertThat(objectMapper.readTree(response.responseBody).path("status").asText()).isEqualTo("UP")
    }

    private fun request(
        path: String,
        token: String? = null,
        traceId: String? = null,
    ): EntityExchangeResult<ByteArray> {
        val request = webTestClient.get().uri(path)
        if (token != null) request.header("Authorization", "Bearer $token")
        if (traceId != null) request.header("X-Trace-Id", traceId)
        return request.exchange().expectBody().returnResult()
    }

    private fun assertError(
        response: EntityExchangeResult<ByteArray>,
        expectedStatus: Int,
        expectedCode: String,
        expectedTraceId: String? = null,
    ) {
        assertThat(response.status.value()).isEqualTo(expectedStatus)
        assertThat(response.responseHeaders.contentType?.toString()).startsWith("application/json")
        val error = objectMapper.readTree(response.responseBody)
        assertThat(error.path("code").asText()).isEqualTo(expectedCode)
        assertThat(error.path("message").asText()).isNotBlank()
        val traceId = error.path("traceId").asText()
        assertThat(traceId).matches("[a-zA-Z0-9_-]{1,64}")
        assertThat(response.responseHeaders.getFirst("X-Trace-Id")).isEqualTo(traceId)
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
