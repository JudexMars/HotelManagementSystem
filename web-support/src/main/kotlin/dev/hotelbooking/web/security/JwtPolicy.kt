package dev.hotelbooking.web.security

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import java.time.Clock
import java.time.Duration
import java.util.UUID

object JwtPolicy {
    const val ISSUER = "hotel-booking-system"
    const val USER_AUDIENCE = "hotel-booking-api"
    const val SERVICE_AUDIENCE = "hotel-inventory"

    fun validator(
        audience: String,
        clock: Clock,
    ): OAuth2TokenValidator<Jwt> =
        DelegatingOAuth2TokenValidator(
            JwtIssuerValidator(ISSUER),
            JwtAudienceValidator(audience),
            JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
            { jwt ->
                val unexpired = jwt.expiresAt?.isAfter(clock.instant()) == true
                val subjectValid =
                    if (audience == SERVICE_AUDIENCE) {
                        jwt.subject == "booking-service"
                    } else {
                        runCatching { UUID.fromString(jwt.subject) }.isSuccess
                    }
                if (unexpired && subjectValid && jwt.audience == listOf(audience)) {
                    OAuth2TokenValidatorResult.success()
                } else {
                    OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Invalid token claims", null))
                }
            },
        )
}
