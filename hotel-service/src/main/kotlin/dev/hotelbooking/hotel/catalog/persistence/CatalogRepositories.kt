package dev.hotelbooking.hotel.catalog.persistence
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface HotelRepository :
    JpaRepository<HotelEntity, UUID>,
    JpaSpecificationExecutor<HotelEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HotelEntity h where h.id = :id")
    fun locked(
        @Param("id") id: UUID,
    ): HotelEntity?
}

interface RoomRepository : JpaRepository<RoomEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoomEntity r where r.hotelId = :hotelId order by r.id")
    fun lockedByHotel(
        @Param("hotelId") hotelId: UUID,
    ): List<RoomEntity>

    @Query(
        """select r from RoomEntity r, HotelEntity h where r.hotelId = h.id
        and r.deleted = false and h.deleted = false and r.available = true
        and (:hotelId is null or r.hotelId = :hotelId)
        and not exists (select v.attemptId from ReservationEntity v where v.roomId = r.id
            and v.startDate < :endDate and v.endDate > :startDate
            and (v.status = dev.hotelbooking.hotel.inventory.domain.ReservationStatus.RESERVED
                or (v.status = dev.hotelbooking.hotel.inventory.domain.ReservationStatus.HELD and v.expiresAt > :now)))
        order by r.timesBooked, r.id""",
    )
    fun available(
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        @Param("hotelId") hotelId: UUID?,
        @Param("now") now: Instant,
    ): List<RoomEntity>

    @Query("select r.hotelId from RoomEntity r where r.id = :id")
    fun hotelId(
        @Param("id") id: UUID,
    ): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoomEntity r where r.id = :id")
    fun locked(
        @Param("id") id: UUID,
    ): RoomEntity?
}
