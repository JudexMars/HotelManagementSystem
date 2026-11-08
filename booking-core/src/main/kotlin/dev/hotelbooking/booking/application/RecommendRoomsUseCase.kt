package dev.hotelbooking.booking.application

import dev.hotelbooking.booking.application.port.HotelRoomSearch
import dev.hotelbooking.booking.application.port.InventoryCandidate
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import java.time.LocalDate
import java.util.UUID

class RecommendRoomsUseCase(
    private val search: HotelRoomSearch,
) {
    fun recommendations(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
        traceId: String?,
    ): List<InventoryCandidate> {
        if (!endDate.isAfter(startDate)) throw BookingException(BookingError.INVALID_BOOKING)
        val candidates = search.candidates(startDate, endDate, hotelId, traceId)
        if (candidates.map { it.id }.distinct().size != candidates.size ||
            candidates.any { !it.available || it.timesBooked < 0 || (hotelId != null && it.hotelId != hotelId) }
        ) {
            throw BookingException(BookingError.INVENTORY_PROTOCOL_ERROR)
        }
        return candidates.sortedWith(compareBy<InventoryCandidate> { it.timesBooked }.thenBy { it.id.toString() })
    }
}
