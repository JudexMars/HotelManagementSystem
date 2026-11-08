package dev.hotelbooking.booking.identity.persistence

import dev.hotelbooking.booking.identity.application.port.UserStore
import dev.hotelbooking.booking.identity.domain.IdentityError
import dev.hotelbooking.booking.identity.domain.IdentityException
import dev.hotelbooking.booking.identity.domain.Role
import dev.hotelbooking.booking.identity.domain.UserAccount
import dev.hotelbooking.booking.identity.domain.UserPage
import dev.hotelbooking.booking.identity.domain.UserProfile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Repository
class JpaUserStore(
    private val repository: UserRepository,
) : UserStore {
    @Transactional(readOnly = true)
    override fun findActiveByUsername(username: String): UserAccount? =
        repository.findByUsernameAndDeletedFalse(username)?.let { UserAccount(it.toProfile(), it.passwordHash) }

    @Transactional(readOnly = true)
    override fun findActiveById(id: UUID): UserProfile? =
        repository
            .findById(id)
            .getOrNull()
            ?.takeUnless { it.deleted }
            ?.toProfile()

    @Transactional(readOnly = true)
    override fun listActive(
        page: Int,
        size: Int,
    ): UserPage {
        val result = repository.findAllByDeletedFalse(PageRequest.of(page, size, Sort.by("username", "id")))
        return UserPage(result.content.map(UserEntity::toProfile), result.totalElements)
    }

    @Transactional
    override fun create(
        profile: UserProfile,
        passwordHash: String,
    ): UserProfile =
        translateConflicts {
            repository.saveAndFlush(UserEntity(profile.id, profile.username, passwordHash, profile.role)).toProfile()
        }

    @Transactional
    override fun update(
        id: UUID,
        username: String,
        role: Role,
        passwordHash: String?,
    ): UserProfile =
        translateConflicts {
            val user = active(id)
            user.username = username
            user.role = role
            passwordHash?.let { user.passwordHash = it }
            repository.flush()
            user.toProfile()
        }

    @Transactional
    override fun delete(id: UUID) {
        translateConflicts {
            active(id).deleted = true
            repository.flush()
        }
    }

    private fun active(id: UUID): UserEntity =
        repository.findById(id).getOrNull()?.takeUnless { it.deleted }
            ?: throw IdentityException(IdentityError.USER_NOT_FOUND)

    private fun <T> translateConflicts(action: () -> T): T =
        try {
            action()
        } catch (_: DataIntegrityViolationException) {
            throw IdentityException(IdentityError.USERNAME_TAKEN)
        } catch (_: OptimisticLockingFailureException) {
            throw IdentityException(IdentityError.CONCURRENT_UPDATE)
        }
}

private fun UserEntity.toProfile() = UserProfile(id, username, role)
