package dev.hotelbooking.hotel.analytics.application

import dev.hotelbooking.hotel.analytics.application.port.OccupancyStore
import dev.hotelbooking.hotel.analytics.domain.OccupancyError
import dev.hotelbooking.hotel.analytics.domain.OccupancyException
import dev.hotelbooking.hotel.analytics.domain.OccupancyReport
import dev.hotelbooking.hotel.analytics.domain.RoomOccupancy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class OccupancyService(
    private val store: OccupancyStore,
) {
    fun report(
        hotelId: UUID,
        from: LocalDate,
        to: LocalDate,
        sort: String,
        direction: String,
    ): OccupancyReport {
        if (!to.isAfter(from)) throw OccupancyException(OccupancyError.INVALID_REPORT_PERIOD)
        if (sort !in setOf("occupancy", "bookings") || direction !in setOf("asc", "desc")) {
            throw OccupancyException(OccupancyError.INVALID_REPORT_SORT)
        }
        val snapshot = store.read(hotelId, from, to) ?: throw OccupancyException(OccupancyError.HOTEL_NOT_FOUND)
        val totalNights = ChronoUnit.DAYS.between(from, to)
        val rooms =
            snapshot.rooms.map {
                val percentage =
                    BigDecimal
                        .valueOf(it.bookedNights)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalNights), 2, RoundingMode.HALF_UP)
                RoomOccupancy(it.roomId, it.roomNumber, it.deleted, it.bookingCount, it.bookedNights, percentage)
            }
        // Every room has the same denominator, so exact occupancy ordering is night ordering, before rounding.
        val metric: (RoomOccupancy) -> Long = if (sort == "occupancy") { it -> it.bookedNights } else { it -> it.bookingCount }
        val order = if (direction == "desc") compareByDescending(metric) else compareBy(metric)
        return OccupancyReport(
            snapshot.hotelId,
            snapshot.hotelName,
            snapshot.hotelDeleted,
            from,
            to,
            totalNights,
            rooms.sortedWith(order.thenBy { it.roomId.toString() }),
        )
    }
}
