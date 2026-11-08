package dev.hotelbooking.booking.application

import dev.hotelbooking.booking.application.port.BookingPage
import dev.hotelbooking.booking.application.port.BookingStore
import dev.hotelbooking.booking.domain.Actor
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import java.util.UUID

class BookingQueries(
    private val store: BookingStore,
) {
    fun history(
        actor: Actor,
        userId: UUID?,
        page: Int,
        size: Int,
    ): BookingPage {
        if (page < 0 || size !in 1..100 || page.toLong() * size > Int.MAX_VALUE) {
            throw BookingException(BookingError.INVALID_BOOKING_PAGE)
        }
        if (!actor.admin && userId != null && userId != actor.id) throw BookingException(BookingError.BOOKING_FILTER_FORBIDDEN)
        return store.history(if (actor.admin) userId else actor.id, page, size)
    }

    fun get(
        actor: Actor,
        id: UUID,
    ): Booking =
        store.find(id)?.takeIf { actor.admin || it.userId == actor.id }
            ?: throw BookingException(BookingError.BOOKING_NOT_FOUND)
}
