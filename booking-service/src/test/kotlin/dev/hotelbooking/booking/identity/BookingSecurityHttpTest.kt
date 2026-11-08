package dev.hotelbooking.booking.identity

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserProfile
import dev.hotelbooking.booking.security.JwtTokenIssuer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.profiles.active=dev",
        "booking.demo.admin.username=demo-admin",
        "booking.demo.admin.password=demo-admin-password",
    ],
)
class BookingSecurityHttpTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var issuer: JwtTokenIssuer

    @Test
    fun `unsupported media type on public authentication survives the servlet error dispatch`() {
        val traceId = "unsupported-${UUID.randomUUID()}"
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/api/users/auth"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/plain")
                .header("X-Trace-Id", traceId)
                .POST(HttpRequest.BodyPublishers.ofString("invalid credentials body"))
                .build()

        val response = send(request)

        assertApiError(response, 415, "UNSUPPORTED_MEDIA_TYPE", traceId)
    }

    @Test
    fun `an authenticated missing resource remains not found through the servlet error dispatch`() {
        val traceId = "missing-${UUID.randomUUID()}"
        val token = issuer.issue(UserProfile(UUID.randomUUID(), "http-security-user", Role.USER))
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/api/bookings/nonexistent/resource"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer ${token.value}")
                .header("X-Trace-Id", traceId)
                .GET()
                .build()

        val response = send(request)

        assertApiError(response, 404, "NOT_FOUND", traceId)
    }

    private fun send(request: HttpRequest): HttpResponse<ByteArray> =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .use { it.send(request, HttpResponse.BodyHandlers.ofByteArray()) }

    private fun assertApiError(
        response: HttpResponse<ByteArray>,
        expectedStatus: Int,
        expectedCode: String,
        traceId: String,
    ) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        val contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue()
        assertThat(response.headers().firstValue("X-Trace-Id").orElse(null)).isEqualTo(traceId)
        val error = objectMapper.readTree(response.body())
        assertThat(error.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("code", "message", "traceId")
        assertThat(error.path("code").asText()).isEqualTo(expectedCode)
        assertThat(error.path("message").asText()).isNotBlank()
        assertThat(error.path("traceId").asText()).isEqualTo(traceId)
    }
}
