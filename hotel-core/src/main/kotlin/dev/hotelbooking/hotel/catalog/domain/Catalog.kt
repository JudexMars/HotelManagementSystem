package dev.hotelbooking.hotel.catalog.domain
import java.util.UUID

data class Hotel(
    val id: UUID,
    val name: String,
    val address: String,
)

data class Room(
    val id: UUID,
    val hotelId: UUID,
    val number: String,
    val available: Boolean,
    val timesBooked: Long,
)

data class HotelPage(
    val items: List<Hotel>,
    val total: Long,
)

enum class CatalogError { CATALOG_IN_USE, HOTEL_NOT_FOUND, ROOM_NOT_FOUND, ROOM_NUMBER_TAKEN, INVALID_CATALOG_INPUT, CONCURRENT_UPDATE }

class CatalogException(
    val reason: CatalogError,
) : RuntimeException(reason.name)
