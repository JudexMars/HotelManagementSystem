package dev.hotelbooking.hotel.inventory.api
import dev.hotelbooking.hotel.inventory.domain.InventoryError
import dev.hotelbooking.hotel.inventory.domain.InventoryException
import dev.hotelbooking.web.http.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class InventoryErrorHandler {
    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(InventoryException::class)
    fun failure(
        error: InventoryException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val (status, message) =
            when (error.reason) {
                InventoryError.ATTEMPT_NOT_FOUND -> 404 to "Попытка не найдена"
                InventoryError.INVALID_PERIOD -> 400 to "Выезд должен быть позже заезда"
                InventoryError.ROOM_NOT_FOUND -> 404 to "Номер не существует"
                InventoryError.ROOM_UNAVAILABLE -> 409 to "Номер недоступен на выбранный период"
                InventoryError.ATTEMPT_CONFLICT -> 409 to "Попытка уже связана с другими параметрами"
                InventoryError.HOLD_EXPIRED -> 409 to "Срок удержания истёк"
                InventoryError.ATTEMPT_RELEASED -> 409 to "Попытка уже освобождена"
            }
        return ResponseEntity.status(status).body(ApiError(error.reason.name, message, request.getAttribute("traceId").toString()))
    }
}
