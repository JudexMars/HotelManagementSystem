package dev.hotelbooking.booking.identity.application

import dev.hotelbooking.booking.identity.application.port.PasswordHasher
import dev.hotelbooking.booking.identity.application.port.UserStore
import dev.hotelbooking.booking.identity.domain.Credentials
import dev.hotelbooking.booking.identity.domain.IdentityError
import dev.hotelbooking.booking.identity.domain.IdentityException
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserPage
import dev.hotelbooking.booking.identity.domain.UserProfile
import dev.hotelbooking.booking.identity.domain.UserValidation
import java.util.UUID

class UserAdministrationService(
    private val users: UserStore,
    private val passwords: PasswordHasher,
) {
    fun create(
        credentials: Credentials,
        role: Role,
    ): UserProfile {
        UserValidation.username(credentials.username)
        UserValidation.password(credentials.password)
        return users.create(UserProfile(UUID.randomUUID(), credentials.username, role), passwords.encode(credentials.password))
    }

    fun get(id: UUID): UserProfile = users.findActiveById(id) ?: throw IdentityException(IdentityError.USER_NOT_FOUND)

    fun list(
        page: Int,
        size: Int,
    ): UserPage {
        if (page < 0 || size !in 1..100 || page.toLong() * size > Int.MAX_VALUE) throw IdentityException(IdentityError.INVALID_PAGE)
        return users.listActive(page, size)
    }

    fun update(
        id: UUID,
        username: String,
        role: Role,
        password: String?,
    ): UserProfile {
        UserValidation.username(username)
        password?.let(UserValidation::password)
        return users.update(id, username, role, password?.let(passwords::encode))
    }

    fun delete(id: UUID) = users.delete(id)
}
