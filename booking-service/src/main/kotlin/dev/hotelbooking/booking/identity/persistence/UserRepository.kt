package dev.hotelbooking.booking.identity.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findAllByDeletedFalse(pageable: Pageable): Page<UserEntity>

    fun findByUsernameAndDeletedFalse(username: String): UserEntity?
}
