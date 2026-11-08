package dev.hotelbooking.booking.identity.api

import dev.hotelbooking.booking.identity.application.AuthenticatedUser
import dev.hotelbooking.booking.identity.application.IdentityService
import dev.hotelbooking.booking.identity.domain.Credentials
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.security.JwtTokenIssuer
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

data class CredentialsRequest(
    val username: String,
    val password: String,
) {
    override fun toString() = "CredentialsRequest(username=$username, password=<redacted>)"
}

data class UserResponse(
    val id: UUID,
    val username: String,
    val role: Role,
)

data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val user: UserResponse,
)

@RestController
@SecurityRequirements
@RequestMapping("/api/users")
class IdentityController(
    private val service: IdentityService,
    private val issuer: JwtTokenIssuer,
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestBody request: CredentialsRequest,
    ): TokenResponse = service.register(Credentials(request.username, request.password)).toResponse()

    @PostMapping("/auth")
    fun authenticate(
        @RequestBody request: CredentialsRequest,
    ): TokenResponse = service.authenticate(Credentials(request.username, request.password)).toResponse()

    @GetMapping("/jwks")
    fun publicKeys(): Map<String, Any> = issuer.publicKeys()
}

private fun AuthenticatedUser.toResponse() =
    TokenResponse(
        token.value,
        "Bearer",
        Duration.between(token.issuedAt, token.expiresAt).seconds,
        UserResponse(user.id, user.username, user.role),
    )
