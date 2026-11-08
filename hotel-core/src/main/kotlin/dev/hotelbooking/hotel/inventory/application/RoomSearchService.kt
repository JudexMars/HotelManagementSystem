package dev.hotelbooking.hotel.inventory.application
import dev.hotelbooking.hotel.catalog.application.port.CatalogStore
import dev.hotelbooking.hotel.catalog.domain.Room
import dev.hotelbooking.hotel.inventory.application.port.RoomSearchStore
import dev.hotelbooking.hotel.inventory.domain.InventoryError
import dev.hotelbooking.hotel.inventory.domain.InventoryException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class RoomSearchService(
    private val store: RoomSearchStore,
    private val clock: Clock,
    private val catalog: CatalogStore,
) {
    fun room(id: UUID): Room = catalog.room(id)

    fun available(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
    ): List<Room> {
        if (!endDate.isAfter(startDate)) throw InventoryException(InventoryError.INVALID_PERIOD)
        return store.available(startDate, endDate, hotelId, clock.instant())
    }
}
