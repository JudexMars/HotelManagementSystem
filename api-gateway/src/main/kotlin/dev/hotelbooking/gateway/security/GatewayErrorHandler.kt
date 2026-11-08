package dev.hotelbooking.gateway.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.hotelbooking.web.http.ApiErrors
import dev.hotelbooking.web.http.TraceIds
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
@Order(-2)
class GatewayErrorHandler(
    private val mapper: ObjectMapper,
) : ErrorWebExceptionHandler {
    override fun handle(
        exchange: ServerWebExchange,
        exception: Throwable,
    ): Mono<Void> {
        if (exchange.response.isCommitted) return Mono.error(exception)
        val status = (exception as? ResponseStatusException)?.statusCode ?: HttpStatus.INTERNAL_SERVER_ERROR
        val traceId = exchange.getAttribute<String>("traceId") ?: TraceIds.resolve(null)
        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        exchange.response.headers.set("X-Trace-Id", traceId)
        val body = mapper.writeValueAsBytes(ApiErrors.forStatus(status.value(), traceId))
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body)))
    }
}
