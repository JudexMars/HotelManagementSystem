package dev.hotelbooking.hotel.inventory.persistence
import dev.hotelbooking.hotel.catalog.domain.CatalogError
import dev.hotelbooking.hotel.catalog.domain.CatalogException
import dev.hotelbooking.hotel.catalog.domain.Room
import dev.hotelbooking.hotel.catalog.persistence.HotelRepository
import dev.hotelbooking.hotel.catalog.persistence.RoomRepository
import dev.hotelbooking.hotel.inventory.application.port.RoomSearchStore
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JpaRoomSearchStore(
    private val hotels: HotelRepository,
    private val rooms: RoomRepository,
) : RoomSearchStore {
    @Transactional(readOnly = true)
    override fun available(
        startDate: LocalDate,
        endDate: LocalDate,
        hotelId: UUID?,
        now: Instant,
    ): List<Room> {
        if (hotelId != null && hotels.findById(hotelId).orElse(null)?.deleted != false) {
            throw CatalogException(CatalogError.HOTEL_NOT_FOUND)
        }
        return rooms.available(startDate, endDate, hotelId, now).map { Room(it.id, it.hotelId, it.number, it.available, it.timesBooked) }
    }
}
