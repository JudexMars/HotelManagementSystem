package dev.hotelbooking.hotel.catalog.api

import dev.hotelbooking.hotel.catalog.application.CatalogService
import dev.hotelbooking.hotel.catalog.domain.Hotel
import dev.hotelbooking.hotel.catalog.domain.HotelPage
import dev.hotelbooking.hotel.catalog.domain.Room
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@PreAuthorize("hasAnyRole('USER','ADMIN')")
class AuthorizedCatalogService(
    private val delegate: CatalogService,
) {
    @PreAuthorize("hasRole('ADMIN')")
    fun createHotel(
        name: String,
        address: String,
    ): Hotel = delegate.createHotel(name, address)

    fun hotel(id: UUID): Hotel = delegate.hotel(id)

    fun hotels(
        page: Int,
        size: Int,
        name: String?,
        address: String?,
    ): HotelPage = delegate.hotels(page, size, name, address)

    @PreAuthorize("hasRole('ADMIN')")
    fun updateHotel(
        id: UUID,
        name: String,
        address: String,
    ): Hotel = delegate.updateHotel(id, name, address)

    @PreAuthorize("hasRole('ADMIN')")
    fun createRoom(
        hotelId: UUID,
        number: String,
        available: Boolean,
    ): Room = delegate.createRoom(hotelId, number, available)

    fun room(id: UUID): Room = delegate.room(id)

    @PreAuthorize("hasRole('ADMIN')")
    fun updateRoom(
        id: UUID,
        number: String,
        available: Boolean,
    ): Room = delegate.updateRoom(id, number, available)

    @PreAuthorize("hasRole('ADMIN')")
    fun deleteHotel(id: UUID) = delegate.deleteHotel(id)

    @PreAuthorize("hasRole('ADMIN')")
    fun deleteRoom(id: UUID) = delegate.deleteRoom(id)
}
