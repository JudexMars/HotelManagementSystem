package dev.hotelbooking.booking.application.port
import dev.hotelbooking.booking.domain.Booking
import java.time.LocalDate
import java.util.UUID

interface HotelInventory {
    fun room(
        id: UUID,
        traceId: String? = null,
    ): InventoryRoom

    fun hold(booking: Booking): InventoryResult

    fun commit(booking: Booking): InventoryResult

    fun release(booking: Booking): InventoryResult
}

data class InventoryRoom(
    val id: UUID,
    val hotelId: UUID,
)

data class InventoryResult(
    val attemptId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: InventoryStatus,
)

enum class InventoryStatus { HELD, RESERVED, RELEASED, EXPIRED }
