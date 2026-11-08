package dev.hotelbooking.hotel.inventory.api
import dev.hotelbooking.hotel.catalog.api.RoomResponse
import dev.hotelbooking.hotel.catalog.domain.Room
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
class RoomSearchController(
    private val service: AuthorizedRoomSearchService,
) {
    @GetMapping("/internal/rooms/{id}")
    @SecurityRequirement(name = "serviceAuth")
    fun room(
        @PathVariable id: UUID,
    ): RoomResponse = service.room(id).response()

    @GetMapping("/api/rooms", "/api/rooms/recommend")
    fun available(
        @RequestParam startDate: LocalDate,
        @RequestParam endDate: LocalDate,
        @RequestParam(required = false) hotelId: UUID?,
    ): List<RoomResponse> = service.available(startDate, endDate, hotelId).map { it.response() }

    @GetMapping("/internal/rooms")
    @SecurityRequirement(name = "serviceAuth")
    fun candidates(
        @RequestParam startDate: LocalDate,
        @RequestParam endDate: LocalDate,
        @RequestParam(required = false) hotelId: UUID?,
    ): List<RoomResponse> = service.available(startDate, endDate, hotelId).map { it.response() }
}

private fun Room.response() = RoomResponse(id, hotelId, number, available, timesBooked)
