package dev.hotelbooking.hotel.catalog.application
import dev.hotelbooking.hotel.catalog.application.port.CatalogStore
import dev.hotelbooking.hotel.catalog.domain.CatalogError
import dev.hotelbooking.hotel.catalog.domain.CatalogException
import dev.hotelbooking.hotel.catalog.domain.Hotel
import dev.hotelbooking.hotel.catalog.domain.HotelPage
import dev.hotelbooking.hotel.catalog.domain.Room
import java.util.UUID

class CatalogService(
    private val store: CatalogStore,
) {
    fun createHotel(
        name: String,
        address: String,
    ): Hotel {
        validate(name, 200)
        validate(address, 500)
        return store.createHotel(name, address)
    }

    fun hotel(id: UUID): Hotel = store.hotel(id)

    fun hotels(
        page: Int,
        size: Int,
        name: String?,
        address: String?,
    ): HotelPage {
        if (page < 0 || size !in 1..100 || page.toLong() * size > Int.MAX_VALUE) throw CatalogException(CatalogError.INVALID_CATALOG_INPUT)
        return store.hotels(page, size, name, address)
    }

    fun updateHotel(
        id: UUID,
        name: String,
        address: String,
    ): Hotel {
        validate(name, 200)
        validate(address, 500)
        return store.updateHotel(id, name, address)
    }

    fun createRoom(
        hotelId: UUID,
        number: String,
        available: Boolean,
    ): Room {
        validate(number, 50)
        return store.createRoom(hotelId, number, available)
    }

    fun room(id: UUID): Room = store.room(id)

    fun updateRoom(
        id: UUID,
        number: String,
        available: Boolean,
    ): Room {
        validate(number, 50)
        return store.updateRoom(id, number, available)
    }

    fun deleteHotel(id: UUID) = store.deleteHotel(id)

    fun deleteRoom(id: UUID) = store.deleteRoom(id)

    private fun validate(
        value: String,
        max: Int,
    ) {
        if (value.isBlank() ||
            value.length > max
        ) {
            throw CatalogException(CatalogError.INVALID_CATALOG_INPUT)
        }
    }
}
