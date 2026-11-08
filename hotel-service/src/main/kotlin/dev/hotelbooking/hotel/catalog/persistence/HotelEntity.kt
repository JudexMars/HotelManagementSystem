package dev.hotelbooking.hotel.catalog.persistence
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

@Entity
@Table(name = "hotels")
class HotelEntity(
    @field:Id @field:Column(name = "id", nullable = false) val id: UUID,
    @field:Column(name = "name", nullable = false, length = 200) var name: String,
    @field:Column(name = "address", nullable = false, length = 500) var address: String,
    @field:Column(name = "deleted", nullable = false) var deleted: Boolean = false,
    @field:Version @field:Column(name = "version", nullable = false) var version: Long? = null,
)
