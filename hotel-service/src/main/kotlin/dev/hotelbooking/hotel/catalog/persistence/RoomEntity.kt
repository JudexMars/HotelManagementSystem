package dev.hotelbooking.hotel.catalog.persistence
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

@Entity
@Table(name = "rooms")
class RoomEntity(
    @field:Id @field:Column(name = "id", nullable = false) val id: UUID,
    @field:Column(name = "hotel_id", nullable = false) val hotelId: UUID,
    @field:Column(name = "number", nullable = false, length = 50) var number: String,
    @field:Column(name = "available", nullable = false) var available: Boolean,
    @field:Column(name = "times_booked", nullable = false) var timesBooked: Long = 0,
    @field:Column(name = "deleted", nullable = false) var deleted: Boolean = false,
    @field:Version @field:Column(name = "version", nullable = false) var version: Long? = null,
)
