package dev.hotelbooking.booking.api
import com.fasterxml.jackson.annotation.JsonProperty
import dev.hotelbooking.booking.application.BookingQueries
import dev.hotelbooking.booking.application.CancelBookingUseCase
import dev.hotelbooking.booking.application.CreateBooking
import dev.hotelbooking.booking.application.CreateBookingUseCase
import dev.hotelbooking.booking.application.RecommendRoomsUseCase
import dev.hotelbooking.booking.domain.Actor
import dev.hotelbooking.booking.domain.Booking
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.BookingStep
import dev.hotelbooking.booking.domain.CompensationStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateBookingRequest(
    @param:JsonProperty(required = true) val autoSelect: Boolean,
    val roomId: UUID? = null,
    val hotelId: UUID? = null,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class BookingResponse(
    val id: UUID,
    val userId: UUID,
    val roomId: UUID?,
    val hotelId: UUID?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val createdAt: Instant,
    val status: BookingStatus,
    val step: BookingStep,
    val attemptId: UUID,
    val compensationStatus: CompensationStatus?,
)

@RestController
@RequestMapping("/api/bookings")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
class BookingController(
    private val createBooking: CreateBookingUseCase,
    private val cancelBooking: CancelBookingUseCase,
    private val queries: BookingQueries,
    private val recommendations: RecommendRoomsUseCase,
) {
    @PostMapping
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Бронь создана и подтверждена", useReturnTypeSchema = true),
            ApiResponse(
                responseCode = "200",
                description = "Повтор запроса: бронь уже CONFIRMED или CANCELLED",
                useReturnTypeSchema = true,
            ),
            ApiResponse(responseCode = "202", description = "Повтор запроса: оформление ещё продолжается", useReturnTypeSchema = true),
        ],
    )
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: CreateBookingRequest,
        servletRequest: HttpServletRequest,
        @Parameter(
            required = true,
            description = "Ключ операции: 1–128 печатных ASCII-символов без пробелов, уникален в пределах пользователя",
        )
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<BookingResponse> {
        val creation =
            createBooking.create(
                jwt.toActor(),
                CreateBooking(request.autoSelect, request.roomId, request.hotelId, request.startDate, request.endDate),
                servletRequest.getAttribute("traceId").toString(),
                idempotencyKey,
            )
        val booking = creation.booking
        val status =
            if (!creation.replayed) {
                201
            } else if (booking.status == BookingStatus.PENDING) {
                202
            } else {
                200
            }
        return ResponseEntity.status(status).location(URI.create("/api/bookings/${booking.id}")).body(booking.toResponse())
    }

    @DeleteMapping("/{id}")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Бронь отменена, освобождение подтверждено"),
            ApiResponse(responseCode = "202", description = "Отмена принята, освобождение продолжается фоном"),
            ApiResponse(responseCode = "404", description = "Бронь не найдена или недоступна пользователю"),
            ApiResponse(responseCode = "409", description = "Новая отмена запрещена с даты заезда UTC"),
        ],
    )
    fun cancel(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
    ): ResponseEntity<BookingResponse> {
        val booking = cancelBooking.cancel(jwt.toActor(), id)
        val status = if (booking.compensationStatus == CompensationStatus.DONE) 200 else 202
        return ResponseEntity.status(status).location(URI.create("/api/bookings/${booking.id}")).body(booking.toResponse())
    }

    @GetMapping
    @Operation(summary = "История бронирований: USER — свои, ADMIN — все или выбранного пользователя")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @Parameter(description = "ADMIN: любой пользователь, включая удалённого; USER: только собственный ID, чужой фильтр даёт 403")
        @RequestParam(required = false) userId: UUID?,
    ): BookingPageResponse {
        val result = queries.history(jwt.toActor(), userId, page, size)
        return BookingPageResponse(result.bookings.map { it.toResponse() }, page, size, result.total, (result.total + size - 1) / size)
    }

    @GetMapping("/recommendations")
    fun recommendations(
        @RequestParam startDate: LocalDate,
        @RequestParam endDate: LocalDate,
        @RequestParam(required = false) hotelId: UUID?,
        request: HttpServletRequest,
    ): List<RoomRecommendationResponse> =
        recommendations
            .recommendations(startDate, endDate, hotelId, request.getAttribute("traceId").toString())
            .map { RoomRecommendationResponse(it.id, it.hotelId, it.number, it.available, it.timesBooked) }

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
    ): BookingResponse = queries.get(jwt.toActor(), id).toResponse()
}

private fun Jwt.toActor() = Actor(UUID.fromString(subject), getClaimAsStringList("roles").contains("ADMIN"))

private fun Booking.toResponse() =
    BookingResponse(
        id,
        userId,
        roomId,
        hotelId,
        startDate,
        endDate,
        createdAt,
        status,
        step,
        attemptId,
        compensationStatus.takeIf { status == BookingStatus.CANCELLED },
    )

data class RoomRecommendationResponse(
    val id: UUID,
    val hotelId: UUID,
    val number: String,
    val available: Boolean,
    val timesBooked: Long,
)

data class BookingPageResponse(
    val items: List<BookingResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)
