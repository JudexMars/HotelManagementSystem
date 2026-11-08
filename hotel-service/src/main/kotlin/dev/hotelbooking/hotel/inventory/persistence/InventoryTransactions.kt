package dev.hotelbooking.hotel.inventory.persistence
import dev.hotelbooking.hotel.catalog.persistence.HotelEntity
import dev.hotelbooking.hotel.catalog.persistence.HotelRepository
import dev.hotelbooking.hotel.catalog.persistence.RoomEntity
import dev.hotelbooking.hotel.catalog.persistence.RoomRepository
import dev.hotelbooking.hotel.configuration.InventoryProperties
import dev.hotelbooking.hotel.inventory.domain.InventoryCommand
import dev.hotelbooking.hotel.inventory.domain.InventoryError
import dev.hotelbooking.hotel.inventory.domain.InventoryException
import dev.hotelbooking.hotel.inventory.domain.InventoryRoomState
import dev.hotelbooking.hotel.inventory.domain.InventoryRules
import dev.hotelbooking.hotel.inventory.domain.InventoryTransition
import dev.hotelbooking.hotel.inventory.domain.Reservation
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Repository
class InventoryTransactions(
    private val hotels: HotelRepository,
    private val rooms: RoomRepository,
    private val reservations: ReservationRepository,
    private val clock: Clock,
    private val properties: InventoryProperties,
) {
    @Transactional
    fun hold(command: InventoryCommand): Reservation {
        val locked = lock(command)
        val decision =
            InventoryRules.hold(
                command,
                locked.reservation?.toDomain(),
                InventoryRoomState(locked.hotel.deleted, locked.room.deleted, locked.room.available),
                locked.now,
                properties.holdTtl,
            ) { reservations.blocking(command.roomId, command.startDate, command.endDate, locked.now) > 0 }
        return persist(locked, decision)
    }

    @Transactional
    fun commit(command: InventoryCommand): Reservation {
        val locked = lock(command)
        return persist(locked, InventoryRules.commit(command, locked.reservation?.toDomain(), locked.now))
    }

    @Transactional
    fun release(command: InventoryCommand): Reservation {
        val locked = lock(command)
        return persist(locked, InventoryRules.release(command, locked.reservation?.toDomain()))
    }

    private fun persist(
        locked: LockedInventory,
        decision: InventoryTransition,
    ): Reservation {
        val value = decision.reservation
        val entity =
            locked.reservation ?: ReservationEntity(
                value.attemptId,
                value.bookingId,
                value.roomId,
                value.startDate,
                value.endDate,
                value.status,
                value.expiresAt,
                locked.now.truncatedTo(ChronoUnit.MICROS),
            )
        entity.status = value.status
        locked.room.timesBooked += decision.counterDelta
        // The state and counter remain in the same transaction and under the original locks.
        if (locked.reservation == null) reservations.saveAndFlush(entity) else reservations.flush()
        return entity.toDomain()
    }

    private fun lock(command: InventoryCommand): LockedInventory {
        val hotelId = rooms.hotelId(command.roomId) ?: throw InventoryException(InventoryError.ROOM_NOT_FOUND)
        val hotel = hotels.locked(hotelId) ?: throw InventoryException(InventoryError.ROOM_NOT_FOUND)
        val room = rooms.locked(command.roomId) ?: throw InventoryException(InventoryError.ROOM_NOT_FOUND)
        val reservation = reservations.locked(command.attemptId)
        return LockedInventory(hotel, room, reservation, clock.instant())
    }

    private data class LockedInventory(
        val hotel: HotelEntity,
        val room: RoomEntity,
        val reservation: ReservationEntity?,
        val now: Instant,
    )
}

private fun ReservationEntity.toDomain() = Reservation(attemptId, bookingId, roomId, startDate, endDate, status, expiresAt)
