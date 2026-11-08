package dev.hotelbooking.hotel.inventory.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReservationStatus { HELD, RESERVED, RELEASED, EXPIRED }

enum class InventoryError {
    INVALID_PERIOD,
    ROOM_NOT_FOUND,
    ROOM_UNAVAILABLE,
    ATTEMPT_NOT_FOUND,
    ATTEMPT_CONFLICT,
    HOLD_EXPIRED,
    ATTEMPT_RELEASED,
}

class InventoryException(
    val reason: InventoryError,
) : RuntimeException(reason.name)

data class InventoryCommand(
    val attemptId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class Reservation(
    val attemptId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: ReservationStatus,
    val expiresAt: Instant?,
) {
    fun requireBinding(command: InventoryCommand) {
        if (attemptId != command.attemptId || bookingId != command.bookingId || roomId != command.roomId ||
            startDate != command.startDate || endDate != command.endDate
        ) {
            throw InventoryException(InventoryError.ATTEMPT_CONFLICT)
        }
    }

    fun replay(
        command: InventoryCommand,
        now: Instant,
    ): Reservation {
        requireBinding(command)
        when (status) {
            ReservationStatus.HELD -> if (expiresAt?.isAfter(now) != true) throw InventoryException(InventoryError.HOLD_EXPIRED)
            ReservationStatus.EXPIRED -> throw InventoryException(InventoryError.HOLD_EXPIRED)
            ReservationStatus.RELEASED -> throw InventoryException(InventoryError.ATTEMPT_RELEASED)
            ReservationStatus.RESERVED -> Unit
        }
        return this
    }
}
