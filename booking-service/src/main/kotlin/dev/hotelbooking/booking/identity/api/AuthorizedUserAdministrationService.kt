package dev.hotelbooking.booking.identity.api

import dev.hotelbooking.booking.identity.application.UserAdministrationService
import dev.hotelbooking.booking.identity.domain.Credentials
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserPage
import dev.hotelbooking.booking.identity.domain.UserProfile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@PreAuthorize("hasRole('ADMIN')")
class AuthorizedUserAdministrationService(
    private val delegate: UserAdministrationService,
) {
    fun create(
        credentials: Credentials,
        role: Role,
    ): UserProfile = delegate.create(credentials, role)

    fun get(id: UUID): UserProfile = delegate.get(id)

    fun list(
        page: Int,
        size: Int,
    ): UserPage = delegate.list(page, size)

    fun update(
        id: UUID,
        username: String,
        role: Role,
        password: String?,
    ): UserProfile = delegate.update(id, username, role, password)

    fun delete(id: UUID) = delegate.delete(id)
}
