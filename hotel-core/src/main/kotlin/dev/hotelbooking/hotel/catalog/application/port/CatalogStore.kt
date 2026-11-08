package dev.hotelbooking.hotel.catalog.application.port

import dev.hotelbooking.hotel.catalog.domain.Hotel
import dev.hotelbooking.hotel.catalog.domain.HotelPage
import dev.hotelbooking.hotel.catalog.domain.Room
import java.util.UUID

interface CatalogStore {
    fun deleteHotel(id: UUID)

    fun deleteRoom(id: UUID)

    fun createHotel(
        name: String,
        address: String,
    ): Hotel

    fun hotel(id: UUID): Hotel

    fun hotels(
        page: Int,
        size: Int,
        name: String?,
        address: String?,
    ): HotelPage

    fun updateHotel(
        id: UUID,
        name: String,
        address: String,
    ): Hotel

    fun createRoom(
        hotelId: UUID,
        number: String,
        available: Boolean,
    ): Room

    fun room(id: UUID): Room

    fun updateRoom(
        id: UUID,
        number: String,
        available: Boolean,
    ): Room
}
