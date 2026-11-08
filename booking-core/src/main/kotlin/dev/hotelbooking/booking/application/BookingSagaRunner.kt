package dev.hotelbooking.booking.application

import dev.hotelbooking.booking.application.port.BookingClaim
import dev.hotelbooking.booking.application.port.BookingClaimLost
import dev.hotelbooking.booking.application.port.BookingEvents
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.application.port.HotelInventory
import dev.hotelbooking.booking.application.port.InventoryResult
import dev.hotelbooking.booking.application.port.InventoryStatus
import dev.hotelbooking.booking.application.port.RetryPause
import dev.hotelbooking.booking.application.port.SagaEvent
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.BookingStep
import java.time.Clock
import java.util.UUID

class BookingSagaRunner(
    private val store: BookingStore,
    private val hotel: HotelInventory,
    private val recommendations: RecommendRoomsUseCase,
    private val clock: Clock,
    private val policy: SagaPolicy,
    private val pause: RetryPause,
    private val events: BookingEvents,
) {
    fun resume(id: UUID): Booking {
        val claim = store.claim(id, policy.lease) ?: return requireNotNull(store.find(id))
        return execute(claim)
    }

    fun recoverDue() {
        for (id in store.due(clock.instant(), 20)) {
            try {
                resume(id)
            } catch (_: Exception) {
                store.find(id)?.let { events.record(SagaEvent.INTERRUPTED, it) }
                // Retain the finite lease; the next cycle may retry after the interrupted owner expires.
            }
        }
    }

    internal fun execute(initial: BookingClaim): Booking =
        try {
            Execution(initial).run()
        } catch (_: BookingClaimLost) {
            requireNotNull(store.find(initial.booking.id))
        }

    private inner class Execution(
        var claim: BookingClaim,
    ) {
        val booking: Booking get() = claim.booking

        fun run(): Booking {
            if (booking.status == BookingStatus.CANCELLED) return compensate()
            try {
                while (booking.status == BookingStatus.PENDING) {
                    ensurePending()
                    when (booking.step) {
                        BookingStep.RESOLVE_ROOM -> resolveRoom()
                        BookingStep.HOLD -> {
                            if (booking.candidateReleasePending) releaseCandidate() else hold()
                        }
                        BookingStep.COMMIT -> {
                            events.record(SagaEvent.COMMIT_REQUESTED, booking)
                            validate(retry { hotel.commit(booking) }, booking, setOf(InventoryStatus.RESERVED))
                            claim = store.confirmed(claim)
                        }
                        BookingStep.DONE -> error("Pending booking has no next step")
                    }
                }
                if (booking.status == BookingStatus.CANCELLED) throw BookingException(booking.failureCode ?: BookingError.BOOKING_DEADLINE)
                events.record(SagaEvent.CONFIRMED, booking)
                return booking
            } catch (error: BookingException) {
                claim = store.cancel(claim, error.reason)
                events.record(SagaEvent.CANCELLED, booking)
                return compensate()
            }
        }

        private fun resolveRoom() {
            if (booking.autoSelect) {
                val candidates =
                    retry {
                        recommendations.recommendations(
                            booking.startDate,
                            booking.endDate,
                            requireNotNull(booking.hotelId),
                            booking.traceId,
                        )
                    }
                if (candidates.isEmpty()) throw BookingException(BookingError.ROOM_UNAVAILABLE)
                claim = store.selected(claim, candidates.map { it.id })
                if (booking.status == BookingStatus.PENDING) events.record(SagaEvent.CANDIDATE_SELECTED, booking)
            } else {
                val room = retry { hotel.room(requireNotNull(booking.roomId), booking.traceId) }
                if (room.id != booking.roomId) throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
                claim = store.prepared(claim, room.hotelId)
            }
        }

        private fun hold() {
            events.record(SagaEvent.HOLD_REQUESTED, booking)
            try {
                validate(retry { hotel.hold(booking) }, booking, setOf(InventoryStatus.HELD, InventoryStatus.RESERVED))
                claim = store.held(claim)
            } catch (error: BookingException) {
                if (!booking.autoSelect || error.reason != BookingError.ROOM_UNAVAILABLE) throw error
                claim = store.candidateRejected(claim)
                events.record(SagaEvent.CANDIDATE_REJECTED, booking)
            }
        }

        private fun releaseCandidate() {
            try {
                validate(retry { hotel.release(booking) }, booking, setOf(InventoryStatus.RELEASED))
            } catch (error: BookingException) {
                if (error.reason != BookingError.ROOM_NOT_FOUND) throw error
            }
            events.record(SagaEvent.CANDIDATE_RELEASED, booking)
            if (booking.candidateIndex + 1 >= booking.candidates.size) throw BookingException(BookingError.ROOM_UNAVAILABLE)
            claim = store.nextCandidate(claim)
            if (booking.status == BookingStatus.PENDING) events.record(SagaEvent.CANDIDATE_SELECTED, booking)
        }

        private fun ensurePending() {
            if (booking.status != BookingStatus.PENDING || !booking.deadline.isAfter(clock.instant())) {
                throw BookingException(booking.failureCode ?: BookingError.BOOKING_DEADLINE)
            }
        }

        private fun <T> retry(action: () -> T): T {
            repeat(policy.maxAttempts) { attempt ->
                claim = store.renew(claim, policy.lease)
                ensurePending()
                try {
                    return action()
                } catch (error: BookingException) {
                    if (!transient(error) || attempt == policy.maxAttempts - 1) throw error
                    val delay = policy.initialBackoff.multipliedBy(1L shl attempt)
                    if (!clock.instant().plus(delay).isBefore(booking.deadline)) throw BookingException(BookingError.BOOKING_DEADLINE)
                    events.record(SagaEvent.RETRY, booking)
                    pause.pause(delay)
                }
            }
            error("Unreachable retry state")
        }

        private fun compensate(): Booking {
            if (booking.roomId == null) return compensated()
            repeat(policy.maxAttempts) { attempt ->
                claim = store.renew(claim, policy.lease)
                try {
                    validate(hotel.release(booking), booking, setOf(InventoryStatus.RELEASED))
                    return compensated()
                } catch (error: BookingException) {
                    if (error.reason == BookingError.ROOM_NOT_FOUND) return compensated()
                    if (!transient(error) || attempt == policy.maxAttempts - 1) return deferred()
                    pause.pause(policy.initialBackoff.multipliedBy(1L shl attempt))
                }
            }
            return deferred()
        }

        private fun compensated(): Booking {
            claim = store.compensated(claim)
            events.record(SagaEvent.COMPENSATED, booking)
            return booking
        }

        private fun deferred(): Booking {
            val delay = policy.recoveryBackoff.multipliedBy(1L shl minOf(claim.recoveryFailures, 8)).coerceAtMost(policy.maxRecoveryBackoff)
            val pending = store.defer(claim, delay)
            events.record(SagaEvent.COMPENSATION_PENDING, pending)
            return pending
        }
    }

    private fun transient(error: BookingException) = error.reason in setOf(BookingError.HOTEL_UNAVAILABLE, BookingError.HOTEL_TIMEOUT)

    private fun validate(
        result: InventoryResult,
        booking: Booking,
        statuses: Set<InventoryStatus>,
    ) {
        if (result.attemptId != booking.attemptId || result.bookingId != booking.id || result.roomId != booking.roomId ||
            result.startDate != booking.startDate || result.endDate != booking.endDate || result.status !in statuses
        ) {
            throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
        }
    }
}
