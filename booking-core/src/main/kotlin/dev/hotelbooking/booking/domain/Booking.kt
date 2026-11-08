package dev.hotelbooking.booking.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

enum class BookingStatus { PENDING, CONFIRMED, CANCELLED }

enum class BookingStep { RESOLVE_ROOM, HOLD, COMMIT, DONE }

enum class CompensationStatus { NONE, PENDING, DONE }

data class Actor(
    val id: UUID,
    val admin: Boolean,
)

data class Booking(
    val id: UUID,
    val userId: UUID,
    val roomId: UUID?,
    val hotelId: UUID?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val createdAt: Instant,
    val status: BookingStatus,
    val step: BookingStep,
    val attemptId: UUID,
    val compensationStatus: CompensationStatus,
    val deadline: Instant,
    val failureCode: BookingError?,
    val traceId: String,
    val autoSelect: Boolean = false,
    val candidates: List<UUID> = emptyList(),
    val candidateIndex: Int = 0,
    val candidateReleasePending: Boolean = false,
) {
    val requiresRecovery: Boolean
        get() =
            status == BookingStatus.PENDING ||
                (status == BookingStatus.CANCELLED && compensationStatus == CompensationStatus.PENDING)

    fun deadlineExceeded(now: Instant): Boolean = status == BookingStatus.PENDING && !deadline.isAfter(now)

    fun pending(
        now: Instant,
        transition: (Booking) -> Booking,
    ): Booking =
        when {
            status != BookingStatus.PENDING -> this
            deadlineExceeded(now) -> cancelled(BookingError.BOOKING_DEADLINE)
            else -> transition(this)
        }

    fun prepared(hotelId: UUID): Booking = copy(hotelId = hotelId, step = BookingStep.HOLD)

    fun selected(candidates: List<UUID>): Booking {
        check(autoSelect && step == BookingStep.RESOLVE_ROOM)
        require(candidates.isNotEmpty() && candidates.distinct().size == candidates.size)
        return copy(candidates = candidates.toList(), candidateIndex = 0, roomId = candidates.first(), step = BookingStep.HOLD)
    }

    fun candidateRejected(): Booking {
        check(autoSelect && step == BookingStep.HOLD)
        return copy(candidateReleasePending = true)
    }

    fun nextCandidate(attemptId: UUID): Booking {
        check(candidateReleasePending && candidateIndex + 1 < candidates.size)
        val nextIndex = candidateIndex + 1
        return copy(
            candidateIndex = nextIndex,
            roomId = candidates[nextIndex],
            attemptId = attemptId,
            candidateReleasePending = false,
            step = BookingStep.HOLD,
        )
    }

    fun held(): Booking = copy(step = BookingStep.COMMIT)

    fun confirmed(): Booking = copy(status = BookingStatus.CONFIRMED, step = BookingStep.DONE)

    fun cancelled(reason: BookingError): Booking =
        copy(
            status = BookingStatus.CANCELLED,
            step = BookingStep.DONE,
            compensationStatus = CompensationStatus.PENDING,
            failureCode = reason,
        )

    fun cancelByUser(
        actor: Actor,
        now: Instant,
    ): Booking {
        if (!actor.admin && userId != actor.id) throw BookingException(BookingError.BOOKING_NOT_FOUND)
        if (status == BookingStatus.CANCELLED) return this
        if (!startDate.isAfter(LocalDate.ofInstant(now, ZoneOffset.UTC))) {
            throw BookingException(BookingError.CANCELLATION_TOO_LATE)
        }
        return cancelled(BookingError.BOOKING_CANCELLED)
    }

    fun compensated(): Booking {
        check(status == BookingStatus.CANCELLED)
        return copy(compensationStatus = CompensationStatus.DONE)
    }
}

enum class BookingError {
    INVALID_BOOKING,
    INVALID_BOOKING_PAGE,
    BOOKING_FILTER_FORBIDDEN,
    INVALID_IDEMPOTENCY_KEY,
    IDEMPOTENCY_CONFLICT,
    BOOKING_NOT_FOUND,
    HOTEL_NOT_FOUND,
    HOTEL_ACCESS_DENIED,
    BOOKING_CANCELLED,
    CANCELLATION_TOO_LATE,
    HOTEL_UNAVAILABLE,
    INVENTORY_PROTOCOL_ERROR,
    BOOKING_DEADLINE,
    HOTEL_TIMEOUT,
    HOTEL_CIRCUIT_OPEN,
    ROOM_UNAVAILABLE,
    ROOM_NOT_FOUND,
    HOLD_EXPIRED,
    ATTEMPT_CONFLICT,
    ATTEMPT_RELEASED,
}

class BookingException(
    val reason: BookingError,
) : RuntimeException(reason.name)
