package dev.hotelbooking.booking.identity.api

import dev.hotelbooking.booking.identity.domain.IdentityError
import dev.hotelbooking.booking.identity.domain.IdentityException
import dev.hotelbooking.web.http.ApiError
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class IdentityErrorHandler {
    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(IdentityException::class)
    fun identityFailure(
        exception: IdentityException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val (status, message) =
            when (exception.reason) {
                IdentityError.USER_NOT_FOUND -> HttpStatus.NOT_FOUND to "Пользователь не найден"
                IdentityError.INVALID_PAGE -> HttpStatus.BAD_REQUEST to "Page должен быть неотрицательным, size — от 1 до 100"
                IdentityError.CONCURRENT_UPDATE -> HttpStatus.CONFLICT to "Пользователь изменён другим запросом"
                IdentityError.INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED to "Неверные учётные данные"
                IdentityError.USERNAME_TAKEN -> HttpStatus.CONFLICT to "Имя пользователя уже занято"
                IdentityError.INVALID_USERNAME -> HttpStatus.BAD_REQUEST to "Username должен содержать 1–100 символов без пробельных"
                IdentityError.INVALID_PASSWORD -> HttpStatus.BAD_REQUEST to "Пароль должен содержать 8–72 байта UTF-8"
            }
        return ResponseEntity.status(status).body(ApiError(exception.reason.name, message, request.getAttribute("traceId").toString()))
    }

    // Invoked by Spring MVC through the exception-handler mapping.
    @Suppress("unused")
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidBody(request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError("INVALID_REQUEST", "Некорректное тело запроса", request.getAttribute("traceId").toString()),
        )
}
