package dev.hotelbooking.booking.identity.persistence

import dev.hotelbooking.booking.identity.domain.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
    @field:Id
    @field:Column(name = "id", nullable = false)
    val id: UUID,
    @field:Column(name = "username", nullable = false, length = 100)
    var username: String,
    @field:Column(name = "password", nullable = false, length = 100)
    var passwordHash: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false, length = 16)
    var role: Role,
    @field:Column(name = "deleted", nullable = false)
    var deleted: Boolean = false,
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null,
)
