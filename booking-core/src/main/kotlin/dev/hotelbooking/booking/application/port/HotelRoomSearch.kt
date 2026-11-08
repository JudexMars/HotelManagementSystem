package dev.hotelbooking.booking.application.port

import java.time.LocalDate
import java.util.UUID

interface HotelRoomSearch {
    fun candidates(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
        traceId: String?,
    ): List<InventoryCandidate>
}

data class InventoryCandidate(
    val id: UUID,
    val hotelId: UUID,
    val number: String,
    val available: Boolean,
    val timesBooked: Long,
)
