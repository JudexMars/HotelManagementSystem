package dev.hotelbooking.booking.persistence
import dev.hotelbooking.booking.application.port.BookingClaim
import dev.hotelbooking.booking.application.port.BookingClaimLost
import dev.hotelbooking.booking.application.port.BookingPage
import dev.hotelbooking.booking.application.port.BookingRequestAlreadyExists
import dev.hotelbooking.booking.application.port.BookingRequestIdentity
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.application.port.SavedBookingRequest
import dev.hotelbooking.booking.domain.Actor
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.BookingStep
import dev.hotelbooking.booking.domain.CompensationStatus
import jakarta.persistence.LockModeType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

interface BookingRepository : JpaRepository<BookingEntity, UUID> {
    @Query("select b from BookingEntity b where (:userId is null or b.userId = :userId) order by b.createdAt desc, b.id desc")
    fun history(
        @Param("userId") userId: UUID?,
        pageable: Pageable,
    ): Page<BookingEntity>

    fun findByUserIdAndIdempotencyKey(
        userId: UUID,
        idempotencyKey: String,
    ): BookingEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id = :id")
    fun locked(
        @Param("id") id: UUID,
    ): BookingEntity?

    @Query(
        """select b.id from BookingEntity b where
        (b.status = dev.hotelbooking.booking.domain.BookingStatus.PENDING and b.deadline <= :now)
        or ((b.status = dev.hotelbooking.booking.domain.BookingStatus.PENDING
            or (b.status = dev.hotelbooking.booking.domain.BookingStatus.CANCELLED
                and b.compensationStatus = dev.hotelbooking.booking.domain.CompensationStatus.PENDING))
            and b.nextActionAt <= :now and (b.leaseUntil is null or b.leaseUntil <= :now))
        order by b.nextActionAt, b.id""",
    )
    fun due(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}

@Repository
@Transactional
class JpaBookingStore(
    private val repository: BookingRepository,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : BookingStore {
    private val creationTransaction = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    override fun history(
        userId: UUID?,
        page: Int,
        size: Int,
    ): BookingPage {
        val result = repository.history(userId, PageRequest.of(page, size))
        return BookingPage(result.content.map { it.toDomain() }, result.totalElements)
    }

    @Transactional(readOnly = true)
    override fun findRequest(
        userId: UUID,
        key: String,
    ): SavedBookingRequest? =
        repository.findByUserIdAndIdempotencyKey(userId, key)?.let {
            SavedBookingRequest(it.toDomain(), requireNotNull(it.requestFingerprint))
        }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun create(
        userId: UUID,
        roomId: UUID?,
        startDate: LocalDate,
        endDate: LocalDate,
        now: Instant,
        deadline: Instant,
        traceId: String,
        lease: Duration,
        identity: BookingRequestIdentity,
        autoSelect: Boolean,
        hotelId: UUID?,
    ): BookingClaim =
        try {
            requireNotNull(
                creationTransaction.execute {
                    val entity =
                        BookingEntity(
                            UUID.randomUUID(),
                            userId,
                            roomId,
                            hotelId,
                            startDate,
                            endDate,
                            now.truncatedTo(ChronoUnit.MICROS),
                            BookingStatus.PENDING,
                            BookingStep.RESOLVE_ROOM,
                            UUID.randomUUID(),
                            CompensationStatus.NONE,
                            deadline.truncatedTo(ChronoUnit.MICROS),
                            traceId,
                            idempotencyKey = identity.key,
                            requestFingerprint = identity.fingerprint,
                            autoSelect = autoSelect,
                        )
                    entity.claimToken = UUID.randomUUID()
                    entity.leaseUntil = clock.instant().plus(lease).truncatedTo(ChronoUnit.MICROS)
                    repository.saveAndFlush(entity)
                    entity.claim(requireNotNull(entity.claimToken))
                },
            )
        } catch (error: DataIntegrityViolationException) {
            // The failed INSERT transaction has ended before reading the concurrent winner.
            if (repository.findByUserIdAndIdempotencyKey(userId, identity.key) != null) throw BookingRequestAlreadyExists()
            throw error
        }

    @Transactional(readOnly = true)
    override fun find(id: UUID): Booking? = repository.findById(id).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun due(
        now: Instant,
        limit: Int,
    ): List<UUID> = repository.due(now, PageRequest.of(0, limit))

    override fun claim(
        id: UUID,
        lease: Duration,
    ): BookingClaim? {
        val entity = locked(id)
        val now = clock.instant()
        if (entity.toDomain().deadlineExceeded(now)) {
            entity.applyState(entity.toDomain().cancelled(BookingError.BOOKING_DEADLINE))
            clearClaim(entity)
            entity.nextActionAt = now
        }
        if (!entity.toDomain().requiresRecovery || entity.nextActionAt.isAfter(now) || entity.leaseUntil?.isAfter(now) == true) return null
        val token = UUID.randomUUID()
        entity.claimToken = token
        entity.leaseUntil = now.plus(lease).truncatedTo(ChronoUnit.MICROS)
        repository.flush()
        return entity.claim(token)
    }

    override fun renew(
        claim: BookingClaim,
        lease: Duration,
    ): BookingClaim {
        val entity = owned(claim)
        val now = clock.instant()
        if (entity.toDomain().deadlineExceeded(now)) entity.applyState(entity.toDomain().cancelled(BookingError.BOOKING_DEADLINE))
        entity.leaseUntil = now.plus(lease).truncatedTo(ChronoUnit.MICROS)
        repository.flush()
        return entity.claim(claim.token)
    }

    override fun prepared(
        claim: BookingClaim,
        hotelId: UUID,
    ): BookingClaim = pending(claim) { it.prepared(hotelId) }

    override fun selected(
        claim: BookingClaim,
        candidates: List<UUID>,
    ): BookingClaim = pending(claim) { it.selected(candidates) }

    override fun candidateRejected(claim: BookingClaim): BookingClaim = pending(claim) { it.candidateRejected() }

    override fun nextCandidate(claim: BookingClaim): BookingClaim = pending(claim) { it.nextCandidate(UUID.randomUUID()) }

    override fun held(claim: BookingClaim): BookingClaim = pending(claim) { it.held() }

    override fun confirmed(claim: BookingClaim): BookingClaim = pending(claim) { it.confirmed() }

    override fun cancel(
        claim: BookingClaim,
        reason: BookingError,
    ): BookingClaim {
        val entity = owned(claim)
        if (entity.status == BookingStatus.PENDING) entity.applyState(entity.toDomain().cancelled(reason))
        repository.flush()
        return entity.claim(claim.token)
    }

    override fun cancelByUser(
        actor: Actor,
        id: UUID,
    ): Booking {
        val entity = locked(id)
        val previous = entity.toDomain()
        val now = clock.instant()
        val cancelled = previous.cancelByUser(actor, now)
        if (cancelled === previous) return previous
        entity.applyState(cancelled)
        clearClaim(entity)
        entity.nextActionAt = now
        repository.flush()
        return entity.toDomain()
    }

    override fun compensated(claim: BookingClaim): BookingClaim {
        val entity = owned(claim)
        entity.applyState(entity.toDomain().compensated())
        clearClaim(entity)
        repository.flush()
        return entity.claim(claim.token)
    }

    override fun defer(
        claim: BookingClaim,
        delay: Duration,
    ): Booking {
        val entity = owned(claim)
        entity.nextActionAt = clock.instant().plus(delay).truncatedTo(ChronoUnit.MICROS)
        entity.recoveryFailures = (entity.recoveryFailures + 1).coerceAtMost(100)
        clearClaim(entity)
        repository.flush()
        return entity.toDomain()
    }

    private fun pending(
        claim: BookingClaim,
        action: (Booking) -> Booking,
    ): BookingClaim {
        val entity = owned(claim)
        val previous = entity.toDomain()
        val next = previous.pending(clock.instant(), action)
        entity.applyState(next)
        if (previous.status == BookingStatus.PENDING && next.status == BookingStatus.CONFIRMED) clearClaim(entity)
        repository.flush()
        return entity.claim(claim.token)
    }

    private fun owned(claim: BookingClaim): BookingEntity {
        val entity = locked(claim.booking.id)
        if (entity.claimToken != claim.token || entity.version != claim.version || entity.leaseUntil?.isAfter(clock.instant()) != true) {
            throw BookingClaimLost()
        }
        return entity
    }

    private fun locked(id: UUID): BookingEntity = repository.locked(id) ?: throw BookingException(BookingError.BOOKING_NOT_FOUND)

    private fun clearClaim(e: BookingEntity) {
        e.claimToken = null
        e.leaseUntil = null
    }
}

private fun BookingEntity.claim(token: UUID) = BookingClaim(toDomain(), token, requireNotNull(version), recoveryFailures)

private fun BookingEntity.toDomain() =
    Booking(
        id,
        userId,
        roomId,
        hotelId,
        startDate,
        endDate,
        createdAt,
        status,
        step,
        attemptId,
        compensationStatus,
        deadline,
        failureCode,
        traceId,
        autoSelect,
        candidates.toList(),
        candidateIndex,
        candidateReleasePending,
    )

/** Apply only mutable business state; claim/lease/version remain adapter-owned concurrency metadata. */
private fun BookingEntity.applyState(value: Booking) {
    roomId = value.roomId
    hotelId = value.hotelId
    status = value.status
    step = value.step
    attemptId = value.attemptId
    compensationStatus = value.compensationStatus
    failureCode = value.failureCode
    if (candidates != value.candidates) candidates = value.candidates.toMutableList()
    candidateIndex = value.candidateIndex
    candidateReleasePending = value.candidateReleasePending
}
