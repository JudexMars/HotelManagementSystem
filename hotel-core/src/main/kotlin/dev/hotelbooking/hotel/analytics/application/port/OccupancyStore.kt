package dev.hotelbooking.hotel.analytics.application.port

import dev.hotelbooking.hotel.analytics.domain.OccupancySnapshot
import java.time.LocalDate
import java.util.UUID

interface OccupancyStore {
    fun read(
        hotelId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): OccupancySnapshot?
}
