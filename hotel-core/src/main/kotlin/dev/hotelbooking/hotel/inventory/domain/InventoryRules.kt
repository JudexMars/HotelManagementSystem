package dev.hotelbooking.hotel.inventory.domain

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class InventoryRoomState(
    val hotelDeleted: Boolean,
    val roomDeleted: Boolean,
    val available: Boolean,
)

data class InventoryTransition(
    val reservation: Reservation,
    val counterDelta: Long = 0,
)

/** Decisions use a snapshot obtained under the adapter's hotel/room/attempt locks. */
object InventoryRules {
    fun hold(
        command: InventoryCommand,
        existing: Reservation?,
        room: InventoryRoomState,
        now: Instant,
        ttl: Duration,
        blocked: () -> Boolean,
    ): InventoryTransition {
        if (existing != null) return InventoryTransition(existing.replay(command, now))
        if (room.hotelDeleted || room.roomDeleted || !room.available || blocked()) {
            throw InventoryException(InventoryError.ROOM_UNAVAILABLE)
        }
        return InventoryTransition(
            Reservation(
                command.attemptId,
                command.bookingId,
                command.roomId,
                command.startDate,
                command.endDate,
                ReservationStatus.HELD,
                now.plus(ttl).truncatedTo(ChronoUnit.MICROS),
            ),
        )
    }

    fun commit(
        command: InventoryCommand,
        existing: Reservation?,
        now: Instant,
    ): InventoryTransition {
        val current = existing?.replay(command, now) ?: throw InventoryException(InventoryError.ATTEMPT_NOT_FOUND)
        if (current.status == ReservationStatus.RESERVED) return InventoryTransition(current)
        return InventoryTransition(current.copy(status = ReservationStatus.RESERVED), 1)
    }

    fun release(
        command: InventoryCommand,
        existing: Reservation?,
    ): InventoryTransition {
        if (existing == null) {
            return InventoryTransition(
                Reservation(
                    command.attemptId,
                    command.bookingId,
                    command.roomId,
                    command.startDate,
                    command.endDate,
                    ReservationStatus.RELEASED,
                    null,
                ),
            )
        }
        existing.requireBinding(command)
        return InventoryTransition(
            existing.copy(status = ReservationStatus.RELEASED),
            if (existing.status == ReservationStatus.RESERVED) -1 else 0,
        )
    }
}
