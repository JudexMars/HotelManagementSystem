package dev.hotelbooking.hotel.catalog.api
import dev.hotelbooking.hotel.catalog.domain.CatalogError
import dev.hotelbooking.hotel.catalog.domain.CatalogException
import dev.hotelbooking.web.http.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class CatalogErrorHandler {
    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(CatalogException::class)
    fun failure(
        error: CatalogException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val (status, message) =
            when (error.reason) {
                CatalogError.CATALOG_IN_USE -> 409 to "Есть действующие удержания или незавершённые проживания"
                CatalogError.HOTEL_NOT_FOUND -> 404 to "Отель не найден"
                CatalogError.ROOM_NOT_FOUND -> 404 to "Номер не найден"
                CatalogError.ROOM_NUMBER_TAKEN -> 409 to "Обозначение номера уже занято в отеле"
                CatalogError.INVALID_CATALOG_INPUT -> 400 to "Некорректные параметры каталога"
                CatalogError.CONCURRENT_UPDATE -> 409 to "Каталог изменён другим запросом"
            }
        return ResponseEntity.status(status).body(ApiError(error.reason.name, message, request.getAttribute("traceId").toString()))
    }

    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidBody(request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError("INVALID_REQUEST", "Некорректное тело запроса", request.getAttribute("traceId").toString()),
        )
}
