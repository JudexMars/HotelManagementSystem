package dev.hotelbooking.hotel.inventory.persistence
import dev.hotelbooking.hotel.inventory.domain.ReservationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "reservations")
class ReservationEntity(
    @field:Id @field:Column(name = "attempt_id", nullable = false) val attemptId: UUID,
    @field:Column(name = "booking_id", nullable = false) val bookingId: UUID,
    @field:Column(name = "room_id", nullable = false) val roomId: UUID,
    @field:Column(name = "start_date", nullable = false) val startDate: LocalDate,
    @field:Column(name = "end_date", nullable = false) val endDate: LocalDate,
    @field:Enumerated(EnumType.STRING) @field:Column(name = "status", nullable = false, length = 16) var status: ReservationStatus,
    @field:Column(name = "expires_at") val expiresAt: Instant?,
    // Hibernate persists this audit timestamp through field access.
    @Suppress("unused")
    @field:Column(name = "created_at", nullable = false) val createdAt: Instant,
    @field:Version @field:Column(name = "version", nullable = false) var version: Long? = null,
)
