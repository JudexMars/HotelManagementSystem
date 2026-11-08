package dev.hotelbooking.booking.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import dev.hotelbooking.booking.identity.application.port.TokenIssuer
import dev.hotelbooking.booking.identity.domain.IssuedToken
import dev.hotelbooking.booking.identity.domain.UserProfile
import dev.hotelbooking.web.security.JwtPolicy
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

class JwtTokenIssuer(
    private val clock: Clock,
) : TokenIssuer,
    ServiceTokenProvider {
    private val key =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().let {
            RSAKey
                .Builder(it.public as RSAPublicKey)
                .privateKey(it.private as RSAPrivateKey)
                .keyID(UUID.randomUUID().toString())
                .build()
        }
    private val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(key)))
    val publicKey: RSAPublicKey get() = key.toRSAPublicKey()

    fun publicKeys(): Map<String, Any> = JWKSet(key.toPublicJWK()).toJSONObject()

    override fun issue(user: UserProfile): IssuedToken =
        issue(
            user.id.toString(),
            JwtPolicy.USER_AUDIENCE,
            3600,
            mapOf("roles" to listOf(user.role.name)),
        )

    override fun serviceToken(): String = issueServiceToken().value

    fun issueServiceToken(): IssuedToken =
        issue(
            "booking-service",
            JwtPolicy.SERVICE_AUDIENCE,
            300,
            mapOf("scope" to "inventory:read inventory:write"),
        )

    private fun issue(
        subject: String,
        audience: String,
        lifetimeSeconds: Long,
        extraClaims: Map<String, Any>,
    ): IssuedToken {
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plusSeconds(lifetimeSeconds)
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(JwtPolicy.ISSUER)
                .subject(subject)
                .audience(listOf(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claims { it.putAll(extraClaims) }
                .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.keyID).build()
        return IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, issuedAt, expiresAt)
    }
}
