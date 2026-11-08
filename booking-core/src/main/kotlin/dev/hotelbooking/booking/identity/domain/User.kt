package dev.hotelbooking.booking.identity.domain

import java.util.UUID

enum class Role { USER, ADMIN }

data class UserProfile(
    val id: UUID,
    val username: String,
    val role: Role,
)

class UserAccount(
    val profile: UserProfile,
    val passwordHash: String,
)

class Credentials(
    val username: String,
    val password: String,
)

enum class IdentityError {
    INVALID_CREDENTIALS,
    USERNAME_TAKEN,
    INVALID_USERNAME,
    INVALID_PASSWORD,
    USER_NOT_FOUND,
    INVALID_PAGE,
    CONCURRENT_UPDATE,
}

class IdentityException(
    val reason: IdentityError,
) : RuntimeException(reason.name)

data class UserPage(
    val users: List<UserProfile>,
    val total: Long,
)
