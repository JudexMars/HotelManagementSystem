package dev.hotelbooking.hotel.analytics.api

import dev.hotelbooking.hotel.analytics.domain.OccupancyError
import dev.hotelbooking.hotel.analytics.domain.OccupancyException
import dev.hotelbooking.web.http.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class OccupancyErrorHandler {
    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(OccupancyException::class)
    fun failure(
        error: OccupancyException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val (status, message) =
            when (error.reason) {
                OccupancyError.HOTEL_NOT_FOUND -> 404 to "Отель не найден"
                OccupancyError.INVALID_REPORT_PERIOD -> 400 to "Конец периода должен быть позже начала"
                OccupancyError.INVALID_REPORT_SORT -> 400 to "Допустимы sort=occupancy/bookings и direction=asc/desc"
            }
        return ResponseEntity.status(status).body(ApiError(error.reason.name, message, request.getAttribute("traceId").toString()))
    }
}
