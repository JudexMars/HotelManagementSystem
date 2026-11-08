package dev.hotelbooking.booking.identity.application

import dev.hotelbooking.booking.identity.application.port.PasswordHasher
import dev.hotelbooking.booking.identity.application.port.TokenIssuer
import dev.hotelbooking.booking.identity.application.port.UserStore
import dev.hotelbooking.booking.identity.domain.Credentials
import dev.hotelbooking.booking.identity.domain.IdentityError
import dev.hotelbooking.booking.identity.domain.IdentityException
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserProfile
import dev.hotelbooking.booking.identity.domain.UserValidation
import java.util.UUID

class IdentityService(
    private val users: UserStore,
    private val passwords: PasswordHasher,
    private val tokens: TokenIssuer,
) {
    private val unavailableAccountHash = passwords.encode(UUID.randomUUID().toString())

    fun register(credentials: Credentials): AuthenticatedUser = issueAuthentication(create(credentials, Role.USER))

    fun authenticate(credentials: Credentials): AuthenticatedUser {
        if (credentials.password.toByteArray(Charsets.UTF_8).size !in 8..72) {
            throw IdentityException(IdentityError.INVALID_CREDENTIALS)
        }
        val account = users.findActiveByUsername(credentials.username)
        val matches = passwords.matches(credentials.password, account?.passwordHash ?: unavailableAccountHash)
        if (account == null || !matches) throw IdentityException(IdentityError.INVALID_CREDENTIALS)
        return issueAuthentication(account.profile)
    }

    fun bootstrapAdmin(credentials: Credentials) {
        if (users.findActiveByUsername(credentials.username) == null) create(credentials, Role.ADMIN)
    }

    private fun create(
        credentials: Credentials,
        role: Role,
    ): UserProfile {
        UserValidation.username(credentials.username)
        UserValidation.password(credentials.password)
        return users.create(
            UserProfile(UUID.randomUUID(), credentials.username, role),
            passwords.encode(credentials.password),
        )
    }

    private fun issueAuthentication(user: UserProfile) = AuthenticatedUser(user, tokens.issue(user))
}
