package dev.hotelbooking.booking.persistence
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.BookingStep
import dev.hotelbooking.booking.domain.CompensationStatus
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "bookings")
class BookingEntity(
    @field:Id val id: UUID,
    @field:Column(name = "user_id", nullable = false) val userId: UUID,
    @field:Column(name = "room_id") var roomId: UUID?,
    @field:Column(name = "hotel_id") var hotelId: UUID?,
    @field:Column(name = "start_date", nullable = false) val startDate: LocalDate,
    @field:Column(name = "end_date", nullable = false) val endDate: LocalDate,
    @field:Column(name = "created_at", nullable = false) val createdAt: Instant,
    @field:Enumerated(EnumType.STRING) @field:Column(nullable = false, length = 16) var status: BookingStatus,
    @field:Enumerated(EnumType.STRING) @field:Column(nullable = false, length = 32) var step: BookingStep,
    @field:Column(name = "attempt_id", nullable = false) var attemptId: UUID,
    @field:Enumerated(EnumType.STRING) @field:Column(name = "compensation_status", nullable = false, length = 16)
    var compensationStatus: CompensationStatus,
    @field:Column(nullable = false) val deadline: Instant,
    @field:Column(name = "trace_id", nullable = false, length = 64) val traceId: String,
    @field:Enumerated(EnumType.STRING) @field:Column(name = "failure_code", length = 64) var failureCode: BookingError? = null,
    @field:Column(name = "claim_token") var claimToken: UUID? = null,
    @field:Column(name = "lease_until") var leaseUntil: Instant? = null,
    @field:Column(name = "next_action_at", nullable = false) var nextActionAt: Instant = createdAt,
    @field:Column(name = "recovery_failures", nullable = false) var recoveryFailures: Int = 0,
    // Used by Spring Data in findByUserIdAndIdempotencyKey.
    @Suppress("unused")
    @field:Column(name = "idempotency_key", length = 128) val idempotencyKey: String? = null,
    @field:Column(name = "request_fingerprint", length = 256) val requestFingerprint: String? = null,
    @field:Column(name = "auto_select", nullable = false) val autoSelect: Boolean = false,
    @field:Column(name = "candidate_index", nullable = false) var candidateIndex: Int = 0,
    @field:Column(name = "candidate_release_pending", nullable = false) var candidateReleasePending: Boolean = false,
    @field:ElementCollection
    @field:CollectionTable(name = "booking_candidates", joinColumns = [JoinColumn(name = "booking_id")])
    @field:OrderColumn(name = "candidate_order")
    @field:Column(name = "room_id", nullable = false)
    var candidates: MutableList<UUID> = mutableListOf(),
    @field:Version var version: Long? = null,
)
