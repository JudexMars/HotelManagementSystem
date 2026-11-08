package dev.hotelbooking.booking.application

import dev.hotelbooking.booking.application.port.BookingEvents
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.application.port.SagaEvent
import dev.hotelbooking.booking.domain.Actor
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.CompensationStatus
import java.util.UUID

class CancelBookingUseCase(
    private val store: BookingStore,
    private val saga: BookingSagaRunner,
    private val events: BookingEvents,
) {
    fun cancel(
        actor: Actor,
        id: UUID,
    ): Booking {
        val cancelled = store.cancelByUser(actor, id)
        if (cancelled.compensationStatus == CompensationStatus.DONE) return cancelled
        events.record(SagaEvent.CANCELLED, cancelled)
        return saga.resume(id)
    }
}
