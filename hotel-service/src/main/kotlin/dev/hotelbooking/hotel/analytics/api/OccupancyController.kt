package dev.hotelbooking.hotel.analytics.api

import dev.hotelbooking.hotel.analytics.application.OccupancyService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RoomOccupancyResponse(
    val roomId: UUID,
    val roomNumber: String,
    val deleted: Boolean,
    val bookingCount: Long,
    val bookedNights: Long,
    val occupancyPercent: BigDecimal,
)

data class OccupancyResponse(
    val hotelId: UUID,
    val hotelName: String,
    val hotelDeleted: Boolean,
    val from: LocalDate,
    val to: LocalDate,
    val totalNights: Long,
    val rooms: List<RoomOccupancyResponse>,
)

@RestController
@PreAuthorize("hasRole('ADMIN')")
class OccupancyController(
    private val service: OccupancyService,
) {
    @GetMapping("/api/hotels/{id}/occupancy")
    @Operation(summary = "Загрузка номеров за [from,to), включая удалённый каталог (ADMIN)")
    fun report(
        @PathVariable id: UUID,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @RequestParam(defaultValue = "occupancy") sort: String,
        @RequestParam(defaultValue = "desc") direction: String,
    ): OccupancyResponse {
        val report = service.report(id, from, to, sort, direction)
        return OccupancyResponse(
            report.hotelId,
            report.hotelName,
            report.hotelDeleted,
            report.from,
            report.to,
            report.totalNights,
            report.rooms.map {
                RoomOccupancyResponse(
                    it.roomId,
                    it.roomNumber,
                    it.deleted,
                    it.bookingCount,
                    it.bookedNights,
                    it.occupancyPercent,
                )
            },
        )
    }
}
