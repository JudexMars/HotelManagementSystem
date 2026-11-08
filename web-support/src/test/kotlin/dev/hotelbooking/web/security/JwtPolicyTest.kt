package dev.hotelbooking.web.security
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JwtPolicyTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userValidator = JwtPolicy.validator("hotel-booking-api", clock)
    private val serviceValidator = JwtPolicy.validator("hotel-inventory", clock)

    @Test
    fun `a current user token with the public audience is valid`() {
        assertAccepted(userValidator, userToken().build())
    }

    @Test
    fun `a current Booking service token with the inventory audience is valid`() {
        assertAccepted(serviceValidator, serviceToken().build())
    }

    @Test
    fun `a token without an expiry is rejected`() {
        val token = userToken().claims { it.remove("exp") }.build()

        assertRejected(userValidator, token)
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0])
    fun `a token is expired at its expiry instant without clock skew`(secondsFromNow: Long) {
        val token = userToken().expiresAt(now.plusSeconds(secondsFromNow)).build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `a token is still valid one second before expiry`() {
        val token = userToken().expiresAt(now.plusSeconds(1)).build()

        assertAccepted(userValidator, token)
    }

    @Test
    fun `a different issuer cannot issue an accepted user token`() {
        val token = userToken().issuer("untrusted-issuer").build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `an absent issuer cannot authenticate a user`() {
        val token = userToken().claims { it.remove("iss") }.build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `an inventory audience cannot authenticate a public user`() {
        val token = userToken().audience(listOf("hotel-inventory")).build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `a public audience cannot authenticate an inventory service`() {
        val token = serviceToken().audience(listOf("hotel-booking-api")).build()

        assertRejected(serviceValidator, token)
    }

    @Test
    fun `an absent audience cannot authenticate a user`() {
        val token = userToken().claims { it.remove("aud") }.build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `a user token cannot also grant the inventory audience`() {
        val token = userToken().audience(listOf("hotel-booking-api", "hotel-inventory")).build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `a service token cannot also grant the public audience`() {
        val token = serviceToken().audience(listOf("hotel-inventory", "hotel-booking-api")).build()

        assertRejected(serviceValidator, token)
    }

    @ParameterizedTest
    @ValueSource(strings = ["hotel-service", "booking-admin", "", "e341f6c0-9caf-4f44-957a-b7b3561e5809"])
    fun `the inventory audience accepts only the Booking service subject`(subject: String) {
        val token = serviceToken().subject(subject).build()

        assertRejected(serviceValidator, token)
    }

    @Test
    fun `a missing service subject is rejected`() {
        val token = serviceToken().claims { it.remove("sub") }.build()

        assertRejected(serviceValidator, token)
    }

    @ParameterizedTest
    @ValueSource(strings = ["booking-service", "not-a-user-id", ""])
    fun `a public user token requires a UUID subject`(subject: String) {
        val token = userToken().subject(subject).build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `a missing user subject is rejected`() {
        val token = userToken().claims { it.remove("sub") }.build()

        assertRejected(userValidator, token)
    }

    @Test
    fun `a token is rejected before its not before instant`() {
        val token = userToken().notBefore(now.plusSeconds(1)).build()

        assertRejected(userValidator, token)
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0])
    fun `a token is accepted from its not before instant`(secondsFromNow: Long) {
        val token = userToken().notBefore(now.plusSeconds(secondsFromNow)).build()

        assertAccepted(userValidator, token)
    }

    private fun userToken(): Jwt.Builder =
        Jwt
            .withTokenValue("user-claim-validation-fixture")
            .header("alg", "RS256")
            .issuer("hotel-booking-system")
            .subject("e341f6c0-9caf-4f44-957a-b7b3561e5809")
            .audience(listOf("hotel-booking-api"))
            .issuedAt(now.minusSeconds(60))
            .expiresAt(now.plusSeconds(3600))
            .claim("roles", listOf("USER"))

    private fun serviceToken(): Jwt.Builder =
        Jwt
            .withTokenValue("service-claim-validation-fixture")
            .header("alg", "RS256")
            .issuer("hotel-booking-system")
            .subject("booking-service")
            .audience(listOf("hotel-inventory"))
            .issuedAt(now.minusSeconds(60))
            .expiresAt(now.plusSeconds(300))
            .claim("scope", "inventory:read inventory:write")

    private fun assertAccepted(
        validator: OAuth2TokenValidator<Jwt>,
        token: Jwt,
    ) {
        val result = validator.validate(token)

        assertThat(result.hasErrors()).isFalse()
        assertThat(result.errors).isEmpty()
    }

    private fun assertRejected(
        validator: OAuth2TokenValidator<Jwt>,
        token: Jwt,
    ) {
        val result = validator.validate(token)

        assertThat(result.hasErrors()).isTrue()
        assertThat(result.errors.map { it.errorCode }).containsOnly("invalid_token")
    }
}
