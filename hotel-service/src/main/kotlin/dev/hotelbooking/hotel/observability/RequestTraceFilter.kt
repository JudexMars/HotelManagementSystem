package dev.hotelbooking.hotel.observability

import dev.hotelbooking.web.http.TraceIds
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTraceFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = TraceIds.resolve(request.getHeader("X-Trace-Id"))
        request.setAttribute("traceId", traceId)
        response.setHeader("X-Trace-Id", traceId)
        MDC.putCloseable("traceId", traceId).use { filterChain.doFilter(request, response) }
    }
}
