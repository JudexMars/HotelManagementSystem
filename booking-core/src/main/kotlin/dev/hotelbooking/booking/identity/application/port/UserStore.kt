package dev.hotelbooking.booking.identity.application.port

import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserAccount
import dev.hotelbooking.booking.identity.domain.UserPage
import dev.hotelbooking.booking.identity.domain.UserProfile
import java.util.UUID

interface UserStore {
    fun findActiveById(id: UUID): UserProfile?

    fun listActive(
        page: Int,
        size: Int,
    ): UserPage

    fun update(
        id: UUID,
        username: String,
        role: Role,
        passwordHash: String?,
    ): UserProfile

    fun delete(id: UUID)

    fun findActiveByUsername(username: String): UserAccount?

    fun create(
        profile: UserProfile,
        passwordHash: String,
    ): UserProfile
}
