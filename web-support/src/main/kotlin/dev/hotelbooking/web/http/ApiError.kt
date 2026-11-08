package dev.hotelbooking.web.http

import java.util.UUID

data class ApiError(
    val code: String,
    val message: String,
    val traceId: String,
)

object TraceIds {
    private val valid = Regex("[a-zA-Z0-9_-]{1,64}")

    fun resolve(candidate: String?): String = candidate?.takeIf { valid.matches(it) } ?: UUID.randomUUID().toString()
}

object ApiErrors {
    fun forStatus(
        status: Int,
        traceId: String,
    ): ApiError {
        val (code, message) =
            when (status) {
                400 -> "INVALID_REQUEST" to "Некорректный запрос"
                401 -> "UNAUTHORIZED" to "Требуется действительный токен"
                403 -> "FORBIDDEN" to "Недостаточно прав"
                404 -> "NOT_FOUND" to "Ресурс не найден"
                405 -> "METHOD_NOT_ALLOWED" to "Метод не поддерживается"
                409 -> "CONFLICT" to "Конфликт состояния"
                415 -> "UNSUPPORTED_MEDIA_TYPE" to "Формат запроса не поддерживается"
                503 -> "SERVICE_UNAVAILABLE" to "Сервис временно недоступен"
                else -> "INTERNAL_ERROR" to "Внутренняя ошибка"
            }
        return ApiError(code, message, traceId)
    }
}
