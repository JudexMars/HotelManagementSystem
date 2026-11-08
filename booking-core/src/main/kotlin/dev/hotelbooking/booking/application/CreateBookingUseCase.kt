package dev.hotelbooking.booking.application

import dev.hotelbooking.booking.application.port.BookingEvents
import dev.hotelbooking.booking.application.port.BookingRequestAlreadyExists
import dev.hotelbooking.booking.application.port.BookingRequestIdentity
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.application.port.RetryPause
import dev.hotelbooking.booking.application.port.SagaEvent
import dev.hotelbooking.booking.application.port.SavedBookingRequest
import dev.hotelbooking.booking.domain.Actor
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import dev.hotelbooking.booking.domain.BookingStatus
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

class CreateBookingUseCase(
    private val store: BookingStore,
    private val saga: BookingSagaRunner,
    private val clock: Clock,
    private val policy: SagaPolicy,
    private val pause: RetryPause,
    private val events: BookingEvents,
) {
    fun create(
        actor: Actor,
        request: CreateBooking,
        traceId: String,
        idempotencyKey: String?,
    ): BookingCreation {
        if (idempotencyKey == null || idempotencyKey.length !in 1..128 || idempotencyKey.any { it.code !in 33..126 }) {
            throw BookingException(BookingError.INVALID_IDEMPOTENCY_KEY)
        }
        val identity = request.identity(idempotencyKey)
        store.findRequest(actor.id, identity.key)?.let { return replay(it, identity) }

        val missingSelection = if (request.autoSelect) request.hotelId == null else request.roomId == null
        if (missingSelection || !request.endDate.isAfter(request.startDate) ||
            request.startDate.isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        ) {
            throw BookingException(BookingError.INVALID_BOOKING)
        }
        val now = clock.instant()
        val initial =
            try {
                store.create(
                    actor.id,
                    request.roomId.takeUnless { request.autoSelect },
                    request.startDate,
                    request.endDate,
                    now,
                    now.plus(policy.budget),
                    traceId,
                    policy.lease,
                    identity,
                    request.autoSelect,
                    request.hotelId.takeIf { request.autoSelect },
                )
            } catch (_: BookingRequestAlreadyExists) {
                return replay(requireNotNull(store.findRequest(actor.id, identity.key)), identity)
            }
        events.record(SagaEvent.CREATED, initial.booking)
        var booking = saga.execute(initial)
        while (booking.status == BookingStatus.PENDING) {
            pause.pause(Duration.ofMillis(25))
            booking = saga.resume(booking.id)
        }
        if (booking.status ==
            BookingStatus.CANCELLED
        ) {
            throw BookingCreationException(booking.failureCode ?: BookingError.BOOKING_DEADLINE, booking)
        }
        return BookingCreation(booking, replayed = false)
    }

    private fun replay(
        saved: SavedBookingRequest,
        identity: BookingRequestIdentity,
    ): BookingCreation {
        if (saved.fingerprint != identity.fingerprint) throw BookingException(BookingError.IDEMPOTENCY_CONFLICT)
        val booking = saved.booking
        val current =
            if (booking.status == BookingStatus.PENDING &&
                !booking.deadline.isAfter(clock.instant())
            ) {
                saga.resume(booking.id)
            } else {
                booking
            }
        return BookingCreation(current, replayed = true)
    }
}
