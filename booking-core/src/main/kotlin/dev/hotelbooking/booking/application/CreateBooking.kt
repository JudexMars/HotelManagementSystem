package dev.hotelbooking.booking.application
import dev.hotelbooking.booking.application.port.BookingRequestIdentity
import java.time.LocalDate
import java.util.UUID

data class CreateBooking(
    val autoSelect: Boolean,
    val roomId: UUID?,
    val hotelId: UUID?,
    val startDate: LocalDate,
    val endDate: LocalDate,
) {
    fun identity(key: String): BookingRequestIdentity {
        val selectedId = if (autoSelect) hotelId else roomId
        return BookingRequestIdentity(key, "$autoSelect|$selectedId|$startDate|$endDate")
    }
}
