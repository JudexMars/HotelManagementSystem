package dev.hotelbooking.booking.application.port

import dev.hotelbooking.booking.domain.Booking

fun interface BookingEvents {
    fun record(
        event: SagaEvent,
        booking: Booking,
    )
}

enum class SagaEvent {
    CREATED,
    HOLD_REQUESTED,
    CANDIDATE_SELECTED,
    CANDIDATE_REJECTED,
    CANDIDATE_RELEASED,
    COMMIT_REQUESTED,
    CONFIRMED,
    CANCELLED,
    COMPENSATED,
    COMPENSATION_PENDING,
    RETRY,
    INTERRUPTED,
}
