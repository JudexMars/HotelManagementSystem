package dev.hotelbooking.hotel.catalog.persistence
import dev.hotelbooking.hotel.catalog.application.port.CatalogStore
import dev.hotelbooking.hotel.catalog.domain.CatalogError
import dev.hotelbooking.hotel.catalog.domain.CatalogException
import dev.hotelbooking.hotel.catalog.domain.Hotel
import dev.hotelbooking.hotel.catalog.domain.HotelPage
import dev.hotelbooking.hotel.catalog.domain.Room
import dev.hotelbooking.hotel.inventory.persistence.ReservationRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Repository
@Transactional
class JpaCatalogStore(
    private val hotels: HotelRepository,
    private val rooms: RoomRepository,
    private val reservations: ReservationRepository,
    private val clock: Clock,
) : CatalogStore {
    override fun createHotel(
        name: String,
        address: String,
    ): Hotel = hotels.saveAndFlush(HotelEntity(UUID.randomUUID(), name, address)).toDomain()

    @Transactional(readOnly = true)
    override fun hotel(id: UUID): Hotel = activeHotel(id).toDomain()

    @Transactional(readOnly = true)
    override fun hotels(
        page: Int,
        size: Int,
        name: String?,
        address: String?,
    ): HotelPage {
        val spec =
            Specification<HotelEntity> { root, _, cb ->
                val filters = mutableListOf(cb.isFalse(root.get("deleted")))
                name?.let { filters += cb.like(cb.lower(root.get("name")), "%${escape(it.lowercase())}%", '\\') }
                address?.let { filters += cb.like(cb.lower(root.get("address")), "%${escape(it.lowercase())}%", '\\') }
                cb.and(*filters.toTypedArray())
            }
        val result = hotels.findAll(spec, PageRequest.of(page, size, Sort.by("name", "id")))
        return HotelPage(result.content.map { it.toDomain() }, result.totalElements)
    }

    override fun updateHotel(
        id: UUID,
        name: String,
        address: String,
    ): Hotel =
        conflicts {
            val h = lockedHotel(id)
            h.name = name
            h.address = address
            hotels.flush()
            h.toDomain()
        }

    override fun createRoom(
        hotelId: UUID,
        number: String,
        available: Boolean,
    ): Room =
        conflicts {
            lockedHotel(hotelId)
            rooms.saveAndFlush(RoomEntity(UUID.randomUUID(), hotelId, number, available)).toDomain()
        }

    @Transactional(readOnly = true)
    override fun room(id: UUID): Room = activeRoom(id).toDomain()

    override fun updateRoom(
        id: UUID,
        number: String,
        available: Boolean,
    ): Room =
        conflicts {
            val room = lockedRoom(id)
            room.number = number
            room.available = available
            rooms.flush()
            room.toDomain()
        }

    override fun deleteRoom(id: UUID) {
        val room = lockedRoom(id)
        val now = clock.instant()
        if (reservations.preventingDeletion(id, now, LocalDate.ofInstant(now, ZoneOffset.UTC)) > 0) {
            throw CatalogException(CatalogError.CATALOG_IN_USE)
        }
        room.deleted = true
        rooms.flush()
    }

    override fun deleteHotel(id: UUID) {
        val hotel = lockedHotel(id)
        val hotelRooms = rooms.lockedByHotel(id)
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (hotelRooms.any { reservations.preventingDeletion(it.id, now, today) > 0 }) {
            throw CatalogException(CatalogError.CATALOG_IN_USE)
        }
        hotelRooms.forEach { it.deleted = true }
        hotel.deleted = true
        hotels.flush()
    }

    private fun lockedHotel(id: UUID): HotelEntity =
        hotels.locked(id)?.takeUnless { it.deleted }
            ?: throw CatalogException(CatalogError.HOTEL_NOT_FOUND)

    private fun lockedRoom(id: UUID): RoomEntity {
        val hotelId = rooms.hotelId(id) ?: throw CatalogException(CatalogError.ROOM_NOT_FOUND)
        lockedHotel(hotelId)
        return rooms.locked(id)?.takeUnless { it.deleted } ?: throw CatalogException(CatalogError.ROOM_NOT_FOUND)
    }

    private fun activeHotel(id: UUID): HotelEntity =
        hotels.findById(id).getOrNull()?.takeUnless { it.deleted }
            ?: throw CatalogException(CatalogError.HOTEL_NOT_FOUND)

    private fun activeRoom(id: UUID): RoomEntity {
        val room = rooms.findById(id).getOrNull()?.takeUnless { it.deleted } ?: throw CatalogException(CatalogError.ROOM_NOT_FOUND)
        activeHotel(room.hotelId)
        return room
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun <T> conflicts(block: () -> T): T =
        try {
            block()
        } catch (_: DataIntegrityViolationException) {
            throw CatalogException(CatalogError.ROOM_NUMBER_TAKEN)
        } catch (_: OptimisticLockingFailureException) {
            throw CatalogException(CatalogError.CONCURRENT_UPDATE)
        }
}

private fun HotelEntity.toDomain() = Hotel(id, name, address)

private fun RoomEntity.toDomain() = Room(id, hotelId, number, available, timesBooked)
