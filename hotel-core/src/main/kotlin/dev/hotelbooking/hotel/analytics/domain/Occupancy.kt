package dev.hotelbooking.hotel.analytics.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RoomOccupancyTotals(
    val roomId: UUID,
    val roomNumber: String,
    val deleted: Boolean,
    val bookingCount: Long,
    val bookedNights: Long,
)

data class OccupancySnapshot(
    val hotelId: UUID,
    val hotelName: String,
    val hotelDeleted: Boolean,
    val rooms: List<RoomOccupancyTotals>,
)

data class RoomOccupancy(
    val roomId: UUID,
    val roomNumber: String,
    val deleted: Boolean,
    val bookingCount: Long,
    val bookedNights: Long,
    val occupancyPercent: BigDecimal,
)

data class OccupancyReport(
    val hotelId: UUID,
    val hotelName: String,
    val hotelDeleted: Boolean,
    val from: LocalDate,
    val to: LocalDate,
    val totalNights: Long,
    val rooms: List<RoomOccupancy>,
)

enum class OccupancyError { INVALID_REPORT_PERIOD, INVALID_REPORT_SORT, HOTEL_NOT_FOUND }

class OccupancyException(
    val reason: OccupancyError,
) : RuntimeException(reason.name)
