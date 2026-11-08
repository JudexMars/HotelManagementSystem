package dev.hotelbooking.booking.application
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError

data class BookingCreation(
    val booking: Booking,
    val replayed: Boolean,
)

class BookingCreationException(
    val reason: BookingError,
    val booking: Booking,
) : RuntimeException(reason.name)
