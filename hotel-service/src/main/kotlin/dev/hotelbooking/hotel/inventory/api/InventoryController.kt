package dev.hotelbooking.hotel.inventory.api
import dev.hotelbooking.hotel.inventory.domain.InventoryCommand
import dev.hotelbooking.hotel.inventory.domain.Reservation
import dev.hotelbooking.hotel.inventory.domain.ReservationStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class InventoryRequest(
    val attemptId: UUID,
    val bookingId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class ReservationResponse(
    val attemptId: UUID,
    val bookingId: UUID,
    val roomId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: ReservationStatus,
    val expiresAt: Instant?,
)

@RestController
@RequestMapping("/internal/rooms")
@SecurityRequirement(name = "serviceAuth")
class InventoryController(
    private val service: AuthorizedInventoryService,
) {
    @PostMapping("/{id}/release")
    fun release(
        @PathVariable id: UUID,
        @RequestBody body: InventoryRequest,
    ): ReservationResponse =
        service.release(InventoryCommand(body.attemptId, body.bookingId, id, body.startDate, body.endDate)).toResponse()

    @PostMapping("/{id}/commit")
    fun commit(
        @PathVariable id: UUID,
        @RequestBody body: InventoryRequest,
    ): ReservationResponse = service.commit(InventoryCommand(body.attemptId, body.bookingId, id, body.startDate, body.endDate)).toResponse()

    @PostMapping("/{id}/hold")
    fun hold(
        @PathVariable id: UUID,
        @RequestBody body: InventoryRequest,
    ): ReservationResponse = service.hold(InventoryCommand(body.attemptId, body.bookingId, id, body.startDate, body.endDate)).toResponse()
}

private fun Reservation.toResponse() = ReservationResponse(attemptId, bookingId, roomId, startDate, endDate, status, expiresAt)
