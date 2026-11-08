package dev.hotelbooking.booking.application.port

import dev.hotelbooking.booking.domain.Actor
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface BookingStore {
    fun history(
        userId: UUID?,
        page: Int,
        size: Int,
    ): BookingPage

    fun findRequest(
        userId: UUID,
        key: String,
    ): SavedBookingRequest?

    fun create(
        userId: UUID,
        roomId: UUID?,
        startDate: LocalDate,
        endDate: LocalDate,
        now: Instant,
        deadline: Instant,
        traceId: String,
        lease: Duration,
        identity: BookingRequestIdentity,
        autoSelect: Boolean = false,
        hotelId: UUID? = null,
    ): BookingClaim

    fun find(id: UUID): Booking?

    fun cancelByUser(
        actor: Actor,
        id: UUID,
    ): Booking

    fun due(
        now: Instant,
        limit: Int,
    ): List<UUID>

    fun claim(
        id: UUID,
        lease: Duration,
    ): BookingClaim?

    fun renew(
        claim: BookingClaim,
        lease: Duration,
    ): BookingClaim

    fun prepared(
        claim: BookingClaim,
        hotelId: UUID,
    ): BookingClaim

    fun selected(
        claim: BookingClaim,
        candidates: List<UUID>,
    ): BookingClaim

    fun candidateRejected(claim: BookingClaim): BookingClaim

    fun nextCandidate(claim: BookingClaim): BookingClaim

    fun held(claim: BookingClaim): BookingClaim

    fun confirmed(claim: BookingClaim): BookingClaim

    fun cancel(
        claim: BookingClaim,
        reason: BookingError,
    ): BookingClaim

    fun compensated(claim: BookingClaim): BookingClaim

    fun defer(
        claim: BookingClaim,
        delay: Duration,
    ): Booking
}

data class BookingClaim(
    val booking: Booking,
    val token: UUID,
    val version: Long,
    val recoveryFailures: Int,
)

class BookingClaimLost : RuntimeException("Booking execution claim is no longer current")

data class BookingRequestIdentity(
    val key: String,
    val fingerprint: String,
)

data class SavedBookingRequest(
    val booking: Booking,
    val fingerprint: String,
)

class BookingRequestAlreadyExists : RuntimeException("Booking request already exists")

data class BookingPage(
    val bookings: List<Booking>,
    val total: Long,
)
