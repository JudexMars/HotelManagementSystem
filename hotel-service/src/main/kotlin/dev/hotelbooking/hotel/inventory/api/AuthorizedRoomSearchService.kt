package dev.hotelbooking.hotel.inventory.api

import dev.hotelbooking.hotel.catalog.domain.Room
import dev.hotelbooking.hotel.inventory.application.RoomSearchService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

@Service
class AuthorizedRoomSearchService(
    private val delegate: RoomSearchService,
) {
    @PreAuthorize("hasAuthority('SCOPE_inventory:read')")
    fun room(id: UUID): Room = delegate.room(id)

    @PreAuthorize("hasAnyRole('USER', 'ADMIN') or hasAuthority('SCOPE_inventory:read')")
    fun available(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
    ): List<Room> = delegate.available(startDate, endDate, hotelId)
}
