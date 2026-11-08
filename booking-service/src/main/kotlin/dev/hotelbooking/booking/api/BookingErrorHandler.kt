package dev.hotelbooking.booking.api
import dev.hotelbooking.booking.application.BookingCreationException
import dev.hotelbooking.booking.domain.BookingError
import dev.hotelbooking.booking.domain.BookingException
import dev.hotelbooking.booking.domain.BookingStatus
import dev.hotelbooking.booking.domain.CompensationStatus
import dev.hotelbooking.web.http.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

data class BookingFailureResponse(
    val code: String,
    val message: String,
    val traceId: String,
    val bookingId: UUID,
    val status: BookingStatus,
    val compensationStatus: CompensationStatus,
)

@RestControllerAdvice
class BookingErrorHandler {
    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(BookingCreationException::class)
    fun creationFailure(
        error: BookingCreationException,
        request: HttpServletRequest,
    ): ResponseEntity<BookingFailureResponse> {
        val booking = error.booking
        return ResponseEntity.status(error.reason.toHttpStatus()).header("Location", "/api/bookings/${booking.id}").body(
            BookingFailureResponse(
                error.reason.name,
                "Оформление не завершено",
                request.getAttribute("traceId").toString(),
                booking.id,
                booking.status,
                booking.compensationStatus,
            ),
        )
    }

    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(BookingException::class)
    fun failure(
        error: BookingException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        ResponseEntity.status(error.reason.toHttpStatus()).body(
            ApiError(
                error.reason.name,
                if (error.reason == BookingError.BOOKING_NOT_FOUND) "Бронирование не найдено" else "Операция бронирования отклонена",
                request.getAttribute("traceId").toString(),
            ),
        )
}

private fun BookingError.toHttpStatus(): Int =
    when (this) {
        BookingError.INVALID_BOOKING, BookingError.INVALID_IDEMPOTENCY_KEY, BookingError.INVALID_BOOKING_PAGE -> 400
        BookingError.BOOKING_FILTER_FORBIDDEN -> 403
        BookingError.BOOKING_NOT_FOUND, BookingError.HOTEL_NOT_FOUND -> 404
        BookingError.HOTEL_ACCESS_DENIED -> 502
        BookingError.BOOKING_DEADLINE, BookingError.HOTEL_TIMEOUT -> 504
        BookingError.BOOKING_CANCELLED, BookingError.CANCELLATION_TOO_LATE,
        BookingError.ROOM_UNAVAILABLE, BookingError.ROOM_NOT_FOUND, BookingError.HOLD_EXPIRED,
        BookingError.ATTEMPT_CONFLICT, BookingError.ATTEMPT_RELEASED, BookingError.IDEMPOTENCY_CONFLICT,
        -> 409
        BookingError.HOTEL_UNAVAILABLE, BookingError.HOTEL_CIRCUIT_OPEN, BookingError.INVENTORY_PROTOCOL_ERROR -> 503
    }
