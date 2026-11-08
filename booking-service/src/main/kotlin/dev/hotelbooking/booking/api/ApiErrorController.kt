package dev.hotelbooking.booking.api

import dev.hotelbooking.web.http.ApiError
import dev.hotelbooking.web.http.ApiErrors
import dev.hotelbooking.web.http.TraceIds
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Hidden
class ApiErrorController : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<ApiError> {
        val status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)?.takeIf { it in 400..599 } ?: 500
        val traceId = request.getAttribute("traceId") as? String ?: TraceIds.resolve(null)
        return ResponseEntity.status(status).header("X-Trace-Id", traceId).body(ApiErrors.forStatus(status, traceId))
    }
}
