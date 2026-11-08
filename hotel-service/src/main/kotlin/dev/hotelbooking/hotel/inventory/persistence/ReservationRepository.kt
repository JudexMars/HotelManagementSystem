package dev.hotelbooking.hotel.inventory.persistence
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ReservationRepository : JpaRepository<ReservationEntity, UUID> {
    @Query(
        """select count(r) from ReservationEntity r where r.roomId = :roomId
        and ((r.status = dev.hotelbooking.hotel.inventory.domain.ReservationStatus.HELD and r.expiresAt > :now)
            or (r.status = dev.hotelbooking.hotel.inventory.domain.ReservationStatus.RESERVED and r.endDate > :today))""",
    )
    fun preventingDeletion(
        @Param("roomId") roomId: UUID,
        @Param("now") now: Instant,
        @Param("today") today: LocalDate,
    ): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservationEntity r where r.attemptId = :id")
    fun locked(
        @Param("id") id: UUID,
    ): ReservationEntity?

    @Query(
        """select count(r) from ReservationEntity r where r.roomId = :roomId
  and r.startDate < :endDate and r.endDate > :startDate
  and (r.status = dev.hotelbooking.hotel.inventory.domain.ReservationStatus.RESERVED
   or (r.status = dev.hotelbooking.hotel.inventory.domain.ReservationStatus.HELD and r.expiresAt > :now))""",
    )
    fun blocking(
        @Param("roomId") roomId: UUID,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        @Param("now") now: Instant,
    ): Long
}
