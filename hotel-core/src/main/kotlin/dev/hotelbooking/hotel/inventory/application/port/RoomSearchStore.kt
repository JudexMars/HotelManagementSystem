package dev.hotelbooking.hotel.inventory.application.port

import dev.hotelbooking.hotel.catalog.domain.Room
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface RoomSearchStore {
    fun available(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
        now: Instant,
    ): List<Room>
}
